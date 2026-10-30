package udb.edu.sv.dsm.pastillapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import udb.edu.sv.dsm.pastillapp.databinding.ActivityAlarmaBinding

/** Tarjeta "Hora de tu medicamento": aparece sobre la pantalla de bloqueo o sobre lo que se esté haciendo. */
class AlarmActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, medId: String, hhmm: String, programada: Long) =
            Intent(ctx, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AlarmScheduler.EXTRA_MED_ID, medId)
                .putExtra(AlarmScheduler.EXTRA_HORA, hhmm)
                .putExtra(AlarmScheduler.EXTRA_PROGRAMADA, programada)
    }

    private lateinit var binding: ActivityAlarmaBinding

    // Si respondes desde la notificación, esta tarjeta se cierra sola
    private val receptorCerrar = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mostrarSobreBloqueo()
        binding = ActivityAlarmaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ContextCompat.registerReceiver(
            this, receptorCerrar, IntentFilter(AlarmService.ACCION_CERRAR),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        cargar(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cargar(intent)          // llegó otra alarma mientras la tarjeta estaba abierta
    }

    @Suppress("DEPRECATION")
    private fun mostrarSobreBloqueo() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun cargar(i: Intent) {
        val medId = i.getStringExtra(AlarmScheduler.EXTRA_MED_ID)
        val hhmm = i.getStringExtra(AlarmScheduler.EXTRA_HORA)
        val programada = i.getLongExtra(AlarmScheduler.EXTRA_PROGRAMADA, 0L)
        val spec = medId?.let { AlarmStore.buscar(this, it) }
        if (medId == null || hhmm == null || spec == null) {
            finish()
            return
        }

        binding.tvHora.text = FrecuenciaConfig.hora12(hhmm)
        binding.tvNombre.text = spec.nombre
        binding.tvDosis.text = spec.dosisTexto

        // Foto de la pastilla (guardada en el teléfono); si no hay, el ícono por defecto
        val archivo = AlarmScheduler.archivoFoto(this, medId)
        val foto = if (archivo.exists()) BitmapFactory.decodeFile(archivo.absolutePath) else null
        if (foto != null) {
            binding.ivFoto.setImageBitmap(foto)
            if (spec.sinFondo) {
                binding.ivFoto.setPadding(dp(14), dp(14), dp(14), dp(14))
                binding.ivFoto.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                binding.ivFoto.setPadding(0, 0, 0, 0)
                binding.ivFoto.scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            binding.ivFoto.setImageResource(R.drawable.ic_pill_default)
            binding.ivFoto.setPadding(dp(20), dp(20), dp(20), dp(20))
            binding.ivFoto.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        binding.btnTomada.setOnClickListener { responder(AlarmService.ACCION_TOMADA, medId, hhmm, programada) }
        binding.btnPosponer.setOnClickListener { responder(AlarmService.ACCION_POSPONER, medId, hhmm, programada) }
        binding.btnOmitir.setOnClickListener { responder(AlarmService.ACCION_OMITIR, medId, hhmm, programada) }
    }

    private fun responder(accion: String, medId: String, hhmm: String, programada: Long) {
        startService(AlarmService.intent(this, accion, medId, hhmm, programada))
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(receptorCerrar)
        super.onDestroy()
    }
}
