package udb.edu.sv.dsm.pastillapp

import java.io.Serializable
import java.util.Locale

/**
 * Configuración de frecuencia de un medicamento (3 modos).
 * Se guarda en Firestore: usuarios/{uid}/medicamentos/{id}  ->  campo "frecuencia" (Map).
 *
 * - Horas en formato 24h "HH:mm".  - Días: 1 = Lunes ... 7 = Domingo.
 * - [horarios] es SIEMPRE la lista de horas en que debe sonar la alarma:
 *     MODO_FIJAS       -> las que eligió el usuario
 *     MODO_CADA_HORAS  -> las generadas a partir de primeraToma + repetirCadaHoras
 *     MODO_NECESIDAD   -> vacía (no hay alarmas)
 */
data class FrecuenciaConfig(
    val modo: String = MODO_FIJAS,

    // Modo 1: horas fijas
    val vecesAlDia: Int = 2,
    val horarios: List<String> = listOf("08:00", "20:00"),
    val diasSemana: List<Int> = listOf(1, 2, 3, 4, 5),
    val duracionIndefinida: Boolean = true,
    val duracionDias: Int? = null,

    // Modo 2: cada X horas
    val repetirCadaHoras: Int = 8,
    val primeraToma: String = "07:00",

    // Modos 1 y 2: aviso antes de la toma
    val recordatorioPrevioActivo: Boolean = true,
    val recordatorioPrevioMin: Int = 15,

    // Modo 3: según necesidad
    val dosisMaximaDia: Int? = null,
    val tiempoMinimoHoras: Int = 4
) : Serializable {

    companion object {
        const val MODO_FIJAS = "horas_fijas"
        const val MODO_CADA_HORAS = "cada_x_horas"
        const val MODO_NECESIDAD = "segun_necesidad"

        /** "20:00" -> "8:00 p.m." */
        fun hora12(hhmm: String): String {
            val h = hhmm.substringBefore(":").toInt()
            val m = hhmm.substringAfter(":").toInt()
            val sufijo = if (h < 12) "a.m." else "p.m."
            val h12 = if (h % 12 == 0) 12 else h % 12
            return String.format(Locale.US, "%d:%02d %s", h12, m, sufijo)
        }

        fun textoRecordatorio(activo: Boolean, minutos: Int): String = when {
            !activo -> "Desactivado"
            minutos == 60 -> "1 hora antes"
            else -> "$minutos minutos antes"
        }

        fun textoHoras(horas: Int): String = if (horas == 1) "1 hora" else "$horas horas"

        /**
         * Horas del día a partir de la primera toma, sumando el intervalo hasta las 23:59.
         * Ej: primera 07:00 y cada 8 h -> 07:00, 15:00, 23:00.
         */
        fun generarHorarios(primeraToma: String, cadaHoras: Int): List<String> {
            if (cadaHoras < 1) return emptyList()
            var minutos = primeraToma.substringBefore(":").toInt() * 60 +
                    primeraToma.substringAfter(":").toInt()
            val lista = ArrayList<String>()
            while (minutos < 24 * 60) {
                lista.add(String.format(Locale.US, "%02d:%02d", minutos / 60, minutos % 60))
                minutos += cadaHoras * 60
            }
            return lista
        }

        /** Reconstruye la configuración leída de Firestore (para programar alarmas, editar...). */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any>): FrecuenciaConfig = FrecuenciaConfig(
            modo = m["modo"] as? String ?: MODO_FIJAS,
            vecesAlDia = (m["vecesAlDia"] as? Number)?.toInt() ?: 0,
            horarios = (m["horarios"] as? List<String>) ?: emptyList(),
            diasSemana = (m["diasSemana"] as? List<Number>)?.map { it.toInt() } ?: emptyList(),
            duracionIndefinida = m["duracionIndefinida"] as? Boolean ?: true,
            duracionDias = (m["duracionDias"] as? Number)?.toInt(),
            repetirCadaHoras = (m["repetirCadaHoras"] as? Number)?.toInt() ?: 8,
            primeraToma = m["primeraToma"] as? String ?: "07:00",
            recordatorioPrevioActivo = m["recordatorioPrevioActivo"] as? Boolean ?: false,
            recordatorioPrevioMin = (m["recordatorioPrevioMin"] as? Number)?.toInt() ?: 15,
            dosisMaximaDia = (m["dosisMaximaDia"] as? Number)?.toInt(),
            tiempoMinimoHoras = (m["tiempoMinimoHoras"] as? Number)?.toInt() ?: 4
        )
    }

    fun toMap(): Map<String, Any> = buildMap {
        put("modo", modo)
        put("vecesAlDia", vecesAlDia)
        put("horarios", horarios)
        put("diasSemana", diasSemana)
        put("duracionIndefinida", duracionIndefinida)
        duracionDias?.let { put("duracionDias", it) }
        put("repetirCadaHoras", repetirCadaHoras)
        put("primeraToma", primeraToma)
        put("recordatorioPrevioActivo", recordatorioPrevioActivo)
        put("recordatorioPrevioMin", recordatorioPrevioMin)
        dosisMaximaDia?.let { put("dosisMaximaDia", it) }
        put("tiempoMinimoHoras", tiempoMinimoHoras)
    }

    /** Texto corto que se muestra en la tarjeta verde de "Agregar medicamento". */
    fun resumen(): String = when (modo) {
        MODO_FIJAS -> {
            val veces = if (vecesAlDia == 1) "1 vez al día" else "$vecesAlDia veces al día"
            "$veces · " + horarios.joinToString(", ") { hora12(it) }
        }
        MODO_CADA_HORAS -> "Cada $repetirCadaHoras horas desde ${hora12(primeraToma)}"
        else -> "Según necesidad · mínimo ${textoHoras(tiempoMinimoHoras)} entre tomas"
    }
}
