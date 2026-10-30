package udb.edu.sv.dsm.pastillapp

import android.graphics.Color
import android.widget.Button
import udb.edu.sv.dsm.pastillapp.databinding.ItemOpcionBinding

object UiHelpers {

    /** Fila de opción: seleccionada (verde + check) o no (gris + círculo vacío). */
    fun marcarOpcion(fila: ItemOpcionBinding, seleccionada: Boolean) {
        fila.root.setBackgroundResource(
            if (seleccionada) R.drawable.bg_opcion_sel else R.drawable.bg_opcion
        )
        fila.tvOpcion.setTextColor(if (seleccionada) Color.WHITE else Color.parseColor("#1C1B1F"))
        fila.ivEstado.setImageResource(
            if (seleccionada) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
        )
    }

    /**
     * Grupo de botones de selección única (1,2,3,4 / Indefinido, Por días).
     * Marca el inicial SIN llamar a [alElegir]; después llama con el índice pulsado.
     */
    fun seleccionUnica(botones: List<Button>, inicial: Int, alElegir: (Int) -> Unit) {
        fun pintar(elegido: Int) = botones.forEachIndexed { i, b -> b.isSelected = i == elegido }
        pintar(inicial)
        botones.forEachIndexed { i, boton ->
            boton.setOnClickListener {
                pintar(i)
                alElegir(i)
            }
        }
    }
}
