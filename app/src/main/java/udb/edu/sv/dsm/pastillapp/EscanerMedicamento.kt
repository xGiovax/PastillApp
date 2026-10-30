package udb.edu.sv.dsm.pastillapp

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer

/**
 * Lee el texto de la foto de una caja/etiqueta (ML Kit Text Recognition, en el teléfono)
 * y trata de extraer nombre, concentración, tipo y cantidad por envase.
 * Todo es una SUGERENCIA: el usuario debe verificarlo.
 */
object EscanerMedicamento {

    data class Resultado(
        val nombre: String?,
        val esLiquido: Boolean?,        // null = no se pudo saber
        val concentracion: Double?,     // 500.0
        val unidad: String?,            // "mg", "g" o "mcg"
        val porEnvase: Int?             // pastillas por blíster/caja
    ) {
        val hayAlgo: Boolean
            get() = nombre != null || esLiquido != null || concentracion != null || porEnvase != null
    }

    private class Linea(val texto: String, val alto: Int)

    private val reconocedor by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Si no hay texto legible o falla, [alTerminar] recibe null (sin errores para el usuario). */
    fun escanear(bitmap: Bitmap, alTerminar: (Resultado?) -> Unit) {
        reconocedor.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { texto ->
                val lineas = texto.textBlocks
                    .flatMap { it.lines }
                    .map { Linea(it.text, it.boundingBox?.height() ?: 0) }
                alTerminar(if (lineas.isEmpty()) null else interpretar(lineas))
            }
            .addOnFailureListener { alTerminar(null) }
    }

    // ------------------------------------------------------------------ Reglas

    private val PATRON_CONCENTRACION =
        Regex("""(\d+(?:[.,]\d+)?)\s*(mg|mcg|µg|μg|g)(?![a-z])""", RegexOption.IGNORE_CASE)

    private val PATRON_DESDE_NUMERO =
        Regex("""\d+(?:[.,]\d+)?\s*(mg|mcg|µg|μg|g|ml)(?![a-z]).*""", RegexOption.IGNORE_CASE)

    private val PATRON_CANTIDAD =
        Regex("""(\d{1,3})\s*(tabletas?|capsulas?|comprimidos?|grageas?|tabs?)""")

    private val PALABRAS_LIQUIDO = listOf("jarabe", "suspension", "gotas", "solucion oral", "elixir")
    private val PALABRAS_PASTILLA = listOf("tableta", "capsula", "comprimido", "gragea")

    /** Líneas que casi nunca son el nombre del medicamento. */
    private val PALABRAS_IGNORADAS = listOf(
        "tableta", "capsula", "comprimido", "gragea", "jarabe", "suspension", "solucion",
        "gotas", "oral", "laboratorio", "lote", "vence", "vencimiento", "fecha", "caducidad",
        "contiene", "cada", "venta", "receta", "prescripcion", "registro", "fabricado",
        "importado", "distribuido", "salvador", "mantenga", "alcance", "ninos", "medico",
        "dosis", "posologia", "indicaciones", "contenido", "caja", "blister", "sanitario",
        "antibiotico", "analgesico", "genérico", "generico", "marca"
    )

    private fun sinAcentos(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun interpretar(lineas: List<Linea>): Resultado {
        val todo = sinAcentos(lineas.joinToString("\n") { it.texto })

        // Tipo
        val liquido = PALABRAS_LIQUIDO.any { it in todo }
        val pastilla = PALABRAS_PASTILLA.any { it in todo }
        val esLiquido = when {
            liquido && !pastilla -> true
            pastilla && !liquido -> false
            else -> null
        }

        // Concentración (primera que aparece)
        val conc = PATRON_CONCENTRACION.find(lineas.joinToString("\n") { it.texto })
        val concentracion = conc?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        val unidad = conc?.groupValues?.get(2)?.lowercase()?.let {
            if (it == "µg" || it == "μg") "mcg" else it
        }

        // Cantidad por envase: "20 tabletas"
        val porEnvase = PATRON_CANTIDAD.find(todo)?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..500 }

        return Resultado(
            nombre = buscarNombre(lineas),
            esLiquido = esLiquido,
            concentracion = concentracion,
            unidad = unidad,
            porEnvase = porEnvase
        )
    }

    /** El nombre suele ser la línea de letras más grandes que no sea texto genérico. */
    private fun buscarNombre(lineas: List<Linea>): String? {
        val candidatas = lineas.mapNotNull { linea ->
            val limpio = PATRON_DESDE_NUMERO.replace(linea.texto, "")
                .trim(' ', '-', '.', ',', '/', '®', '™', ':')
            if (limpio.length < 3 || limpio.length > 30) return@mapNotNull null
            if (limpio.any { it.isDigit() }) return@mapNotNull null
            if (limpio.count { it.isLetter() } < limpio.length * 0.7) return@mapNotNull null
            if (limpio.split(" ").size > 3) return@mapNotNull null
            val normal = sinAcentos(limpio)
            if (PALABRAS_IGNORADAS.any { it in normal }) return@mapNotNull null
            Pair(limpio, linea.alto)
        }
        val mejor = candidatas.maxByOrNull { it.second }?.first ?: return null
        return mejor.split(" ").joinToString(" ") { palabra ->
            palabra.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
