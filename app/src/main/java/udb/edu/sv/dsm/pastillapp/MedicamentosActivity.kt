package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import udb.edu.sv.dsm.pastillapp.databinding.ActivityMedicamentosBinding
import udb.edu.sv.dsm.pastillapp.databinding.DialogDetalleMedicamentoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MedicamentosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMedicamentosBinding
    private val db = FirebaseFirestore.getInstance()
    private var escucha: ListenerRegistration? = null

    private var todos: List<Medicamento> = emptyList()
    private val adaptador = MedicamentoAdapter { abrirDetalle(it) }

    // Filtros
    private var filtroEstado = 0      // 0 Activos | 1 Terminados | 2 Todos
    private var filtroTipo = 0        // 0 Todos | 1 Pastilla | 2 Líquido
    private var orden = 0             // 0 Próximos a acabarse | 1 Nombre A-Z | 2 Más recientes
    private var busqueda = ""

    // Detalle abierto (se actualiza solo si cambian los datos)
    private var detalle: AlertDialog? = null
    private var detalleBinding: DialogDetalleMedicamentoBinding? = null
    private var detalleId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMedicamentosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDateTime.text =
            SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault()).format(Date())
        cargarNombre()

        binding.rvMedicamentos.layoutManager = GridLayoutManager(this, 2)
        binding.rvMedicamentos.adapter = adaptador

        configurarFiltros()
        configurarNavegacion()
    }

    override fun onStart() {
        super.onStart()
        escuchar()
    }

    override fun onStop() {
        escucha?.remove()
        escucha = null
        super.onStop()
    }

    // ---------------------------------------------------------------- Datos en vivo

    private fun escuchar() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        escucha?.remove()
        escucha = db.collection("usuarios").document(uid).collection("medicamentos")
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                todos = snap.toObjects(Medicamento::class.java)

                // Mantiene las alarmas al día: edición, eliminación y "terminado" las actualizan solas.
                // (Se omite solo si es una caché vacía sin cambios, para no cancelar alarmas por error.)
                val cacheVacia = snap.metadata.isFromCache && snap.isEmpty && !snap.metadata.hasPendingWrites()
                if (!cacheVacia) AlarmScheduler.sincronizar(this, todos)

                aplicarFiltros()
                refrescarDetalle()
            }
    }

    // ---------------------------------------------------------------- Filtros

    private fun configurarFiltros() {
        configurarSpinner(binding.spEstado, listOf("Activos", "Terminados", "Todos")) { filtroEstado = it }
        configurarSpinner(binding.spTipo, listOf("Tipo", "Pastilla", "Líquido")) { filtroTipo = it }
        configurarSpinner(
            binding.spOrden, listOf("Próximos a acabarse", "Nombre A-Z", "Más recientes")
        ) { orden = it }

        binding.etBuscar.doAfterTextChanged {
            busqueda = it?.toString().orEmpty()
            aplicarFiltros()
        }
    }

    private fun configurarSpinner(spinner: Spinner, opciones: List<String>, alElegir: (Int) -> Unit) {
        spinner.adapter = ArrayAdapter(this, R.layout.item_spinner_filtro, opciones).apply {
            setDropDownViewResource(R.layout.item_spinner_filtro)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                alElegir(position)
                aplicarFiltros()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Qué fracción queda (1.0 = lleno). Sin datos de cantidad va al final. */
    private fun fraccion(med: Medicamento): Double {
        val total = med.totalInicial()
        return if (total <= 0) 2.0 else med.existenciaActual / total
    }

    private fun aplicarFiltros() {
        var lista = todos

        lista = when (filtroEstado) {
            0 -> lista.filter { !it.terminado }
            1 -> lista.filter { it.terminado }
            else -> lista
        }
        if (filtroTipo == 1) lista = lista.filter { it.tipo == Medicamento.TIPO_PASTILLA }
        if (filtroTipo == 2) lista = lista.filter { it.tipo == Medicamento.TIPO_LIQUIDO }

        val texto = busqueda.trim()
        if (texto.isNotEmpty()) lista = lista.filter { it.nombre.contains(texto, ignoreCase = true) }

        lista = when (orden) {
            0 -> lista.sortedBy { fraccion(it) }
            1 -> lista.sortedBy { it.nombre.lowercase() }
            else -> lista.sortedByDescending { it.creadoEn?.time ?: Long.MAX_VALUE }
        }

        adaptador.submit(lista)
        binding.tvVacio.isVisible = lista.isEmpty()
    }

    // ---------------------------------------------------------------- Detalle

    private fun abrirDetalle(med: Medicamento) {
        detalle?.dismiss()
        val b = DialogDetalleMedicamentoBinding.inflate(layoutInflater)
        val dialogo = AlertDialog.Builder(this).setView(b.root).create()
        dialogo.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogo.setOnDismissListener {
            detalle = null
            detalleBinding = null
            detalleId = null
        }

        detalle = dialogo
        detalleBinding = b
        detalleId = med.id
        llenarDetalle(b, med)

        dialogo.show()
        dialogo.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun refrescarDetalle() {
        val id = detalleId ?: return
        val b = detalleBinding ?: return
        val med = todos.firstOrNull { it.id == id }
        if (med == null) detalle?.dismiss() else llenarDetalle(b, med)
    }

    private fun llenarDetalle(b: DialogDetalleMedicamentoBinding, med: Medicamento) {
        val formato = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        FotosMedicamento.mostrar(b.ivFotoDetalle, med)
        b.tvNombre.text = "Nombre: ${med.nombre}"
        b.tvTipo.text = "Tipo: ${if (med.tipo == Medicamento.TIPO_LIQUIDO) "Líquido" else "Pastilla"}"
        b.tvDosis.text = "Dosis: ${med.dosisTexto()}"
        b.tvRestante.text = "Cantidad restante: ${med.textoRestante()}"

        val pct = med.porcentajeRestante()
        b.progreso.setProgressCompat(pct, false)
        b.tvPorcentaje.text = "$pct %"
        b.tvPorcentaje.setTextColor(Color.parseColor(if (pct >= 55) "#FFFFFF" else "#1C1B1F"))

        b.tvFechaInicio.text = med.creadoEn?.let { formato.format(it) } ?: "—"
        b.tvFechaFin.text = med.fechaFin()?.let { formato.format(it) } ?: "Indefinido"

        // "Marcar como terminado": se asigna el estado SIN disparar el listener
        b.cbTerminado.setOnCheckedChangeListener(null)
        b.cbTerminado.isChecked = med.terminado
        b.rowTerminado.setOnClickListener { b.cbTerminado.toggle() }
        b.cbTerminado.setOnCheckedChangeListener { _, marcado -> cambiarTerminado(med.id, marcado) }

        b.btnEditar.setOnClickListener {
            detalle?.dismiss()
            startActivity(
                Intent(this, AddMedicamentoActivity::class.java)
                    .putExtra(AddMedicamentoActivity.EXTRA_MED_ID, med.id)
            )
        }
        b.btnEliminar.setOnClickListener { confirmarEliminar(med) }
    }

    private fun coleccion() = FirebaseAuth.getInstance().currentUser?.uid?.let {
        db.collection("usuarios").document(it).collection("medicamentos")
    }

    private fun cambiarTerminado(id: String, terminado: Boolean) {
        coleccion()?.document(id)?.update("terminado", terminado)
            ?.addOnFailureListener {
                Toast.makeText(this, "No se pudo actualizar", Toast.LENGTH_SHORT).show()
            }
        Toast.makeText(
            this,
            if (terminado) "Marcado como terminado. Sus alarmas se desactivaron."
            else "Medicamento reactivado",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmarEliminar(med: Medicamento) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar medicamento")
            .setMessage("¿Eliminar ${med.nombre}? Se cancelarán sus alarmas. El historial de tomas se conserva.")
            .setPositiveButton("Eliminar") { _, _ ->
                coleccion()?.document(med.id)?.delete()
                    ?.addOnFailureListener {
                        Toast.makeText(this, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
                    }
                detalle?.dismiss()
                Toast.makeText(this, "Medicamento eliminado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- Encabezado y menú

    private fun cargarNombre() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val google = user.displayName
        if (!google.isNullOrBlank()) {
            saludar(google)
            return
        }
        db.collection("usuarios").document(user.uid).get()
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
        binding.bottomNavigation.selectedItemId = R.id.nav_medicamentos
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_medicamentos -> true
                R.id.nav_inicio -> {
                    startActivity(
                        Intent(this, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                    true
                }
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
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
