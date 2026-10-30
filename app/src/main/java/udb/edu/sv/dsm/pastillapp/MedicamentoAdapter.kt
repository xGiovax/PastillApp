package udb.edu.sv.dsm.pastillapp

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import udb.edu.sv.dsm.pastillapp.databinding.ItemMedicamentoBinding

/** Decodifica cada miniatura una sola vez y la dibuja (PNG sin fondo o JPEG). */
object FotosMedicamento {
    private val cache = HashMap<String, Bitmap?>()

    private fun bitmap(med: Medicamento): Bitmap? {
        val clave = med.id + med.fotoBase64.hashCode()
        if (!cache.containsKey(clave)) {
            cache[clave] = try { med.fotoComoBitmap() } catch (e: Exception) { null }
        }
        return cache[clave]
    }

    fun mostrar(iv: ImageView, med: Medicamento) {
        val densidad = iv.resources.displayMetrics.density
        val bmp = bitmap(med)
        if (bmp != null) {
            iv.setImageBitmap(bmp)
            val pad = if (med.fondoEliminado) (8 * densidad).toInt() else 0
            iv.setPadding(pad, pad, pad, pad)
            iv.scaleType = if (med.fondoEliminado) ImageView.ScaleType.FIT_CENTER
            else ImageView.ScaleType.CENTER_CROP
        } else {
            iv.setImageResource(R.drawable.ic_pill_default)
            val pad = (16 * densidad).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }
}

class MedicamentoAdapter(
    private val alTocar: (Medicamento) -> Unit
) : RecyclerView.Adapter<MedicamentoAdapter.VH>() {

    class VH(val b: ItemMedicamentoBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<Medicamento> = emptyList()

    fun submit(lista: List<Medicamento>) {
        items = lista
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemMedicamentoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val med = items[position]
        val b = holder.b

        FotosMedicamento.mostrar(b.ivFoto, med)
        b.tvNombre.text = med.nombre
        b.tvTipo.text = if (med.tipo == Medicamento.TIPO_LIQUIDO) "Líquido" else "Pastilla"
        b.tvRestante.text = if (med.terminado) "Terminado" else "Quedan ${med.textoRestante()}"

        // En rojo si queda menos que lo configurado en "Avisar cuando quede"
        val umbral = Ajustes.avisoPocasPastillas(b.root.context)
        val pocas = !med.terminado && umbral > 0 && med.existenciaActual <= umbral
        b.tvRestante.setTextColor(Color.parseColor(if (pocas) "#B3261E" else "#1C1B1F"))

        b.root.setOnClickListener { alTocar(med) }
    }
}
