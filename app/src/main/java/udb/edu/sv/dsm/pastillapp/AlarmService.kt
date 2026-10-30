package udb.edu.sv.dsm.pastillapp

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import kotlin.math.roundToInt

/**
 * Servicio en primer plano: reproduce el sonido (canal de ALARMA), vibra, enciende la pantalla
 * y abre la tarjeta. También atiende los botones: Ya la tomé / Posponer / Omitir.
 */
@SuppressLint("MissingPermission")
class AlarmService : Service() {

    companion object {
        const val ACCION_SONAR = "udb.edu.sv.dsm.pastillapp.SONAR"
        const val ACCION_TOMADA = "udb.edu.sv.dsm.pastillapp.TOMADA"
        const val ACCION_POSPONER = "udb.edu.sv.dsm.pastillapp.POSPONER"
        const val ACCION_OMITIR = "udb.edu.sv.dsm.pastillapp.OMITIR"
        const val ACCION_CERRAR = "udb.edu.sv.dsm.pastillapp.CERRAR_ALARMA"   // broadcast: cierra la tarjeta

        private const val NOTIF_ID = 4242
        private const val MIN_POSPONER = 10
        private const val SILENCIAR_MS = 2 * 60 * 1000L     // deja de sonar solo a los 2 minutos

        fun intent(ctx: Context, accion: String, medId: String, hhmm: String, programada: Long) =
            Intent(ctx, AlarmService::class.java)
                .setAction(accion)
                .putExtra(AlarmScheduler.EXTRA_MED_ID, medId)
                .putExtra(AlarmScheduler.EXTRA_HORA, hhmm)
                .putExtra(AlarmScheduler.EXTRA_PROGRAMADA, programada)
    }

    private data class EnCurso(val medId: String, val hhmm: String, val programada: Long)

    private var sonando: EnCurso? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var volumenOriginal = -1
    private val handler = Handler(Looper.getMainLooper())
    private val silenciar = Runnable { silenciarPorTiempo() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accion = intent?.action
        val medId = intent?.getStringExtra(AlarmScheduler.EXTRA_MED_ID)
        val hhmm = intent?.getStringExtra(AlarmScheduler.EXTRA_HORA)
        val programada = intent?.getLongExtra(AlarmScheduler.EXTRA_PROGRAMADA, 0L) ?: 0L

        if (accion == ACCION_SONAR) {
            val spec = if (medId != null) AlarmStore.buscar(this, medId) else null
            if (spec == null || hhmm == null) {
                // Obligatorio llamar a startForeground aunque no haya nada que mostrar
                ServiceCompat.startForeground(
                    this, NOTIF_ID, Notificaciones.generica(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                iniciarAlarma(spec, hhmm, programada)
            }
            return START_NOT_STICKY
        }

        val estado = when (accion) {
            ACCION_TOMADA -> "tomada"
            ACCION_POSPONER -> "pospuesta"
            ACCION_OMITIR -> "omitida"
            else -> null
        }
        if (estado != null && medId != null && hhmm != null) {
            responder(estado, medId, hhmm, programada)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- Sonar

    private fun iniciarAlarma(spec: AlarmaSpec, hhmm: String, programada: Long) {
        // Si ya sonaba otra alarma, su notificación queda en la bandeja con sus botones
        sonando?.let { anterior ->
            AlarmStore.buscar(this, anterior.medId)?.let { viejo ->
                NotificationManagerCompat.from(this).notify(
                    Notificaciones.idPara(anterior.medId, anterior.hhmm),
                    Notificaciones.alarma(this, viejo, anterior.hhmm, anterior.programada, false)
                )
            }
        }
        sonando = EnCurso(spec.medId, hhmm, programada)

        ServiceCompat.startForeground(
            this, NOTIF_ID,
            Notificaciones.alarma(this, spec, hhmm, programada, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        reproducir()
        vibrar()
        mantenerDespierto()

        // Con el teléfono en uso: abre la tarjeta directamente (requiere "Mostrar sobre otras apps").
        // Con el teléfono bloqueado: lo cubre la notificación de pantalla completa.
        try {
            startActivity(AlarmActivity.intent(this, spec.medId, hhmm, programada))
        } catch (e: Exception) {
            // el sistema lo bloqueó: queda la notificación emergente con botones
        }

        handler.removeCallbacks(silenciar)
        handler.postDelayed(silenciar, SILENCIAR_MS)
    }

    private fun reproducir() {
        detenerSonido()

        // Volumen del canal de ALARMA según Configuración (se restaura al terminar)
        try {
            val am = getSystemService(AudioManager::class.java)
            volumenOriginal = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val nuevo = (max * Ajustes.factorVolumen(this)).roundToInt().coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, nuevo, 0)
        } catch (e: Exception) {
            volumenOriginal = -1
        }

        val candidatos = listOfNotNull(
            Ajustes.sonidoUri(this),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidatos) {
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                mp?.release()          // probar con el siguiente sonido
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrar() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator = v
        val patron = longArrayOf(0, 800, 600)
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(patron, 0))
        } else {
            v.vibrate(patron, 0)
        }
    }

    private fun mantenerDespierto() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pastillapp:alarma").apply {
            acquire(SILENCIAR_MS + 10_000L)
        }
    }

    private fun detenerSonido() {
        try { player?.stop() } catch (e: Exception) { }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (volumenOriginal >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, volumenOriginal, 0)
            } catch (e: Exception) { }
            volumenOriginal = -1
        }
    }

