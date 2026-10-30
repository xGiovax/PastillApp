package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import udb.edu.sv.dsm.pastillapp.databinding.ActivityReportesBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemReporteMedBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reportes de adherencia (Semana / Mes / Año, del periodo en curso).
 * Usa la misma lógica del Calendario: lo programado según la frecuencia contra lo registrado
 * en usuarios/{uid}/tomas. Una toma vencida (pasados 30 min) sin respuesta cuenta como "omitida".
 */
class ReportesActivity : AppCompatActivity() {

    private enum class Periodo(val etiqueta: String) { SEMANA("Semana"), MES("Mes"), ANIO("Año") }

    private data class Registro(
        val medId: String, val nombre: String, val programada: Long,
        val estado: String, val registrada: Long?, val manual: Boolean
    )

    private data class Dosis(
        val medId: String, val nombre: String, val horaMs: Long,
        val estado: String, val registrada: Long?, val manual: Boolean
    )

    companion object {
        private const val GRACIA_MS = 30 * 60 * 1000L
    }

    private lateinit var binding: ActivityReportesBinding
    private val db = FirebaseFirestore.getInstance()
    private var escuchaMeds: ListenerRegistration? = null
    private var escuchaTomas: ListenerRegistration? = null

    private var meds: List<Medicamento> = emptyList()
    private var registros: List<Registro> = emptyList()
    private var periodo = Periodo.SEMANA
    private var nombreUsuario = ""
    private var resumen: ReporteResumen? = null

