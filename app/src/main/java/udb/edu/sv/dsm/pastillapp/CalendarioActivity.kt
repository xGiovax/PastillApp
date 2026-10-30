package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import udb.edu.sv.dsm.pastillapp.databinding.ActivityCalendarioBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemTomaCalendarioBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calendario de tomas. Cada día se pinta según lo programado (frecuencia de los medicamentos)
 * contra lo registrado en usuarios/{uid}/tomas:
 *   verde = todo tomado | naranja = parcial | rojo = nada tomado.
 * Las tomas que nadie respondió NO tienen registro: se detectan porque pasó su hora sin "tomada".
 */
class CalendarioActivity : AppCompatActivity() {

    private data class Registro(
        val medId: String, val nombre: String, val programada: Long,
        val estado: String, val registrada: Date?
    )

    private data class Dosis(
        val medId: String, val nombre: String, val horaMs: Long,
        val estado: String,                // tomada | omitida | pendiente
        val registrada: Date?, val med: Medicamento?
    )

    companion object {
        private const val GRACIA_MS = 30 * 60 * 1000L     // 30 min antes de contarla como "no tomada"
        private val VERDE = Color.parseColor("#3F9B4C")
        private val NARANJA = Color.parseColor("#E08A1E")
        private val ROJO = Color.parseColor("#CF4A4A")
        private val AZUL = Color.parseColor("#5B8DEF")
        private val TEXTO = Color.parseColor("#1C1B1F")
    }

    private lateinit var binding: ActivityCalendarioBinding
    private val db = FirebaseFirestore.getInstance()
    private var escuchaMeds: ListenerRegistration? = null
    private var escuchaTomas: ListenerRegistration? = null

    private var meds: List<Medicamento> = emptyList()
    private var registros: List<Registro> = emptyList()
    private val porDia = HashMap<Int, MutableList<Dosis>>()

