package udb.edu.sv.dsm.pastillapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Lo mínimo que necesita una alarma para sonar SIN internet y sin leer Firestore
 * (reinicios del teléfono, alarma a las 3 a.m., etc.).
 */
data class AlarmaSpec(
    val medId: String,
    val nombre: String,
    val dosisTexto: String,          // "50 mg - 1 pastilla"
    val horarios: List<String>,      // "HH:mm"
    val dias: List<Int>,             // 1 = Lunes ... 7 = Domingo
    val finEpoch: Long,              // 0 = indefinido
    val previoActivo: Boolean,
    val previoMin: Int,
    val descuento: Double,           // cuánto inventario se gasta en cada toma
    val existencia: Double,          // inventario conocido
    val sinFondo: Boolean            // la foto es un PNG recortado
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("medId", medId)
        put("nombre", nombre)
        put("dosisTexto", dosisTexto)
        put("horarios", JSONArray(horarios))
        put("dias", JSONArray(dias))
        put("finEpoch", finEpoch)
        put("previoActivo", previoActivo)
        put("previoMin", previoMin)
        put("descuento", descuento)
        put("existencia", existencia)
        put("sinFondo", sinFondo)
    }

    companion object {
        fun fromJson(o: JSONObject): AlarmaSpec {
            val h = o.getJSONArray("horarios")
            val d = o.getJSONArray("dias")
            return AlarmaSpec(
                medId = o.getString("medId"),
                nombre = o.getString("nombre"),
                dosisTexto = o.getString("dosisTexto"),
                horarios = List(h.length()) { h.getString(it) },
                dias = List(d.length()) { d.getInt(it) },
                finEpoch = o.getLong("finEpoch"),
                previoActivo = o.getBoolean("previoActivo"),
                previoMin = o.getInt("previoMin"),
                descuento = o.getDouble("descuento"),
                existencia = o.getDouble("existencia"),
                sinFondo = o.getBoolean("sinFondo")
            )
        }

        private fun numero(d: Double): String =
            if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

        /** Convierte un medicamento de Firestore en alarma. Devuelve null si no lleva alarma. */
        fun desde(med: Medicamento): AlarmaSpec? {
            if (med.id.isBlank()) return null
            if (med.terminado) return null          // tratamiento terminado: sin alarmas
            val mapa = med.frecuencia ?: return null
            val cfg = FrecuenciaConfig.fromMap(mapa)
            if (cfg.modo == FrecuenciaConfig.MODO_NECESIDAD) return null
            if (cfg.horarios.isEmpty() || cfg.diasSemana.isEmpty()) return null

            // Fin del tratamiento (si es "por días"), contado desde el día en que se creó
            var fin = 0L
            val diasTratamiento = cfg.duracionDias
            if (!cfg.duracionIndefinida && diasTratamiento != null && diasTratamiento > 0) {
                val cal = Calendar.getInstance()
                cal.timeInMillis = med.creadoEn?.time ?: System.currentTimeMillis()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.add(Calendar.DAY_OF_YEAR, diasTratamiento)
                fin = cal.timeInMillis
            }

            val esPastilla = med.tipo == Medicamento.TIPO_PASTILLA
            val dosisTexto = if (esPastilla) {
                val por = med.pastillasPorToma ?: 1.0
                val pastillas = when (por) {
                    0.5 -> "1/2 pastilla"
                    1.0 -> "1 pastilla"
                    else -> "${numero(por)} pastillas"
                }
                "${numero(med.dosisCantidad)} ${med.dosisUnidad} - $pastillas"
            } else {
                "${numero(med.dosisCantidad)} ${med.dosisUnidad}"
            }

            val descuento = if (esPastilla) {
                med.pastillasPorToma ?: 1.0
            } else when (med.dosisUnidad) {
                "cucharadita" -> med.dosisCantidad * 5
                "cucharada" -> med.dosisCantidad * 15
                else -> med.dosisCantidad
            }

            return AlarmaSpec(
                medId = med.id,
                nombre = med.nombre,
                dosisTexto = dosisTexto,
                horarios = cfg.horarios,
                dias = cfg.diasSemana,
                finEpoch = fin,
                previoActivo = cfg.recordatorioPrevioActivo,
                previoMin = cfg.recordatorioPrevioMin,
                descuento = descuento,
                existencia = med.existenciaActual,
                sinFondo = med.fondoEliminado
            )
        }
    }
}

object AlarmStore {
    private const val PREFS = "alarmas_store"
    private const val KEY = "specs"

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun guardar(ctx: Context, specs: List<AlarmaSpec>) {
        val arr = JSONArray()
        specs.forEach { arr.put(it.toJson()) }
        p(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun leer(ctx: Context): List<AlarmaSpec> = try {
        val arr = JSONArray(p(ctx).getString(KEY, "[]"))
        List(arr.length()) { AlarmaSpec.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun buscar(ctx: Context, medId: String): AlarmaSpec? = leer(ctx).firstOrNull { it.medId == medId }

    fun actualizar(ctx: Context, spec: AlarmaSpec) {
        guardar(ctx, leer(ctx).map { if (it.medId == spec.medId) spec else it })
    }

    fun limpiar(ctx: Context) = p(ctx).edit().remove(KEY).apply()
}
