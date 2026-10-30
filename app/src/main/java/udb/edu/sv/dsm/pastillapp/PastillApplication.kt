package udb.edu.sv.dsm.pastillapp

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle

class PastillApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notificaciones.crearCanales(this)

        // "Tamaño de texto" de Configuración: se aplica a TODAS las pantallas
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val config = Configuration(activity.resources.configuration)
                config.fontScale = Ajustes.escalaTexto(activity)
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
