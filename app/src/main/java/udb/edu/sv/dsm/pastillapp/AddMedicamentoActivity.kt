package udb.edu.sv.dsm.pastillapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import udb.edu.sv.dsm.pastillapp.databinding.ActivityAddMedicamentoBinding
import com.google.android.gms.tasks.Task
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import android.content.Intent

class AddMedicamentoActivity : AppCompatActivity() {

    companion object {
        /** Si se envía este extra, la pantalla EDITA ese medicamento en vez de crear uno nuevo. */
        const val EXTRA_MED_ID = "extra_med_id"
    }

    private lateinit var binding: ActivityAddMedicamentoBinding

    // Estado del formulario
    private var tipo = Medicamento.TIPO_PASTILLA
    private var pastillasPorToma = 1.0
    private var fotoBitmap: Bitmap? = null
    private var fondoEliminado = false
    private var escaneoId = 0   // para ignorar resultados de una foto anterior

    // Modo edición
    private var medEditando: Medicamento? = null
    private var fotoModificada = false     // true si tomó una foto nueva o quitó el fondo

    private var frecuenciaConfig: FrecuenciaConfig? = null

    private val abrirFrecuencia = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val config = resultado.data
                ?.getSerializableExtra(FrecuenciaActivity.EXTRA_FRECUENCIA) as? FrecuenciaConfig
            if (config != null) {
                frecuenciaConfig = config
                binding.tvFrecuenciaEstado.text = config.resumen()
            }
        }
    }

    private val unidadesPastilla = listOf("mg", "g", "mcg")
    private val unidadesLiquido = listOf("ml", "cucharadita", "cucharada")

    // Cámara: guarda la foto en un archivo temporal (cache) vía FileProvider
    private val tomarFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cargarFotoTomada()
    }
    private var uriFotoTemporal: Uri? = null

    // ML Kit Subject Segmentation (el modelo se descarga una vez vía Google Play Services)
    private val segmentador by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddMedicamentoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnCancelar.setOnClickListener { finish() }

        configurarTipo()
        configurarPorToma()
        configurarFoto()

        binding.cardFrecuencia.setOnClickListener {
            val intent = Intent(this, FrecuenciaActivity::class.java)
            frecuenciaConfig?.let { intent.putExtra(FrecuenciaActivity.EXTRA_FRECUENCIA, it) }
            abrirFrecuencia.launch(intent)
        }
        binding.btnGuardar.setOnClickListener { guardar() }

        // ¿Viene de "Editar" en la pantalla de medicamentos?
        intent.getStringExtra(EXTRA_MED_ID)?.let { cargarParaEditar(it) }
    }

    // ---------------------------------------------------------------- Tipo

    private fun configurarTipo() {
        binding.toggleTipo.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            tipo = if (id == R.id.btnLiquido) Medicamento.TIPO_LIQUIDO else Medicamento.TIPO_PASTILLA
            actualizarFormularioPorTipo()
        }
        actualizarFormularioPorTipo()
    }

    private fun actualizarFormularioPorTipo() {
        val esPastilla = tipo == Medicamento.TIPO_PASTILLA
        val unidades = if (esPastilla) unidadesPastilla else unidadesLiquido

        binding.spUnidad.adapter = ArrayAdapter(this, R.layout.item_spinner, unidades).apply {
            setDropDownViewResource(R.layout.item_spinner)
        }
        binding.tvLabelDosis.text = if (esPastilla) "Dosis" else "Dosis por toma"
        binding.togglePorToma.visibility = if (esPastilla) View.VISIBLE else View.GONE
        binding.tvLabelEnvases.text = if (esPastilla) "Blísteres" else "Frascos"
        binding.tvLabelPorEnvase.text = if (esPastilla) "Pastillas c/u" else "ml c/u"
        binding.etPorEnvase.hint = if (esPastilla) "10" else "120"
    }

    private fun configurarPorToma() {
        binding.togglePorToma.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            pastillasPorToma = when (id) {
                R.id.btnMedia -> 0.5
                R.id.btnDos -> 2.0
                else -> 1.0
            }
        }
    }

    // ---------------------------------------------------------------- Foto

    private fun configurarFoto() {
        binding.cardFoto.setOnClickListener { lanzarCamara() }
        binding.btnVolverTomar.setOnClickListener { lanzarCamara() }
        binding.btnEliminarFondo.setOnClickListener { eliminarFondo() }
    }

    private fun lanzarCamara() {
        val carpeta = File(cacheDir, "fotos").apply { mkdirs() }
        val archivo = File.createTempFile("med_", ".jpg", carpeta)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
        uriFotoTemporal = uri
        tomarFoto.launch(uri)
    }

    private fun cargarFotoTomada() {
        val uri = uriFotoTemporal ?: return
        val bmp = decodificar(uri, 1024)
        if (bmp == null) {
            Toast.makeText(this, "No se pudo leer la foto", Toast.LENGTH_SHORT).show()
            return
        }
        fotoBitmap = bmp
        fondoEliminado = false
        fotoModificada = true
        mostrarFoto()
        escanearTexto(uri, bmp)      // lee la caja en segundo plano
    }

    private fun mostrarFoto() {
        val bmp = fotoBitmap
        binding.layoutFotoHint.visibility = if (bmp == null) View.VISIBLE else View.GONE
        binding.ivFoto.visibility = if (bmp == null) View.GONE else View.VISIBLE
        binding.ivFoto.setImageBitmap(bmp)
        binding.btnVolverTomar.isEnabled = bmp != null
        binding.btnEliminarFondo.isEnabled = bmp != null && !fondoEliminado
    }

    private fun eliminarFondo() {
        val bmp = fotoBitmap ?: return
        if (fondoEliminado) return

        binding.progressFoto.visibility = View.VISIBLE
        binding.btnEliminarFondo.isEnabled = false

        segmentador.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { resultado ->
                binding.progressFoto.visibility = View.GONE
                val recorte = resultado.foregroundBitmap
                if (recorte == null) {
                    Toast.makeText(this, "No se detectó ningún objeto", Toast.LENGTH_SHORT).show()
                    mostrarFoto()
                } else {
                    fotoBitmap = recortarTransparencia(recorte)
                    fondoEliminado = true
                    fotoModificada = true
                    mostrarFoto()
                }
            }
            .addOnFailureListener {
                binding.progressFoto.visibility = View.GONE
                Toast.makeText(
                    this,
                    "No se pudo eliminar el fondo. Revisa tu conexión (el modelo se descarga la primera vez).",
                    Toast.LENGTH_LONG
                ).show()
                mostrarFoto()
            }
    }

    // ---------------------------------------------------------------- Escaneo de la caja (OCR)

    /** Lee el texto de la foto ORIGINAL (antes de quitar el fondo) y autocompleta. */
    private fun escanearTexto(uri: Uri, respaldo: Bitmap) {
        val id = ++escaneoId
        val paraOcr = decodificar(uri, 1600) ?: respaldo      // más resolución = mejor lectura
        EscanerMedicamento.escanear(paraOcr) { resultado ->
            if (id != escaneoId || isFinishing || isDestroyed) return@escanear
            if (resultado != null && resultado.hayAlgo) aplicarSugerencias(resultado)
        }
    }

    private fun formatearNumero(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    /** Rellena SOLO los campos vacíos y ofrece "Deshacer". */
    private fun aplicarSugerencias(r: EscanerMedicamento.Resultado) {
        val deshacer = mutableListOf<() -> Unit>()

        val formularioSinTocar = binding.etNombre.text.isNullOrBlank() &&
                binding.etDosis.text.isNullOrBlank() &&
                binding.etEnvases.text.isNullOrBlank() &&
                binding.etPorEnvase.text.isNullOrBlank()

        // 1) Tipo: solo si el formulario está sin tocar (cambiarlo reinicia unidades y etiquetas)
        val tipoAnterior = tipo
        if (formularioSinTocar && r.esLiquido != null) {
            val nuevo = if (r.esLiquido) Medicamento.TIPO_LIQUIDO else Medicamento.TIPO_PASTILLA
            if (nuevo != tipo) {
                binding.toggleTipo.check(if (r.esLiquido) R.id.btnLiquido else R.id.btnPastilla)
                deshacer.add {
                    binding.toggleTipo.check(
                        if (tipoAnterior == Medicamento.TIPO_LIQUIDO) R.id.btnLiquido else R.id.btnPastilla
                    )
                }
            }
        }
        val esPastilla = tipo == Medicamento.TIPO_PASTILLA

        // 2) Nombre
        if (r.nombre != null && binding.etNombre.text.isNullOrBlank()) {
            binding.etNombre.setText(r.nombre)
            deshacer.add { binding.etNombre.setText("") }
        }

        // 3) Dosis (concentración): solo pastillas. En líquidos "250 mg / 5 ml" confundiría.
        if (esPastilla && r.concentracion != null && r.unidad != null &&
            binding.etDosis.text.isNullOrBlank()
        ) {
            val posicion = unidadesPastilla.indexOf(r.unidad)
            if (posicion >= 0) {
                val posicionAnterior = binding.spUnidad.selectedItemPosition
                binding.etDosis.setText(formatearNumero(r.concentracion))
                binding.spUnidad.setSelection(posicion)
                deshacer.add {
                    binding.etDosis.setText("")
                    binding.spUnidad.setSelection(posicionAnterior)
                }
            }
        }

        // 4) Pastillas por blíster/caja
        if (esPastilla && r.porEnvase != null && binding.etPorEnvase.text.isNullOrBlank()) {
            binding.etPorEnvase.setText(r.porEnvase.toString())
            deshacer.add { binding.etPorEnvase.setText("") }
        }

        if (deshacer.isEmpty()) return

        Snackbar.make(binding.root, "Datos detectados, verifica antes de guardar", Snackbar.LENGTH_LONG)
            .setDuration(8000)
            .setAction("Deshacer") { deshacer.asReversed().forEach { it() } }
            .show()
    }

    /** Decodifica la foto reduciendo tamaño y corrigiendo la rotación EXIF. */
    private fun decodificar(uri: Uri, maxLado: Int): Bitmap? {
        val bordes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bordes) }
        var muestra = 1
        while (max(bordes.outWidth, bordes.outHeight) / muestra > maxLado * 2) muestra *= 2

        val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opciones)
        } ?: return null

        val grados = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        bmp = escalar(bmp, maxLado)
        if (grados != 0f) {
            bmp = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(grados) }, true
            )
        }
        return bmp
    }

    private fun escalar(bmp: Bitmap, maxLado: Int): Bitmap {
        val lado = max(bmp.width, bmp.height)
        if (lado <= maxLado) return bmp
        val factor = maxLado.toFloat() / lado
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * factor).toInt(), (bmp.height * factor).toInt(), true
        )
    }

    /** Quita los márgenes transparentes para que la pastilla ocupe todo el espacio. */
    private fun recortarTransparencia(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((px[y * w + x] ushr 24) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Miniatura de máx. 300 px en Base64. PNG si no tiene fondo (transparencia), JPEG si no. */
    private fun fotoABase64(): String? {
        val bmp = fotoBitmap ?: return null
        val miniatura = escalar(bmp, 300)
        val salida = ByteArrayOutputStream()
        if (fondoEliminado) miniatura.compress(Bitmap.CompressFormat.PNG, 100, salida)
        else miniatura.compress(Bitmap.CompressFormat.JPEG, 80, salida)
        return Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- Guardar

    private fun guardar() {
        if (intent.hasExtra(EXTRA_MED_ID) && medEditando == null) {
            Toast.makeText(this, "Cargando medicamento…", Toast.LENGTH_SHORT).show()
            return
        }
        val nombre = binding.etNombre.text.toString().trim()
        val dosis = binding.etDosis.text.toString().toDoubleOrNull()
        val envases = binding.etEnvases.text.toString().toIntOrNull()
        val porEnvase = binding.etPorEnvase.text.toString().toIntOrNull()

        if (nombre.isEmpty()) { binding.etNombre.error = "Escribe el nombre"; return }
        if (dosis == null || dosis <= 0) { binding.etDosis.error = "Ingresa la dosis"; return }
        if (envases == null || envases < 0) { binding.etEnvases.error = "Requerido"; return }
        if (porEnvase == null || porEnvase <= 0) { binding.etPorEnvase.error = "Requerido"; return }

        if (frecuenciaConfig == null) {
            Toast.makeText(this, "Configura la frecuencia y horario", Toast.LENGTH_SHORT).show()
            return
        }

        // La clave: cada medicamento cuelga del uid del usuario logueado
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, "Debes iniciar sesión", Toast.LENGTH_SHORT).show()
            return
        }

        val esPastilla = tipo == Medicamento.TIPO_PASTILLA
        val original = medEditando

        // Al editar: si NO cambió lo comprado se conserva lo que queda; si cambió, se reinicia
        val existencia = if (original != null && original.envases == envases &&
            original.unidadesPorEnvase == porEnvase
        ) original.existenciaActual else (envases * porEnvase).toDouble()

        val medicamento = Medicamento(
            id = original?.id ?: "",
            tipo = tipo,
            nombre = nombre,
            dosisCantidad = dosis,
            dosisUnidad = binding.spUnidad.selectedItem.toString(),
            pastillasPorToma = if (esPastilla) pastillasPorToma else null,
            envases = envases,
            unidadesPorEnvase = porEnvase,
            existenciaActual = existencia,
            fotoBase64 = if (original != null && !fotoModificada) original.fotoBase64 else fotoABase64(),
            fondoEliminado = fondoEliminado,
            frecuencia = frecuenciaConfig?.toMap(),
            terminado = original?.terminado ?: false,
            creadoEn = original?.creadoEn            // conserva la fecha de inicio original
        )

        val coleccion = FirebaseFirestore.getInstance()
            .collection("usuarios").document(uid)
            .collection("medicamentos")
        val tarea: Task<*> = if (original != null) {
            coleccion.document(original.id).set(medicamento)
        } else {
            coleccion.add(medicamento)
        }

        binding.btnGuardar.isEnabled = false
        tarea.addOnCompleteListener { resultado ->
            if (resultado.isSuccessful) {
                Toast.makeText(
                    this,
                    if (original != null) "Cambios guardados" else "Medicamento guardado",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                binding.btnGuardar.isEnabled = true
                Toast.makeText(
                    this, "Error al guardar: ${resultado.exception?.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------- Modo edición

    private fun cargarParaEditar(id: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("usuarios").document(uid)
            .collection("medicamentos").document(id).get()
            .addOnSuccessListener { doc ->
                val med = doc.toObject(Medicamento::class.java)
                if (med == null) {
                    Toast.makeText(this, "No se encontró el medicamento", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    medEditando = med
                    rellenarFormulario(med)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "No se pudo cargar el medicamento", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    private fun rellenarFormulario(med: Medicamento) {
        binding.toolbar.title = "Editar medicamento"
        binding.btnGuardar.text = "Guardar cambios"

        val liquido = med.tipo == Medicamento.TIPO_LIQUIDO
        binding.toggleTipo.check(if (liquido) R.id.btnLiquido else R.id.btnPastilla)  // reconstruye unidades

        binding.etNombre.setText(med.nombre)
        binding.etDosis.setText(formatearNumero(med.dosisCantidad))
        val unidades = if (liquido) unidadesLiquido else unidadesPastilla
        binding.spUnidad.setSelection(unidades.indexOf(med.dosisUnidad).coerceAtLeast(0))

        if (!liquido) {
            binding.togglePorToma.check(
                when (med.pastillasPorToma) {
                    0.5 -> R.id.btnMedia
                    2.0 -> R.id.btnDos
                    else -> R.id.btnUna
                }
            )
        }
        binding.etEnvases.setText(med.envases.toString())
        binding.etPorEnvase.setText(med.unidadesPorEnvase.toString())

        frecuenciaConfig = med.frecuencia?.let { FrecuenciaConfig.fromMap(it) }
        frecuenciaConfig?.let { binding.tvFrecuenciaEstado.text = it.resumen() }

        med.fotoBase64?.let { b64 ->
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                fotoBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                fondoEliminado = med.fondoEliminado
                mostrarFoto()
            } catch (e: Exception) {
                // foto dañada: se deja sin foto
            }
        }
    }
}
