package udb.edu.sv.dsm.pastillapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notificaciones {
    const val CANAL_ALARMA = "alarma_medicamentos"
    const val CANAL_AVISOS = "avisos_medicamentos"

    fun idPara(medId: String, hhmm: String): Int = (medId + hhmm).hashCode()

    fun crearCanales(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val alarma = NotificationChannel(
            CANAL_ALARMA, "Alarma de medicamentos", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Suena a la hora de tomar tu medicamento"
            setSound(null, null)               // el sonido lo reproduce AlarmService
            enableVibration(false)             // la vibración también
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val avisos = NotificationChannel(
            CANAL_AVISOS, "Avisos", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Recordatorio previo y avisos de inventario" }

        nm.createNotificationChannels(listOf(alarma, avisos))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Notificación de la alarma, con pantalla completa y botones. */
    fun alarma(
        ctx: Context, spec: AlarmaSpec, hhmm: String, programada: Long, sonando: Boolean
    ): Notification {
        crearCanales(ctx)
        val id = idPara(spec.medId, hhmm)

        val abrir = PendingIntent.getActivity(
            ctx, id, AlarmActivity.intent(ctx, spec.medId, hhmm, programada), flags()
        )

        fun accion(accion: String, titulo: String, offset: Int) = NotificationCompat.Action.Builder(
            0, titulo,
            PendingIntent.getService(
                ctx, id + offset,
                AlarmService.intent(ctx, accion, spec.medId, hhmm, programada), flags()
            )
        ).build()

        val b = NotificationCompat.Builder(ctx, CANAL_ALARMA)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Hora de tu medicamento")
            .setContentText("${spec.nombre} · ${spec.dosisTexto}")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(abrir)
            .setOngoing(sonando)
            .setAutoCancel(false)
            .addAction(accion(AlarmService.ACCION_TOMADA, "Ya la tomé", 1))
            .addAction(accion(AlarmService.ACCION_POSPONER, "Posponer 10 min", 2))
            .addAction(accion(AlarmService.ACCION_OMITIR, "Omitir", 3))

        if (sonando) b.setFullScreenIntent(abrir, true)
        return b.build()
    }

    /** Notificación mínima (solo para cumplir el requisito de servicio en primer plano). */
    fun generica(ctx: Context): Notification {
        crearCanales(ctx)
        return NotificationCompat.Builder(ctx, CANAL_ALARMA)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("PastillApp")
            .setContentText("Alarma de medicamento")
            .build()
    }

    /** Recordatorio previo, avisos de inventario y respaldo si la alarma no pudo iniciar. */
    @SuppressLint("MissingPermission")
    fun avisoSimple(ctx: Context, id: Int, titulo: String, texto: String, timeoutMs: Long = 0L) {
        crearCanales(ctx)
        val abrir = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags()
        )
        val b = NotificationCompat.Builder(ctx, CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }
}
