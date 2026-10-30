package udb.edu.sv.dsm.pastillapp

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/** Datos ya calculados que se pintan en el PDF. */
data class ReporteResumen(
    val usuario: String,
    val periodo: String,          // "Semana", "Mes", "Año"
    val rango: String,            // "27/10/2026 - 02/11/2026"
    val adherencia: Int?,         // null = sin datos
    val confirmadas: Int,
    val omitidas: Int,
    val retrasoMin: Int?,         // null = sin datos
    val porMedicamento: List<Pair<String, Int>>,
    val generado: String
)

object ReportePdf {

    private const val W = 595            // A4 a 72 dpi
    private const val H = 842
    private const val M = 40f

    fun generar(context: Context, r: ReporteResumen): File {
        val doc = PdfDocument()
        var numero = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, numero).create())
        var c = page.canvas
        var y = M

        val verde = Color.parseColor("#2D4D3A")
        val gris = Color.parseColor("#C9C9C5")
        val texto = Color.parseColor("#1C1B1F")

        val titulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = verde; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = texto; textSize = 12f }
        val negrita = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = texto; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        }
        val grande = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = texto; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }
        val relleno = Paint(Paint.ANTI_ALIAS_FLAG)

        fun nuevaPagina() {
            doc.finishPage(page)
            numero++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, numero).create())
            c = page.canvas
            y = M
        }

        // Encabezado
        c.drawText("PastillApp - Reporte de adherencia", M, y + 24f, titulo)
        y += 44f
        c.drawText("Usuario: ${r.usuario}", M, y, normal); y += 16f
        c.drawText("Periodo: ${r.periodo} (${r.rango})", M, y, normal); y += 16f
        c.drawText("Generado: ${r.generado}", M, y, normal); y += 28f

        // Tarjetas
        val ancho = (W - 2 * M - 12f) / 2f
        val alto = 70f
        val datos = listOf(
            (r.adherencia?.let { "$it%" } ?: "--") to "Adherencia general",
            r.confirmadas.toString() to "Tomas confirmadas",
            r.omitidas.toString() to "Tomas omitidas / no tomadas",
            (r.retrasoMin?.let { "$it min" } ?: "--") to "Retraso promedio"
        )
        datos.forEachIndexed { i, (valor, etiqueta) ->
            val col = i % 2
            val fila = i / 2
            val x = M + col * (ancho + 12f)
            val top = y + fila * (alto + 12f)
            relleno.color = gris
            c.drawRoundRect(x, top, x + ancho, top + alto, 14f, 14f, relleno)
            c.drawText(valor, x + 14f, top + 34f, grande)
            c.drawText(etiqueta, x + 14f, top + 56f, normal)
        }
        y += 2 * alto + 12f + 32f

        // Por medicamento
        c.drawText("Adherencia por medicamento", M, y, negrita)
        y += 20f

        if (r.porMedicamento.isEmpty()) {
            c.drawText("Sin tomas registradas en este periodo.", M, y, normal)
            y += 20f
        }
        val anchoBarra = W - 2 * M
        for ((nombre, pct) in r.porMedicamento) {
            if (y > H - 80f) nuevaPagina()
            val nombreCorto = if (nombre.length > 50) nombre.take(47) + "..." else nombre
            c.drawText(nombreCorto, M, y, normal)
            val p = "$pct%"
            c.drawText(p, M + anchoBarra - normal.measureText(p), y, normal)
            y += 8f
            relleno.color = Color.parseColor("#B5B5B0")
            c.drawRoundRect(M, y, M + anchoBarra, y + 8f, 4f, 4f, relleno)
            relleno.color = verde
            c.drawRoundRect(M, y, M + anchoBarra * (pct.coerceIn(0, 100) / 100f), y + 8f, 4f, 4f, relleno)
            y += 28f
        }

        // Pie
        if (y > H - 70f) nuevaPagina()
        y = H - 50f
        val pie = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 10f }
        c.drawText(
            "Adherencia = tomas confirmadas / (confirmadas + omitidas + no respondidas vencidas). " +
                    "Este reporte es informativo y no sustituye la indicacion de un profesional de salud.",
            M, y, pie
        )
        doc.finishPage(page)

        val dir = File(context.cacheDir, "reportes").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val archivo = File(dir, "reporte_pastillapp_${r.periodo.lowercase()}.pdf")
        archivo.outputStream().use { doc.writeTo(it) }
        doc.close()
        return archivo
    }
}
