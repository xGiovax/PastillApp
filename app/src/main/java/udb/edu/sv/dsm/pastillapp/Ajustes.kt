package udb.edu.sv.dsm.pastillapp

import android.content.Context
import android.net.Uri

/** Preferencias de la pantalla de Configuración (se guardan en el teléfono). */
object Ajustes {
    private const val PREFS = "ajustes"
    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Sonido de alarma
    fun sonidoUri(ctx: Context): Uri? = p(ctx).getString("sonido_uri", null)?.let { Uri.parse(it) }
    fun sonidoTitulo(ctx: Context): String = p(ctx).getString("sonido_titulo", null) ?: "Predeterminado"
    fun guardarSonido(ctx: Context, uri: Uri?, titulo: String) {
        p(ctx).edit().putString("sonido_uri", uri?.toString()).putString("sonido_titulo", titulo).apply()
    }

    // ---- Volumen: 0 = Bajo, 1 = Medio, 2 = Alto
    val NOMBRES_VOLUMEN = listOf("Bajo", "Medio", "Alto")
    fun volumen(ctx: Context): Int = p(ctx).getInt("volumen", 2)
    fun guardarVolumen(ctx: Context, indice: Int) = p(ctx).edit().putInt("volumen", indice).apply()
    fun factorVolumen(ctx: Context): Float = when (volumen(ctx)) {
        0 -> 0.4f
        1 -> 0.7f
        else -> 1f
    }

    // ---- Recordatorio previo por defecto (minutos) para medicamentos nuevos
    fun recordatorioPorDefecto(ctx: Context): Int = p(ctx).getInt("recordatorio_defecto", 15)
    fun guardarRecordatorioPorDefecto(ctx: Context, minutos: Int) =
        p(ctx).edit().putInt("recordatorio_defecto", minutos).apply()

    // ---- Inventario: avisar cuando queden X unidades (0 = no avisar)
    fun avisoPocasPastillas(ctx: Context): Int = p(ctx).getInt("aviso_pocas", 5)
    fun guardarAvisoPocasPastillas(ctx: Context, cantidad: Int) =
        p(ctx).edit().putInt("aviso_pocas", cantidad).apply()

    // ---- Tamaño de texto
    val NOMBRES_TEXTO = listOf("Normal", "Grande", "Muy grande")
    private val ESCALAS = listOf(1.0f, 1.15f, 1.3f)
    fun indiceTexto(ctx: Context): Int = p(ctx).getInt("texto", 0).coerceIn(0, 2)
    fun guardarIndiceTexto(ctx: Context, indice: Int) = p(ctx).edit().putInt("texto", indice).apply()
    fun escalaTexto(ctx: Context): Float = ESCALAS[indiceTexto(ctx)]
}
