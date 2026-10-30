package udb.edu.sv.dsm.pastillapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import udb.edu.sv.dsm.pastillapp.databinding.SheetTiempoMinimoBinding

/** Modal "Tiempo mínimo entre tomas" (modo Según necesidad). Slider de 1 a 12 horas. */
class TiempoMinimoSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "tiempo_minimo"
        const val KEY_HORAS = "horas"

        fun newInstance(horas: Int) = TiempoMinimoSheet().apply {
            arguments = Bundle().apply {
                putInt(KEY_HORAS, horas)
            }
        }
    }

    private var _b: SheetTiempoMinimoBinding? = null
    private val b get() = _b!!
    private var horas = 4

    override fun getTheme() = R.style.Pill_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = SheetTiempoMinimoBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        horas = (savedInstanceState ?: requireArguments()).getInt(KEY_HORAS, 4).coerceIn(1, 12)

        b.slider.value = horas.toFloat()
        pintar()
        b.slider.addOnChangeListener { _, valor, _ ->
            horas = valor.toInt()
            pintar()
        }

        b.btnAtras.setOnClickListener { dismiss() }          // sin guardar
        b.btnListo.setOnClickListener {
            val resultado = Bundle().apply {
                putInt(KEY_HORAS, horas)
            }
            parentFragmentManager.setFragmentResult(REQUEST_KEY, resultado)
            dismiss()
        }
    }

    private fun pintar() {
        b.tvHoras.text = horas.toString()
        b.tvUnidad.text = if (horas == 1) "Hora" else "Horas"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_HORAS, horas)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
