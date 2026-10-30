package udb.edu.sv.dsm.pastillapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Se ejecuta cuando AlarmManager dispara una toma, un recordatorio previo o un "posponer". */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getStringExtra(AlarmScheduler.EXTRA_MED_ID) ?: return
        val hhmm = intent.getStringExtra(AlarmScheduler.EXTRA_HORA) ?: return
        val tipo = intent.getStringExtra(AlarmScheduler.EXTRA_TIPO) ?: AlarmScheduler.TIPO_DOSIS
        val programada = intent.getLongExtra(AlarmScheduler.EXTRA_PROGRAMADA, System.currentTimeMillis())
        val spec = AlarmStore.buscar(context, medId) ?: return     // medicamento ya eliminado

        if (tipo == AlarmScheduler.TIPO_PREVIO) {
            Notificaciones.avisoSimple(
                context,
                Notificaciones.idPara(medId, hhmm + "previo"),
                "Pronto: ${spec.nombre}",
                "A las ${FrecuenciaConfig.hora12(hhmm)} · ${spec.dosisTexto}",
                spec.previoMin * 60_000L
            )
            return
        }

        // Toma normal: dejar programada la SIGUIENTE ocurrencia de este horario
        if (tipo == AlarmScheduler.TIPO_DOSIS) {
            AlarmScheduler.programarOcurrencia(context, spec, hhmm, programada + 60_000L)
        }

        try {
            ContextCompat.startForegroundService(
                context,
                AlarmService.intent(context, AlarmService.ACCION_SONAR, medId, hhmm, programada)
            )
        } catch (e: Exception) {
            // Respaldo: si el sistema no deja iniciar el servicio, al menos una notificación
            Notificaciones.avisoSimple(
                context, Notificaciones.idPara(medId, hhmm),
                "Hora de tu medicamento", "${spec.nombre} · ${spec.dosisTexto}"
            )
        }
    }
}
