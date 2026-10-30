package udb.edu.sv.dsm.pastillapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Se guarda en: usuarios/{uid}/medicamentos/{id}
 */
data class Medicamento(
    @DocumentId val id: String = "",
    val tipo: String = TIPO_PASTILLA,          // "pastilla" | "liquido"
    val nombre: String = "",
    val dosisCantidad: Double = 0.0,
    val dosisUnidad: String = "mg",            // mg, g, mcg | ml, cucharadita, cucharada
    val pastillasPorToma: Double? = null,      // 1.0, 0.5, 2.0 (solo pastilla)
    val envases: Int = 0,                      // blísteres o frascos
    val unidadesPorEnvase: Int = 0,            // pastillas c/u o ml c/u
    val existenciaActual: Double = 0.0,        // envases * unidadesPorEnvase (se descuenta en cada toma; admite 0.5)
    val fotoBase64: String? = null,            // miniatura (PNG sin fondo o JPEG)
    val fondoEliminado: Boolean = false,
    val frecuencia: Map<String, Any>? = null,  // se llena en la siguiente pantalla
    val terminado: Boolean = false,            // true = tratamiento terminado (sin alarmas)
    @ServerTimestamp val creadoEn: Date? = null
) {
    companion object {
        const val TIPO_PASTILLA = "pastilla"
        const val TIPO_LIQUIDO = "liquido"
    }

    // OJO: estas son FUNCIONES (no propiedades "get...") para que Firestore no las guarde como campos.

    /** Lo comprado: envases x unidades por envase. */
    fun totalInicial(): Double = (envases * unidadesPorEnvase).toDouble()

    /** 0..100 */
    fun porcentajeRestante(): Int {
        val total = totalInicial()
        return if (total <= 0) 0 else ((existenciaActual / total) * 100).roundToInt().coerceIn(0, 100)
    }

    /** "12 pastillas" / "45 ml" */
    fun textoRestante(): String {
        val numero = if (existenciaActual % 1.0 == 0.0) existenciaActual.toInt().toString()
        else String.format(Locale.US, "%.1f", existenciaActual)
        return "$numero ${if (tipo == TIPO_LIQUIDO) "ml" else "pastillas"}"
    }

    /** "500 mg - 1 pastilla" / "5 ml" */
    fun dosisTexto(): String {
        fun n(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
        if (tipo == TIPO_LIQUIDO) return "${n(dosisCantidad)} $dosisUnidad"
        val pastillas = when (val por = pastillasPorToma ?: 1.0) {
            0.5 -> "1/2 pastilla"
            1.0 -> "1 pastilla"
            else -> "${n(por)} pastillas"
        }
        return "${n(dosisCantidad)} $dosisUnidad - $pastillas"
    }

    /** Último día del tratamiento, o null si es indefinido. */
    fun fechaFin(): Date? {
        val config = frecuencia?.let { FrecuenciaConfig.fromMap(it) } ?: return null
        val dias = config.duracionDias
        if (config.duracionIndefinida || dias == null || dias <= 0) return null
        val cal = Calendar.getInstance()
        cal.time = creadoEn ?: Date()
        cal.add(Calendar.DAY_OF_YEAR, dias - 1)
        return cal.time
    }

    /** Úsalo en el Home para mostrar la foto: ivMedPhoto.setImageBitmap(med.fotoComoBitmap()) */
    fun fotoComoBitmap(): Bitmap? {
        val data = fotoBase64 ?: return null
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
