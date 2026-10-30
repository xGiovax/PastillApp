package udb.edu.sv.dsm.pastillapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Tras reiniciar el teléfono, actualizar la app o cambiar la hora, las alarmas se pierden: se reprograman. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.reprogramarDesdeStore(context)
    }
}