    private val localeEs = Locale("es", "SV")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDateTime.text =
            SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault()).format(Date())
        cargarNombre()

        binding.btnSemana.setOnClickListener { elegir(Periodo.SEMANA) }
        binding.btnMes.setOnClickListener { elegir(Periodo.MES) }
        binding.btnAnio.setOnClickListener { elegir(Periodo.ANIO) }
        binding.cardExportar.setOnClickListener { exportarPdf() }

        marcarBotones()
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

    // ---------------------------------------------------------------- Periodo

    private fun elegir(p: Periodo) {
        if (p == periodo) return
        periodo = p
        registros = emptyList()
        marcarBotones()
        escucharTomas()
        recalcular()
    }

    private fun marcarBotones() {
        binding.btnSemana.isSelected = periodo == Periodo.SEMANA
        binding.btnMes.isSelected = periodo == Periodo.MES
        binding.btnAnio.isSelected = periodo == Periodo.ANIO
    }

    private fun inicioDia(c: Calendar): Calendar = c.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** [inicio, fin) del periodo en curso: semana lun-dom, mes calendario o año calendario. */
    private fun rango(): Pair<Calendar, Calendar> {
        val ini = inicioDia(Calendar.getInstance())
        val fin: Calendar
        when (periodo) {
            Periodo.SEMANA -> {
                val dow = isoDow(ini)
                ini.add(Calendar.DAY_OF_MONTH, -(dow - 1))
                fin = (ini.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
            }
            Periodo.MES -> {
                ini.set(Calendar.DAY_OF_MONTH, 1)
                fin = (ini.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            }
            Periodo.ANIO -> {
                ini.set(Calendar.DAY_OF_YEAR, 1)
                fin = (ini.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            }
        }
        return ini to fin
    }

    private fun isoDow(c: Calendar): Int {
        val d = c.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
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

    private fun escucharTomas() {
        val base = base() ?: return
        escuchaTomas?.remove()
        val (ini, fin) = rango()
        escuchaTomas = base.collection("tomas")
            .whereGreaterThanOrEqualTo("programadaEn", Timestamp(ini.time))
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
                        doc.getTimestamp("registradaEn")?.toDate()?.time,
                        doc.getBoolean("manual") == true
                    )
                }
                recalcular()
            }
    }

    // ---------------------------------------------------------------- Cálculo

    private fun clave(medId: String, ms: Long) = "$medId|${ms / 60_000L}"

    private fun prioridad(estado: String) = when (estado) {
        "tomada" -> 3
        "omitida" -> 2
        else -> 1
    }

    private fun momento(dia: Calendar, hhmm: String): Long =
        (dia.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hhmm.substringBefore(":").toInt())
            set(Calendar.MINUTE, hhmm.substringAfter(":").toInt())
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun construirDosis(ini: Calendar, fin: Calendar): List<Dosis> {
        val mapa = HashMap<String, Registro>()
        registros.forEach { r ->
            val k = clave(r.medId, r.programada)
            val previo = mapa[k]
            if (previo == null || prioridad(r.estado) > prioridad(previo.estado)) mapa[k] = r
        }

        val usados = HashSet<String>()
        val lista = mutableListOf<Dosis>()

        for (med in meds) {
            val cfg = med.frecuencia?.let { FrecuenciaConfig.fromMap(it) } ?: continue
            val inicioMed = med.creadoEn?.time ?: System.currentTimeMillis()
            val finMed = med.fechaFin()?.let {
                Calendar.getInstance().apply {
                    time = it
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }.timeInMillis
            }

            val dia = ini.clone() as Calendar
            while (dia.before(fin)) {
                if (isoDow(dia) in cfg.diasSemana) {
                    for (hhmm in cfg.horarios) {
                        val ms = momento(dia, hhmm)
                        val k = clave(med.id, ms)
                        val rec = mapa[k]
                        if (rec == null) {
                            if (ms < inicioMed) continue
                            if (finMed != null && ms > finMed) continue
                            if (med.terminado) continue
                        } else usados.add(k)
                        lista.add(
                            Dosis(
                                med.id, med.nombre, ms,
                                when (rec?.estado) {
                                    "tomada" -> "tomada"
                                    "omitida" -> "omitida"
                                    else -> "pendiente"
                                },
                                rec?.registrada, rec?.manual == true
                            )
                        )
                    }
                }
                dia.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // Registros sin toma programada asociada (medicamento eliminado u horario editado)
        mapa.forEach { (k, r) ->
            if (k in usados) return@forEach
            lista.add(
                Dosis(
                    r.medId, r.nombre, r.programada,
                    when (r.estado) {
                        "tomada" -> "tomada"
                        "omitida" -> "omitida"
                        else -> "pendiente"
                    },
                    r.registrada, r.manual
                )
            )
        }
        return lista
    }

    private fun recalcular() {
        val (ini, fin) = rango()
        val ahora = System.currentTimeMillis()
        val dosis = construirDosis(ini, fin)

        // Toma "contada": ya respondida, o vencida (pasaron 30 min) sin respuesta.
        // Las futuras o recién llegadas (pendientes) no afectan los porcentajes.
        fun esTomada(d: Dosis) = d.estado == "tomada"
        fun esOmitida(d: Dosis) =
            d.estado == "omitida" || (d.estado == "pendiente" && d.horaMs + GRACIA_MS <= ahora)

        val tomadas = dosis.filter { esTomada(it) }
        val omitidas = dosis.filter { esOmitida(it) }
        val total = tomadas.size + omitidas.size
        val adherencia = if (total == 0) null else Math.round(tomadas.size * 100f / total)

        // Retraso: solo tomas confirmadas desde la alarma (las marcadas a mano no miden retraso real)
        val retrasos = tomadas
            .filter { !it.manual && it.registrada != null }
            .map { ((it.registrada!! - it.horaMs) / 60_000L).coerceAtLeast(0L) }
        val retraso = if (retrasos.isEmpty()) null else Math.round(retrasos.average()).toInt()

        val porMed = dosis.groupBy { it.medId }.mapNotNull { (_, ds) ->
            val t = ds.count { esTomada(it) }
            val o = ds.count { esOmitida(it) }
            if (t + o == 0) null else ds.first().nombre to Math.round(t * 100f / (t + o))
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

        binding.tvAdherencia.text = adherencia?.let { "$it%" } ?: "--"
        binding.tvConfirmadas.text = tomadas.size.toString()
        binding.tvOmitidas.text = omitidas.size.toString()
        binding.tvRetraso.text = retraso?.let { "$it min" } ?: "--"

        val f = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val ultimo = (fin.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        val rangoTxt = "${f.format(ini.time)} - ${f.format(ultimo.time)}"
        binding.tvRango.text = rangoTxt
        binding.tvExportarSub.text = "Resumen de ${periodo.etiqueta.lowercase()} ($rangoTxt)"

        binding.llMeds.removeAllViews()
        binding.tvSinDatos.isVisible = porMed.isEmpty()
        for ((nombre, pct) in porMed) {
            val fila = ItemReporteMedBinding.inflate(layoutInflater, binding.llMeds, false)
            fila.tvNombre.text = nombre
            fila.tvPorcentaje.text = "$pct%"
            fila.barra.setProgressCompat(pct, false)
            binding.llMeds.addView(fila.root)
        }

        resumen = ReporteResumen(
            usuario = nombreUsuario.ifBlank { "Usuario" },
            periodo = periodo.etiqueta,
            rango = rangoTxt,
            adherencia = adherencia,
            confirmadas = tomadas.size,
            omitidas = omitidas.size,
            retrasoMin = retraso,
            porMedicamento = porMed,
            generado = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date())
        )
    }

    // ---------------------------------------------------------------- PDF

    private fun exportarPdf() {
        val datos = resumen ?: return
        try {
            val archivo = ReportePdf.generar(this, datos)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Reporte PastillApp - ${datos.periodo}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir o guardar reporte"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Saludo y navegación

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
        nombreUsuario = nombre?.trim().orEmpty()
        binding.tvGreeting.text = if (nombreUsuario.isEmpty()) "¡Hola!"
        else "¡Hola ${nombreUsuario.split(" ").first()}!"
        resumen = resumen?.copy(usuario = nombreUsuario.ifBlank { "Usuario" })
    }

    private fun irA(destino: Class<*>, limpiar: Boolean = false) {
        val intent = Intent(this, destino)
        if (limpiar) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun configurarNavegacion() {
        binding.bottomNavigation.selectedItemId = R.id.nav_reportes
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_reportes -> true
                R.id.nav_inicio -> { irA(HomeActivity::class.java, true); true }
                R.id.nav_calendario -> { irA(CalendarioActivity::class.java); true }
                R.id.nav_medicamentos -> { irA(MedicamentosActivity::class.java); true }
                R.id.nav_config -> { irA(ConfigActivity::class.java); true }
                else -> false
            }
        }
    }
}