    /** A los 2 minutos sin respuesta: deja de sonar, pero la notificación con botones se queda. */
    private fun silenciarPorTiempo() {
        detenerSonido()
        val actual = sonando
        sonando = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (actual != null) {
            AlarmStore.buscar(this, actual.medId)?.let { spec ->
                NotificationManagerCompat.from(this).notify(
                    Notificaciones.idPara(actual.medId, actual.hhmm),
                    Notificaciones.alarma(this, spec, actual.hhmm, actual.programada, false)
                )
            }
        }
        stopSelf()
    }

    // ---------------------------------------------------------------- Botones

    private fun responder(estado: String, medId: String, hhmm: String, programada: Long) {
        val esLaQueSuena = sonando?.let { it.medId == medId && it.hhmm == hhmm } == true
        if (esLaQueSuena) {
            handler.removeCallbacks(silenciar)
            detenerSonido()
            sonando = null
        }

        val nm = NotificationManagerCompat.from(this)
        if (esLaQueSuena) nm.cancel(NOTIF_ID)
        nm.cancel(Notificaciones.idPara(medId, hhmm))

        val spec = AlarmStore.buscar(this, medId)
        if (spec != null) {
            when (estado) {
                "tomada" -> descontarInventario(spec)
                "pospuesta" -> AlarmScheduler.posponer(this, spec, hhmm, programada, MIN_POSPONER)
            }
            registrarToma(spec, estado, programada)
        }

        sendBroadcast(Intent(ACCION_CERRAR).setPackage(packageName))   // cierra la tarjeta

        if (sonando == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun descontarInventario(spec: AlarmaSpec) {
        val aDescontar = minOf(spec.descuento, spec.existencia)
        val nueva = (spec.existencia - spec.descuento).coerceAtLeast(0.0)
        AlarmStore.actualizar(this, spec.copy(existencia = nueva))

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            FirebaseFirestore.getInstance()
                .collection("usuarios").document(uid)
                .collection("medicamentos").document(spec.medId)
                .update("existenciaActual", FieldValue.increment(-aDescontar))
        }

        val umbral = Ajustes.avisoPocasPastillas(this)
        if (umbral > 0 && nueva <= umbral) {
            val texto = if (nueva % 1.0 == 0.0) nueva.toInt().toString() else "%.1f".format(nueva)
            Notificaciones.avisoSimple(
                this, Notificaciones.idPara(spec.medId, "stock"),
                "Pocas unidades de ${spec.nombre}",
                "Quedan $texto. Considera reponer tu medicamento."
            )
        }
    }

    /** Historial para Reportes y Calendario: usuarios/{uid}/tomas */
    private fun registrarToma(spec: AlarmaSpec, estado: String, programada: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val datos = hashMapOf<String, Any>(
            "medicamentoId" to spec.medId,
            "nombre" to spec.nombre,
            "dosis" to spec.dosisTexto,
            "estado" to estado,                                   // tomada | omitida | pospuesta
            "programadaEn" to Timestamp(Date(if (programada > 0) programada else System.currentTimeMillis())),
            "registradaEn" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance()
            .collection("usuarios").document(uid)
            .collection("tomas").add(datos)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detenerSonido()
        super.onDestroy()
    }
}
