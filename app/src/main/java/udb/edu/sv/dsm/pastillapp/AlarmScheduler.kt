package udb.edu.sv.dsm.pastillapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Base64
import java.io.File
import java.util.Calendar

/**
 * Programa las alarmas con AlarmManager.
 *  - Toma:  setAlarmClock  (alarma "de reloj": suena aunque el teléfono esté en reposo profundo)
 *  - Aviso previo: alarma exacta discreta (solo notificación)
 * Solo se programa la PRÓXIMA ocurrencia de cada horario; cuando suena, se programa la siguiente.
 */
object AlarmScheduler {
    const val ACCION = "udb.edu.sv.dsm.pastillapp.ALARMA_MEDICAMENTO"
    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_HORA = "hora"
    const val EXTRA_TIPO = "tipo"
    const val EXTRA_PROGRAMADA = "programada"

    const val TIPO_DOSIS = "dosis"
    const val TIPO_PREVIO = "previo"
    const val TIPO_POSPUESTA = "pospuesta"

    // ------------------------------------------------------------ API pública

    /** Llamar con la lista de medicamentos del usuario (Home lo hace al abrir). */
    fun sincronizar(ctx: Context, meds: List<Medicamento>) {
        val app = ctx.applicationContext
        cancelarTodo(app)                                   // quita alarmas de la lista anterior
        val specs = meds.mapNotNull { AlarmaSpec.desde(it) }
        cachearFotos(app, meds)
        AlarmStore.guardar(app, specs)
        specs.forEach { programarSpec(app, it) }
    }

    /** Tras reiniciar el teléfono / cambio de hora: no necesita internet. */
    fun reprogramarDesdeStore(ctx: Context) {
        val app = ctx.applicationContext
        AlarmStore.leer(app).forEach { programarSpec(app, it) }
    }

    /** Al cerrar sesión: nada del usuario anterior debe seguir sonando. */
    fun limpiarTodo(ctx: Context) {
        val app = ctx.applicationContext
        cancelarTodo(app)
        AlarmStore.limpiar(app)
        File(app.filesDir, "fotos").deleteRecursively()
    }

    fun archivoFoto(ctx: Context, medId: String): File =
        File(File(ctx.filesDir, "fotos"), "$medId.img")

    /** Programa la siguiente toma (y su aviso previo) de un horario, después de [despuesDe]. */
    fun programarOcurrencia(ctx: Context, spec: AlarmaSpec, hhmm: String, despuesDe: Long) {
        val disparo = proximaOcurrencia(hhmm, spec.dias, despuesDe, spec.finEpoch) ?: return
        programarAlarma(ctx, spec.medId, hhmm, TIPO_DOSIS, disparo, disparo)

        if (spec.previoActivo && spec.previoMin > 0) {
            val previo = disparo - spec.previoMin * 60_000L
            if (previo > System.currentTimeMillis()) {
                programarAlarma(ctx, spec.medId, hhmm, TIPO_PREVIO, previo, disparo)
            }
        }
    }

    /** "Posponer 10 min": una alarma extra, sin tocar la serie normal. */
    fun posponer(ctx: Context, spec: AlarmaSpec, hhmm: String, programada: Long, minutos: Int) {
        val disparo = System.currentTimeMillis() + minutos * 60_000L
        programarAlarma(ctx, spec.medId, hhmm, TIPO_POSPUESTA, disparo, programada)
    }

    /** SOLO PARA PRUEBAS: hace sonar la primera alarma guardada dentro de [segundos]. */
    fun probar(ctx: Context, segundos: Int = 10): Boolean {
        val spec = AlarmStore.leer(ctx).firstOrNull() ?: return false
        val disparo = System.currentTimeMillis() + segundos * 1000L
        programarAlarma(ctx, spec.medId, spec.horarios.first(), TIPO_POSPUESTA, disparo, disparo)
        return true
    }

    // ------------------------------------------------------------ Internos

    private fun programarSpec(ctx: Context, spec: AlarmaSpec) {
        val ahora = System.currentTimeMillis()
        spec.horarios.forEach { programarOcurrencia(ctx, spec, it, ahora) }
    }

    private fun cancelarTodo(ctx: Context) {
        AlarmStore.leer(ctx).forEach { spec ->
            spec.horarios.forEach { hhmm ->
                cancelar(ctx, spec.medId, hhmm, TIPO_DOSIS)
                cancelar(ctx, spec.medId, hhmm, TIPO_PREVIO)
            }
        }
    }

    /** Próxima fecha (ms) en que cae [hhmm] en alguno de [dias], estrictamente después de [despuesDe]. */
    fun proximaOcurrencia(hhmm: String, dias: List<Int>, despuesDe: Long, fin: Long): Long? {
        val h = hhmm.substringBefore(":").toInt()
        val m = hhmm.substringAfter(":").toInt()
        val cal = Calendar.getInstance()
        cal.timeInMillis = despuesDe
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        repeat(8) {
            val t = cal.timeInMillis
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val iso = if (dow == Calendar.SUNDAY) 7 else dow - 1       // 1 = Lunes ... 7 = Domingo
            if (t > despuesDe && iso in dias) {
                return if (fin > 0 && t > fin) null else t
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun pendingIntent(
        ctx: Context, medId: String, hhmm: String, tipo: String, programada: Long
    ): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACCION
            // El "data" hace único cada PendingIntent (medicamento + hora + tipo)
            data = Uri.parse("pastillapp://alarma/$medId/${hhmm.replace(":", "")}/$tipo")
            putExtra(EXTRA_MED_ID, medId)
            putExtra(EXTRA_HORA, hhmm)
            putExtra(EXTRA_TIPO, tipo)
            putExtra(EXTRA_PROGRAMADA, programada)
        }
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelar(ctx: Context, medId: String, hhmm: String, tipo: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(ctx, medId, hhmm, tipo, 0L))
    }

    private fun programarAlarma(
        ctx: Context, medId: String, hhmm: String, tipo: String, disparo: Long, programada: Long
    ) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(ctx, medId, hhmm, tipo, programada)
        val puedeExacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        try {
            if (!puedeExacta) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
            } else if (tipo == TIPO_PREVIO) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
            } else {
                val abrirApp = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, HomeActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(disparo, abrirApp), pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
        }
    }

    /** La foto se guarda como archivo local para que la alarma la muestre sin internet. */
    private fun cachearFotos(ctx: Context, meds: List<Medicamento>) {
        val dir = File(ctx.filesDir, "fotos").apply { mkdirs() }
        val vigentes = HashSet<String>()
        meds.forEach { med ->
            val b64 = med.fotoBase64
            if (!b64.isNullOrEmpty() && med.id.isNotBlank()) {
                try {
                    File(dir, "${med.id}.img").writeBytes(Base64.decode(b64, Base64.NO_WRAP))
                    vigentes.add("${med.id}.img")
                } catch (e: Exception) {
                    // foto corrupta: se ignora y la alarma usará el ícono por defecto
                }
            }
        }
        dir.listFiles()?.forEach { if (it.name !in vigentes) it.delete() }
    }
}
