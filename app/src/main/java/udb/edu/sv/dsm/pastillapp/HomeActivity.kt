package udb.edu.sv.dsm.pastillapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import udb.edu.sv.dsm.pastillapp.databinding.ActivityHomeBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemPocaCantidadBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemTomaHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private data class Registro(val medId: String, val nombre: String, val programada: Long, val estado: String)

    private data class Dosis(
        val medId: String, val nombre: String, val horaMs: Long,
        val estado: String,            // tomada | omitida | pendiente
        val med: Medicamento?
    )

    companion object {
        private const val GRACIA_MS = 30 * 60 * 1000L     // igual que Calendario y Reportes
        private const val MAX_POCAS = 3
    }

    private var meds: List<Medicamento> = emptyList()
    private var registrosHoy: List<Registro> = emptyList()
    private var escuchaMeds: ListenerRegistration? = null
    private var escuchaTomas: ListenerRegistration? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            configurarFechaHora()
            refrescar()
            handler.postDelayed(this, 60_000L)
        }
    }

    // Permiso de notificaciones (Android 13+)
    private var pidioNotificaciones = false
    private val pedirNotificaciones = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusBarInsets.top, 0, 0)
            insets
        }

        obtenerNombreUsuario()
        configurarFechaHora()
        configurarBotones()
        configurarNavegacion()
    }

    override fun onStart() {
        super.onStart()
        escucharMedicamentos()
        escucharTomasDeHoy()
        handler.postDelayed(tick, 60_000L)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        escuchaMeds?.remove(); escuchaMeds = null
        escuchaTomas?.remove(); escuchaTomas = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        configurarFechaHora()
        verificarPermisosAlarma()
        sincronizarAlarmas()
    }

    // ---------------------------------------------------------------- Datos en vivo

    private fun base() = auth.currentUser?.uid?.let { db.collection("usuarios").document(it) }

    private fun inicioDeHoy(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun escucharMedicamentos() {
        val base = base() ?: return
        escuchaMeds?.remove()
        escuchaMeds = base.collection("medicamentos").addSnapshotListener { snap, error ->
            if (error != null || snap == null) return@addSnapshotListener
            meds = snap.toObjects(Medicamento::class.java)
            refrescar()
        }
    }

    /** Solo las tomas de hoy (rango sobre un único campo: no necesita índice). */
    private fun escucharTomasDeHoy() {
        val base = base() ?: return
        escuchaTomas?.remove()
        val ini = inicioDeHoy()
        val fin = (ini.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        escuchaTomas = base.collection("tomas")
            .whereGreaterThanOrEqualTo("programadaEn", Timestamp(ini.time))
            .whereLessThan("programadaEn", Timestamp(fin.time))
            .addSnapshotListener { snap, error ->
                if (error != null || snap == null) return@addSnapshotListener
                registrosHoy = snap.documents.mapNotNull { doc ->
                    val medId = doc.getString("medicamentoId") ?: return@mapNotNull null
                    val programada = doc.getTimestamp("programadaEn")?.toDate()?.time
                        ?: return@mapNotNull null
                    Registro(medId, doc.getString("nombre") ?: "Medicamento", programada, doc.getString("estado") ?: "omitida")
                }
                refrescar()
            }
    }

    // ---------------------------------------------------------------- Cálculo

    private fun clave(medId: String, ms: Long) = "$medId|${ms / 60_000L}"

    private fun prioridad(estado: String) = when (estado) {
        "tomada" -> 3
        "omitida" -> 2
        else -> 1                      // pospuesta
    }

    private fun isoDow(c: Calendar): Int {
        val d = c.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1       // 1 = Lunes ... 7 = Domingo
    }

    /** Tomas programadas de un día concreto, con su estado (solo hoy tiene registros). */
    private fun dosisDelDia(dia: Calendar, mapa: Map<String, Registro>): List<Dosis> {
        val lista = mutableListOf<Dosis>()
        for (med in meds) {
            val cfg = med.frecuencia?.let { FrecuenciaConfig.fromMap(it) } ?: continue
            if (isoDow(dia) !in cfg.diasSemana) continue
            val inicioMed = med.creadoEn?.time ?: System.currentTimeMillis()
            val finMed = med.fechaFin()?.let {
                Calendar.getInstance().apply {
                    time = it
                    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }.timeInMillis
            }
            for (hhmm in cfg.horarios) {
                val ms = (dia.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hhmm.substringBefore(":").toInt())
                    set(Calendar.MINUTE, hhmm.substringAfter(":").toInt())
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val rec = mapa[clave(med.id, ms)]
                if (rec == null) {
                    if (ms < inicioMed) continue
                    if (finMed != null && ms > finMed) continue
                    if (med.terminado) continue
                }
                lista.add(
                    Dosis(
                        med.id, med.nombre, ms,
                        when (rec?.estado) {
                            "tomada" -> "tomada"
                            "omitida" -> "omitida"
                            else -> "pendiente"
                        },
                        med
                    )
                )
            }
        }
        return lista
    }

    private fun vencida(d: Dosis, ahora: Long) = d.estado != "tomada" && d.horaMs + GRACIA_MS <= ahora

    private fun refrescar() {
        val ahora = System.currentTimeMillis()
        val hoy = inicioDeHoy()

        val mapa = HashMap<String, Registro>()
        registrosHoy.forEach { r ->
            val k = clave(r.medId, r.programada)
            val previo = mapa[k]
            if (previo == null || prioridad(r.estado) > prioridad(previo.estado)) mapa[k] = r
        }

        val programadas = dosisDelDia(hoy, mapa)
        // Tomas registradas hoy que no coinciden con el horario (alarma pospuesta, horario editado,
        // medicamento "según necesidad"...): también deben verse en la tabla.
        val usadas = programadas.map { clave(it.medId, it.horaMs) }.toHashSet()
        val extras = mapa.filter { (k, _) -> k !in usadas }.values.map { r ->
            Dosis(
                r.medId, r.nombre, r.programada,
                if (r.estado == "tomada") "tomada" else if (r.estado == "omitida") "omitida" else "pendiente",
                meds.firstOrNull { it.id == r.medId }
            )
        }
        val deHoy = (programadas + extras).sortedBy { it.horaMs }

        // Próxima toma: la primera sin responder (vigente o dentro de la gracia), hasta 8 días adelante
        var proxima: Dosis? = null
        for (offset in 0..7) {
            val dia = (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            val candidatas = (if (offset == 0) deHoy else dosisDelDia(dia, emptyMap()))
                .filter { it.estado == "pendiente" && it.horaMs + GRACIA_MS > ahora }
            if (candidatas.isNotEmpty()) {
                proxima = candidatas.minByOrNull { it.horaMs }
                break
            }
        }

        dibujarProxima(proxima, ahora)
        dibujarTomasDeHoy(deHoy, ahora)
        dibujarPocas()
    }

    // ---------------------------------------------------------------- Dibujo

    private val localeEs = Locale("es", "SV")

    private fun frecuenciaCorta(med: Medicamento): String {
        val cfg = med.frecuencia?.let { FrecuenciaConfig.fromMap(it) } ?: return "-"
        return when (cfg.modo) {
            FrecuenciaConfig.MODO_CADA_HORAS -> "C/${cfg.repetirCadaHoras} horas"
            FrecuenciaConfig.MODO_FIJAS ->
                if (cfg.vecesAlDia == 1) "1 vez al día" else "${cfg.vecesAlDia} veces al día"
            else -> "Según necesidad"
        }
    }

    private fun etiquetaHora(ms: Long): String {
        val hora = FrecuenciaConfig.hora12(SimpleDateFormat("HH:mm", Locale.US).format(Date(ms)))
        val hoy = inicioDeHoy()
        val diaToma = Calendar.getInstance().apply { timeInMillis = ms }.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dias = Math.round((diaToma.timeInMillis - hoy.timeInMillis) / 86_400_000.0).toInt()
        return when (dias) {
            0 -> hora
            1 -> "Mañana $hora"
            else -> "${SimpleDateFormat("EEEE", localeEs).format(Date(ms))
                .replaceFirstChar { it.uppercase() }} $hora"
        }
    }

    private fun dibujarProxima(d: Dosis?, ahora: Long) {
        val med = d?.med
        if (d == null || med == null) {
            binding.ivMedPhoto.setImageResource(android.R.drawable.ic_menu_camera)
            binding.tvMedName.text = "Sin tomas próximas"
            binding.tvMedDose.text = "Agrega un medicamento para empezar"
            binding.tvMedFreq.isVisible = false
            binding.tvMedTime.isVisible = false
            return
        }
        val foto = try { med.fotoComoBitmap() } catch (e: Exception) { null }
        if (foto != null) binding.ivMedPhoto.setImageBitmap(foto)
        else binding.ivMedPhoto.setImageResource(android.R.drawable.ic_menu_camera)

        binding.tvMedName.text = "Nombre: ${med.nombre}"
        binding.tvMedDose.text = "Dosis: ${med.dosisTexto()}"
        binding.tvMedFreq.text = "Frecuencia: ${frecuenciaCorta(med)}"
        binding.tvMedFreq.isVisible = true
        binding.tvMedTime.text = "Hora de dosis: ${etiquetaHora(d.horaMs)}" +
                if (d.horaMs <= ahora) " (ahora)" else ""
        binding.tvMedTime.isVisible = true
    }

    private fun dibujarTomasDeHoy(lista: List<Dosis>, ahora: Long) {
        binding.llTomasHoy.removeAllViews()
        binding.tvSinTomas.isVisible = lista.isEmpty()
        binding.llEncabezadoTomas.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE

        for (d in lista) {
            val fila = ItemTomaHomeBinding.inflate(layoutInflater, binding.llTomasHoy, false)
            fila.tvNombre.text = d.nombre
            fila.tvHora.text = FrecuenciaConfig.hora12(
                SimpleDateFormat("HH:mm", Locale.US).format(Date(d.horaMs))
            )
            val omitida = d.estado == "omitida" || (d.estado == "pendiente" && vencida(d, ahora))
            fila.ivTomado.visibility = if (d.estado == "tomada") View.VISIBLE else View.INVISIBLE
            fila.ivOmitido.visibility = if (omitida) View.VISIBLE else View.INVISIBLE
            fila.ivPendiente.visibility =
                if (d.estado == "pendiente" && !omitida) View.VISIBLE else View.INVISIBLE
            binding.llTomasHoy.addView(fila.root)
        }
    }

    private fun dibujarPocas() {
        val ll = binding.llPocas
        ll.removeAllViews()
        val umbral = Ajustes.avisoPocasPastillas(this)
        val pocas = if (umbral <= 0) emptyList()
        else meds.filter { !it.terminado && it.existenciaActual <= umbral }
            .sortedBy { it.existenciaActual }

        binding.cardPocas.setOnClickListener {
            startActivity(Intent(this, MedicamentosActivity::class.java))
        }
        // La tarjeta de "Agregar" copia la altura de la lista (mínimo 110dp)
        binding.cardPocas.post {
            val minimo = (110 * resources.displayMetrics.density).toInt()
            val alto = maxOf(binding.cardPocas.height, minimo)
            val lp = binding.cardAgregar.layoutParams
            if (lp.height != alto) { lp.height = alto; binding.cardAgregar.layoutParams = lp }
        }

        if (pocas.isEmpty()) {
            val t = TextView(this).apply {
                text = "Todo en orden\nNo hay medicinas por agotarse"
                gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.parseColor("#1C1B1F"))
                textSize = 12f
            }
            ll.addView(t)
            return
        }
        for (med in pocas.take(MAX_POCAS)) {
            val fila = ItemPocaCantidadBinding.inflate(layoutInflater, ll, false)
            fila.tvNombre.text = med.nombre
            fila.tvRestante.text = "${med.textoRestante()} restantes"
            ll.addView(fila.root)
        }
        if (pocas.size > MAX_POCAS) {
            ll.addView(TextView(this).apply {
                text = "+${pocas.size - MAX_POCAS} más"
                setTextColor(android.graphics.Color.parseColor("#2D4D3A"))
                textSize = 12f
                setPadding(0, 4, 0, 0)
            })
        }
    }

    /** Pide notificaciones y explica los permisos para que la alarma suene con el teléfono bloqueado. */
    private fun verificarPermisosAlarma() {
        val faltaNotificaciones = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (faltaNotificaciones && !pidioNotificaciones) {
            pidioNotificaciones = true
            pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!faltaNotificaciones) {
            PermisosAlarma.dialogoSiFalta(this)
        }
    }

    /** Lee los medicamentos del usuario y (re)programa todas las alarmas. */
    private fun sincronizarAlarmas() {
        val uid = auth.currentUser?.uid ?: return
        db.collection("usuarios").document(uid).collection("medicamentos").get()
            .addOnSuccessListener { snap ->
                AlarmScheduler.sincronizar(this, snap.toObjects(Medicamento::class.java))
            }
    }

    private fun obtenerNombreUsuario() {
        val user = auth.currentUser ?: return

        // 1. Si el usuario tiene displayName (ej: inició sesión con Google)
        val googleName = user.displayName
        if (!googleName.isNullOrBlank()) {
            actualizarSaludo(googleName)
            return
        }

        // 2. Si es correo/contraseña, buscar primero en Firestore
        db.collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { document ->
                val firestoreName = if (document.exists()) {
                    document.getString("nombre")
                        ?: document.getString("name")
                        ?: document.getString("username")
                } else null

                if (!firestoreName.isNullOrBlank()) {
                    actualizarSaludo(firestoreName)
                } else {
                    // Fallback 1: Si no se encuentra en Firestore, toma el nombre antes del '@' (ej: alberto@gmail.com -> Alberto)
                    val emailName = user.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
                    actualizarSaludo(emailName)
                }
            }
            .addOnFailureListener {
                // Fallback 2: Si la consulta a Firestore falla (sin conexión, etc.), usa el correo
                val emailName = user.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
                actualizarSaludo(emailName)
            }
    }

    private fun actualizarSaludo(nombreCompleto: String?) {
        if (nombreCompleto.isNullOrEmpty()) {
            binding.tvGreeting.text = "¡Hola!"
            return
        }
        val primerNombre = nombreCompleto.trim().split(" ").firstOrNull() ?: nombreCompleto
        binding.tvGreeting.text = "¡Hola $primerNombre!"
    }

    private fun configurarFechaHora() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault())
        binding.tvDateTime.text = dateFormat.format(Date())
    }

    private fun configurarBotones() {
        binding.btnAddMedicine.setOnClickListener {
            startActivity(Intent(this, AddMedicamentoActivity::class.java))
        }

        // Evento al presionar el ícono discreto de cerrar sesión
        binding.btnLogout.setOnClickListener {
            cerrarSesion()
        }
    }

    private fun cerrarSesion() {
        AlarmScheduler.limpiarTodo(this)   // nada de este usuario debe seguir sonando
        auth.signOut()

        val intent = Intent(this, WelcomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun configurarNavegacion() {
        binding.bottomNavigation.selectedItemId = R.id.nav_inicio

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_reportes -> {
                    startActivity(Intent(this, ReportesActivity::class.java))
                    false
                }
                R.id.nav_calendario -> {
                    startActivity(Intent(this, CalendarioActivity::class.java))
                    false
                }
                R.id.nav_inicio -> true
                R.id.nav_medicamentos -> {
                    startActivity(Intent(this, MedicamentosActivity::class.java))
                    false
                }
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    false
                }
                else -> false
            }
        }
    }
}