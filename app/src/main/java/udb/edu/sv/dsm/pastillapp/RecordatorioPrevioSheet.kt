package udb.edu.sv.dsm.pastillapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import udb.edu.sv.dsm.pastillapp.databinding.ItemOpcionBinding
import udb.edu.sv.dsm.pastillapp.databinding.SheetRecordatorioPrevioBinding

/** Modal "Recordatorio previo". Devuelve el resultado con setFragmentResult al pulsar "Listo". */
class RecordatorioPrevioSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "recordatorio_previo"
        const val KEY_ACTIVO = "activo"
        const val KEY_MINUTOS = "minutos"

        fun newInstance(activo: Boolean, minutos: Int) = RecordatorioPrevioSheet().apply {
            arguments = Bundle().apply {
                putBoolean(KEY_ACTIVO, activo)
                putInt(KEY_MINUTOS, minutos)
            }
        }
    }

    private var _b: SheetRecordatorioPrevioBinding? = null
    private val b get() = _b!!

    private var activo = true
    private var minutos = 15

    private val opciones = listOf(
        5 to "5 minutos antes",
        15 to "15 minutos antes",
        30 to "30 minutos antes",
        60 to "1 hora antes"
    )

    override fun getTheme() = R.style.Pill_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = SheetRecordatorioPrevioBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val origen = savedInstanceState ?: requireArguments()
        activo = origen.getBoolean(KEY_ACTIVO, true)
        minutos = origen.getInt(KEY_MINUTOS, 15)

        b.swActivo.isChecked = activo
        b.swActivo.setOnCheckedChangeListener { _, marcado ->
            activo = marcado
            dibujarOpciones()
        }
        b.btnAtras.setOnClickListener { dismiss() }          // sin guardar
        b.btnListo.setOnClickListener {
            val resultado = Bundle().apply {
                putBoolean(KEY_ACTIVO, activo)
                putInt(KEY_MINUTOS, minutos)
            }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, resultado)
            dismiss()
        }
        dibujarOpciones()
    }

    private fun dibujarOpciones() {
        b.llOpciones.removeAllViews()
        opciones.forEach { (min, texto) ->
            val fila = ItemOpcionBinding.inflate(layoutInflater, b.llOpciones, false)
            fila.tvOpcion.text = texto
            UiHelpers.marcarOpcion(fila, min == minutos)
            fila.root.setOnClickListener {
                if (activo) {
                    minutos = min
                    dibujarOpciones()
                }
            }
            b.llOpciones.addView(fila.root)
        }
        b.llOpciones.alpha = if (activo) 1f else 0.4f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ACTIVO, activo)
        outState.putInt(KEY_MINUTOS, minutos)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