    private val mes: Calendar = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    private var diaSel = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    private val localeEs = Locale("es", "SV")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalendarioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDateTime.text =
            SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault()).format(Date())
        cargarNombre()

        binding.btnMesAnterior.setOnClickListener { cambiarMes(-1) }
        binding.btnMesSiguiente.setOnClickListener { cambiarMes(1) }

        configurarNavegacion()
        recalcular()
    }

    override fun onStart() {
        super.onStart()
        escucharMedicamentos()
        escucharTomas()
    }

    override fun onStop() {
        escuchaMeds?.remove(); escuchaMeds = null
        escuchaTomas?.remove(); escuchaTomas = null
        super.onStop()
    }

    // ---------------------------------------------------------------- Datos en vivo

    private fun base() = FirebaseAuth.getInstance().currentUser?.uid?.let {
        db.collection("usuarios").document(it)
    }

    private fun escucharMedicamentos() {
        val base = base() ?: return
        escuchaMeds?.remove()
        escuchaMeds = base.collection("medicamentos").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            meds = snap.toObjects(Medicamento::class.java)
            recalcular()
        }
    }

    /** Solo las tomas del mes mostrado (rango sobre un único campo: no necesita índice). */
    private fun escucharTomas() {
        val base = base() ?: return
        escuchaTomas?.remove()
        val fin = (mes.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        escuchaTomas = base.collection("tomas")
            .whereGreaterThanOrEqualTo("programadaEn", Timestamp(mes.time))
            .whereLessThan("programadaEn", Timestamp(fin.time))
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                registros = snap.documents.mapNotNull { doc ->
                    val medId = doc.getString("medicamentoId") ?: return@mapNotNull null
                    val programada = doc.getTimestamp("programadaEn")?.toDate()?.time
                        ?: return@mapNotNull null
                    Registro(
                        medId, doc.getString("nombre") ?: "Medicamento", programada,
                        doc.getString("estado") ?: "omitida",
                        doc.getTimestamp("registradaEn")?.toDate()
                    )
                }
                recalcular()
            }
    }

    private fun cambiarMes(delta: Int) {
        mes.add(Calendar.MONTH, delta)
        val hoy = Calendar.getInstance()
        diaSel = if (hoy.get(Calendar.YEAR) == mes.get(Calendar.YEAR) &&
            hoy.get(Calendar.MONTH) == mes.get(Calendar.MONTH)
        ) hoy.get(Calendar.DAY_OF_MONTH) else 1
        registros = emptyList()
        escucharTomas()
        recalcular()
    }

    // ---------------------------------------------------------------- Cálculo

    private fun clave(medId: String, ms: Long) = "$medId|${ms / 60_000L}"

    private fun prioridad(estado: String) = when (estado) {
        "tomada" -> 3
        "omitida" -> 2
        else -> 1                 // pospuesta
    }

    private fun momento(dia: Int, hhmm: String): Long {
        val c = (mes.clone() as Calendar)
        c.set(Calendar.DAY_OF_MONTH, dia)
        c.set(Calendar.HOUR_OF_DAY, hhmm.substringBefore(":").toInt())
        c.set(Calendar.MINUTE, hhmm.substringAfter(":").toInt())
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun isoDow(c: Calendar): Int {
        val d = c.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1       // 1 = Lunes ... 7 = Domingo
    }

    private fun recalcular() {
        porDia.clear()

        // El registro "más decisivo" de cada toma (tomada gana sobre omitida y pospuesta)
        val mapa = HashMap<String, Registro>()
        registros.forEach { r ->
            val k = clave(r.medId, r.programada)
            val previo = mapa[k]
            if (previo == null || prioridad(r.estado) > prioridad(previo.estado)) mapa[k] = r
        }

        val usados = HashSet<String>()
        val diasMes = mes.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (med in meds) {
            val cfg = med.frecuencia?.let { FrecuenciaConfig.fromMap(it) } ?: continue
            val inicio = med.creadoEn?.time ?: System.currentTimeMillis()
            val fin = med.fechaFin()?.let {
                Calendar.getInstance().apply {
                    time = it
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }.timeInMillis
            }

            for (d in 1..diasMes) {
                val dia = (mes.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
                if (isoDow(dia) !in cfg.diasSemana) continue

                for (hhmm in cfg.horarios) {
                    val ms = momento(d, hhmm)
                    val k = clave(med.id, ms)
                    val rec = mapa[k]
                    if (rec == null) {
                        // Sin registro: solo cuenta si el tratamiento estaba vigente
                        if (ms < inicio) continue
                        if (fin != null && ms > fin) continue
                        if (med.terminado) continue
                    } else {
                        usados.add(k)
                    }
                    val estado = when (rec?.estado) {
                        "tomada" -> "tomada"
                        "omitida" -> "omitida"
                        else -> "pendiente"
                    }
                    porDia.getOrPut(d) { mutableListOf() }
                        .add(Dosis(med.id, med.nombre, ms, estado, rec?.registrada, med))
                }
            }
        }

        // Registros que no coinciden con ninguna toma programada (medicamento eliminado, horario editado...)
        mapa.forEach { (k, r) ->
            if (k in usados) return@forEach
            val d = Calendar.getInstance().apply { timeInMillis = r.programada }
                .get(Calendar.DAY_OF_MONTH)
            val estado = when (r.estado) {
                "tomada" -> "tomada"
                "omitida" -> "omitida"
                else -> "pendiente"
            }
            porDia.getOrPut(d) { mutableListOf() }.add(
                Dosis(r.medId, r.nombre, r.programada, estado, r.registrada,
                    meds.firstOrNull { it.id == r.medId })
            )
        }

        dibujarMes()
        dibujarDia()
    }

    private fun vencida(d: Dosis, ahora: Long) = d.estado != "tomada" && d.horaMs + GRACIA_MS <= ahora

    /** Color del día, o null si no hay nada que resaltar. */
    private fun colorDia(dia: Int): Int? {
        val lista = porDia[dia] ?: return null
        val ahora = System.currentTimeMillis()
        val tomadas = lista.count { it.estado == "tomada" }
        val faltantes = lista.count { vencida(it, ahora) }
        val pendientes = lista.size - tomadas - faltantes
        if (tomadas + faltantes == 0) return null
        return when {
            faltantes == 0 -> if (pendientes == 0) VERDE else null   // hoy, aún faltan tomas por llegar
            tomadas == 0 -> ROJO
            else -> NARANJA
        }
    }

    // ---------------------------------------------------------------- Dibujo

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun capitalizar(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun dibujarMes() {
        binding.tvMes.text = capitalizar(SimpleDateFormat("MMMM yyyy", localeEs).format(mes.time))
        binding.gridDias.removeAllViews()

        val offset = isoDow(mes) - 1
        val diasMes = mes.getActualMaximum(Calendar.DAY_OF_MONTH)
        binding.gridDias.rowCount = (offset + diasMes + 6) / 7

        val hoy = Calendar.getInstance()
        val ahoraDia = hoy.get(Calendar.DAY_OF_MONTH)
        val esMesActual = hoy.get(Calendar.YEAR) == mes.get(Calendar.YEAR) &&
                hoy.get(Calendar.MONTH) == mes.get(Calendar.MONTH)
        val mesFuturo = mes.timeInMillis > hoy.timeInMillis && !esMesActual

        for (d in 1..diasMes) {
            val pos = offset + d - 1
            val color = colorDia(d)
            val esHoy = esMesActual && d == ahoraDia
            val esFuturo = mesFuturo || (esMesActual && d > ahoraDia)

            val fondo = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color ?: Color.TRANSPARENT)
                when {
                    d == diaSel -> setStroke(dp(2), TEXTO)
                    esHoy -> setStroke(dp(1), AZUL)
                }
            }

            val celda = TextView(this).apply {
                text = d.toString()
                gravity = Gravity.CENTER
                textSize = 14f
                background = fondo
                setTextColor(
                    when {
                        color != null -> Color.WHITE
                        esHoy || esFuturo -> AZUL
                        else -> TEXTO
                    }
                )
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(pos / 7), GridLayout.spec(pos % 7, 1f)
                ).apply {
                    width = 0
                    height = dp(40)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener {
                    diaSel = d
                    dibujarMes()
                    dibujarDia()
                }
            }
            binding.gridDias.addView(celda)
        }
    }

    private fun dibujarDia() {
        val fecha = (mes.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, diaSel) }.time
        binding.tvDiaSeleccionado.text =
            capitalizar(SimpleDateFormat("EEEE d 'de' MMMM", localeEs).format(fecha))

        binding.llDosis.removeAllViews()
        val lista = porDia[diaSel]?.sortedBy { it.horaMs } ?: emptyList()
        binding.tvSinDosis.isVisible = lista.isEmpty()

        val ahora = System.currentTimeMillis()
        for (dosis in lista) {
            val fila = ItemTomaCalendarioBinding.inflate(layoutInflater, binding.llDosis, false)
            fila.tvNombre.text = dosis.nombre

            val hora = FrecuenciaConfig.hora12(
                SimpleDateFormat("HH:mm", Locale.US).format(Date(dosis.horaMs))
            )
            val resultado = when {
                dosis.estado == "tomada" -> {
                    val real = dosis.registrada?.let {
                        FrecuenciaConfig.hora12(SimpleDateFormat("HH:mm", Locale.US).format(it))
                    }
                    if (real != null) "Tomado $real" else "Tomado"
                }
                dosis.estado == "omitida" -> "Omitido"
                vencida(dosis, ahora) -> "No tomado"
                else -> "Pendiente"
            }
            fila.tvDetalle.text = "Programado $hora - $resultado"

            fila.ivEstado.setImageResource(
                when {
                    dosis.estado == "tomada" -> R.drawable.ic_estado_ok
                    dosis.estado == "omitida" || vencida(dosis, ahora) -> R.drawable.ic_estado_no
                    else -> R.drawable.ic_estado_pendiente
                }
            )

            // "Marcar tomado": solo si ya pasó la hora y el medicamento todavía existe
            val puedeMarcar = dosis.estado != "tomada" && dosis.horaMs <= ahora && dosis.med != null
            fila.btnMarcar.isVisible = puedeMarcar
            if (puedeMarcar) {
                fila.btnMarcar.setOnClickListener {
                    fila.btnMarcar.isEnabled = false
                    marcarTomado(dosis)
                }
            }
            binding.llDosis.addView(fila.root)
        }
    }

    // ---------------------------------------------------------------- Marcar tomado

    private fun descuento(m: Medicamento): Double =
        if (m.tipo == Medicamento.TIPO_PASTILLA) m.pastillasPorToma ?: 1.0
        else when (m.dosisUnidad) {
            "cucharadita" -> m.dosisCantidad * 5
            "cucharada" -> m.dosisCantidad * 15
            else -> m.dosisCantidad
        }

    private fun marcarTomado(dosis: Dosis) {
        val base = base() ?: return
        val med = dosis.med ?: return

        val registro = hashMapOf<String, Any>(
            "medicamentoId" to dosis.medId,
            "nombre" to dosis.nombre,
            "dosis" to med.dosisTexto(),
            "estado" to "tomada",
            "programadaEn" to Timestamp(Date(dosis.horaMs)),
            "registradaEn" to FieldValue.serverTimestamp(),
            "manual" to true
        )
        base.collection("tomas").add(registro)
            .addOnSuccessListener {
                val aDescontar = minOf(descuento(med), med.existenciaActual)
                if (aDescontar > 0) {
                    base.collection("medicamentos").document(dosis.medId)
                        .update("existenciaActual", FieldValue.increment(-aDescontar))
                }
                Toast.makeText(this, "Toma registrada", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "No se pudo registrar la toma", Toast.LENGTH_SHORT).show()
                dibujarDia()
            }
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
        binding.tvGreeting.text = if (nombre.isNullOrBlank()) "¡Hola!"
        else "¡Hola ${nombre.trim().split(" ").first()}!"
    }

    private fun irA(destino: Class<*>, limpiar: Boolean = false) {
        val intent = Intent(this, destino)
        if (limpiar) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun configurarNavegacion() {
        binding.bottomNavigation.selectedItemId = R.id.nav_calendario
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_calendario -> true
                R.id.nav_inicio -> { irA(HomeActivity::class.java, true); true }
                R.id.nav_medicamentos -> { irA(MedicamentosActivity::class.java); true }
                R.id.nav_reportes -> { irA(ReportesActivity::class.java); true }
                R.id.nav_config -> { irA(ConfigActivity::class.java); true }
                else -> {
                    Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }
}
