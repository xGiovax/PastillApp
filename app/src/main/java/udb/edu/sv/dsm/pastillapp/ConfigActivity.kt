package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import udb.edu.sv.dsm.pastillapp.databinding.ActivityConfigBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemConfigFilaBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private val valores = mutableMapOf<String, TextView>()

    private val opcionesRecordatorio = listOf(5, 15, 30, 60)
    private val opcionesInventario = listOf(0, 3, 5, 10, 15, 20)

    // Selector de sonidos de ALARMA del propio teléfono (no hace falta incluir audios en la app)
    private val elegirSonido = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = resultado.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val titulo = RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Personalizado"
                Ajustes.guardarSonido(this, uri, titulo)
                pintar()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDateTime.text =
            SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault()).format(Date())
        cargarNombre()

        crearFilas()
        pintar()
        configurarNavegacion()
    }

    // ---------------------------------------------------------------- Filas

    private fun crearFilas() {
        agregarFila(binding.llGeneral, "sonido", "Sonido de alarma") { abrirSelectorSonido() }

        agregarFila(binding.llGeneral, "volumen", "Volumen") {
            elegir("Volumen de la alarma", Ajustes.NOMBRES_VOLUMEN, Ajustes.volumen(this)) {
                Ajustes.guardarVolumen(this, it)
            }
        }

        agregarFila(binding.llGeneral, "recordatorio", "Recordatorio") {
            elegir(
                "Recordatorio previo (medicamentos nuevos)",
                opcionesRecordatorio.map { textoMinutos(it) },
                opcionesRecordatorio.indexOf(Ajustes.recordatorioPorDefecto(this))
            ) { Ajustes.guardarRecordatorioPorDefecto(this, opcionesRecordatorio[it]) }
        }

        // TEMPORAL: para probar sonido y volumen sin esperar. Bórralo cuando termines de probar.
        agregarFila(binding.llGeneral, "probar", "Probar alarma") {
            val ok = AlarmScheduler.probar(this, 10)
            Toast.makeText(
                this,
                if (ok) "Suena en 10 segundos. Puedes bloquear el teléfono."
                else "Primero agrega un medicamento con horario",
                Toast.LENGTH_LONG
            ).show()
        }

        agregarFila(binding.llInventario, "inventario", "Avisar cuando quede") {
            elegir(
                "Avisar cuando queden",
                opcionesInventario.map { if (it == 0) "No avisar" else "$it pastillas" },
                opcionesInventario.indexOf(Ajustes.avisoPocasPastillas(this))
            ) { Ajustes.guardarAvisoPocasPastillas(this, opcionesInventario[it]) }
        }

        agregarFila(binding.llAccesibilidad, "texto", "Tamaño de texto") {
            elegir("Tamaño de texto", Ajustes.NOMBRES_TEXTO, Ajustes.indiceTexto(this)) {
                Ajustes.guardarIndiceTexto(this, it)
                recreate()          // vuelve a dibujar esta pantalla con el nuevo tamaño
            }
        }
    }

    private fun agregarFila(
        contenedor: LinearLayout, clave: String, titulo: String, alTocar: () -> Unit
    ) {
        val fila = ItemConfigFilaBinding.inflate(layoutInflater, contenedor, false)
        fila.tvTitulo.text = titulo
        fila.root.setOnClickListener { alTocar() }
        valores[clave] = fila.tvValor
        contenedor.addView(fila.root)
    }

    private fun pintar() {
        valores["sonido"]?.text = "${Ajustes.sonidoTitulo(this)} >"
        valores["volumen"]?.text = "${Ajustes.NOMBRES_VOLUMEN[Ajustes.volumen(this)]} >"
        valores["recordatorio"]?.text = "${textoMinutos(Ajustes.recordatorioPorDefecto(this))} >"
        valores["probar"]?.text = "10 s >"
        val aviso = Ajustes.avisoPocasPastillas(this)
        valores["inventario"]?.text = if (aviso == 0) "No avisar >" else "$aviso pastillas >"
        valores["texto"]?.text = "${Ajustes.NOMBRES_TEXTO[Ajustes.indiceTexto(this)]} >"
    }

    private fun textoMinutos(minutos: Int) = if (minutos == 60) "1 hora" else "$minutos minutos"

    private fun elegir(
        titulo: String, opciones: List<String>, actual: Int, alElegir: (Int) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setSingleChoiceItems(opciones.toTypedArray(), actual) { dialogo, cual ->
                alElegir(cual)
                pintar()
                dialogo.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun abrirSelectorSonido() {
        val actual = Ajustes.sonidoUri(this)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido de alarma")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, actual)
        }
        elegirSonido.launch(intent)
    }

    // ---------------------------------------------------------------- Encabezado y menú

    private fun cargarNombre() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val google = user.displayName
        if (!google.isNullOrBlank()) {
            saludar(google)
            return
        }
        FirebaseFirestore.getInstance().collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { doc ->
                saludar(doc.getString("nombre") ?: user.email?.substringBefore("@"))
            }
            .addOnFailureListener { saludar(user.email?.substringBefore("@")) }
    }

    private fun saludar(nombre: String?) {
        binding.tvGreeting.text = if (nombre.isNullOrBlank()) {
            "¡Hola!"
        } else {
            "¡Hola ${nombre.trim().split(" ").first()}!"
        }
    }

    private fun configurarNavegacion() {
        binding.bottomNavigation.selectedItemId = R.id.nav_config
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_config -> true
                R.id.nav_inicio -> {
                    startActivity(
                        Intent(this, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                    true
                }
                R.id.nav_medicamentos -> {
                    startActivity(Intent(this, MedicamentosActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_calendario -> {
                    startActivity(Intent(this, CalendarioActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_reportes -> {
                    startActivity(Intent(this, ReportesActivity::class.java))
                    finish()
                    true
                }
                else -> {
                    Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }
}
