package udb.edu.sv.dsm.pastillapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/** Permisos especiales para que la alarma aparezca con el teléfono bloqueado o en uso. */
object PermisosAlarma {
    private var yaPregunto = false

    fun sobreOtrasAppsOk(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun pantallaCompletaOk(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true

    /** Muestra UN aviso explicando lo que falta; "Configurar" abre la pantalla de ajustes. */
    fun dialogoSiFalta(activity: Activity) {
        if (yaPregunto) return
        val faltaSobre = !sobreOtrasAppsOk(activity)
        val faltaPantalla = !pantallaCompletaOk(activity)
        if (!faltaSobre && !faltaPantalla) return

        val mensaje = StringBuilder(
            "Para que la alarma suene y aparezca aunque el teléfono esté bloqueado o en uso, activa:\n"
        )
        if (faltaSobre) mensaje.append("\n• Mostrar sobre otras apps")
        if (faltaPantalla) mensaje.append("\n• Notificaciones a pantalla completa")

        val paquete = Uri.parse("package:${activity.packageName}")
        AlertDialog.Builder(activity)
            .setTitle("Permisos para la alarma")
            .setMessage(mensaje.toString())
            .setPositiveButton("Configurar") { _, _ ->
                val intent = if (faltaSobre) {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, paquete)
                } else {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, paquete)
                }
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, paquete))
                }
            }
            .setNegativeButton("Ahora no") { _, _ -> yaPregunto = true }
            .show()
    }
}
