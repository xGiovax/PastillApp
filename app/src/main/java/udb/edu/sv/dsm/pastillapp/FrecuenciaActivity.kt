package udb.edu.sv.dsm.pastillapp

import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import udb.edu.sv.dsm.pastillapp.FrecuenciaConfig.Companion.MODO_CADA_HORAS
import udb.edu.sv.dsm.pastillapp.FrecuenciaConfig.Companion.MODO_FIJAS
import udb.edu.sv.dsm.pastillapp.FrecuenciaConfig.Companion.MODO_NECESIDAD
import udb.edu.sv.dsm.pastillapp.databinding.ActivityFrecuenciaBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemHorarioBinding
import udb.edu.sv.dsm.pastillapp.databinding.ItemOpcionBinding
import java.util.Locale

class FrecuenciaActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRECUENCIA = "extra_frecuencia"
    }

    private lateinit var binding: ActivityFrecuenciaBinding

    // ---- Estado (cada modo conserva lo suyo si el usuario cambia de modo y regresa) ----
    private var modo = MODO_FIJAS

    // Modo 1: horas fijas (valores del Figma)
    private var veces = 2
    private val horarios = mutableListOf("08:00", "20:00")
    private val dias = mutableSetOf(1, 2, 3, 4, 5)                // 1 = Lunes ... 7 = Domingo
    private var indefinido = true
    private var diasTratamiento: Int? = null

    // Modo 2: cada X horas
    private var repetirCada = 8
    private var primeraToma = "07:00"

    // Modos 1 y 2
    private var recordatorioActivo = true
    private var recordatorioMin = 15

    // Modo 3: según necesidad
    private var dosisMaxima: Int? = null
    private var tiempoMinimo = 4

    private val horariosPorDefecto = mapOf(
        1 to listOf("08:00"),
        2 to listOf("08:00", "20:00"),
        3 to listOf("08:00", "14:00", "20:00"),
        4 to listOf("08:00", "12:00", "16:00", "20:00")
    )

    private val modos = listOf(
        MODO_FIJAS to "Horas fijas al día",
        MODO_CADA_HORAS to "Cada cierto número de horas",
        MODO_NECESIDAD to "Según necesidad (sin alarma)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFrecuenciaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        cargarConfiguracionPrevia()

        pintarModos()
        configurarModoFijas()
        configurarModoCadaHoras()
        configurarModoNecesidad()
        configurarTarjetaAccion()
        actualizarVisibilidad()

        binding.btnGuardarHorario.setOnClickListener { guardar() }
    }

    /** Si ya habían configurado algo y vuelven a abrir la pantalla, se muestra lo anterior. */
    private fun cargarConfiguracionPrevia() {
        @Suppress("DEPRECATION")
        val previa = intent.getSerializableExtra(EXTRA_FRECUENCIA) as? FrecuenciaConfig ?: return

        modo = previa.modo
        when (previa.modo) {
            MODO_FIJAS -> {
                veces = previa.vecesAlDia.coerceIn(1, 4)
                if (previa.horarios.isNotEmpty()) {
                    horarios.clear(); horarios.addAll(previa.horarios)
                }
                dias.clear(); dias.addAll(previa.diasSemana)
                indefinido = previa.duracionIndefinida
                diasTratamiento = previa.duracionDias
            }
            MODO_CADA_HORAS -> {
                repetirCada = previa.repetirCadaHoras
                primeraToma = previa.primeraToma
            }
            MODO_NECESIDAD -> {
                dosisMaxima = previa.dosisMaximaDia
                tiempoMinimo = previa.tiempoMinimoHoras
            }
        }
        if (previa.modo != MODO_NECESIDAD) {
            recordatorioActivo = previa.recordatorioPrevioActivo
            recordatorioMin = previa.recordatorioPrevioMin
        }
    }

    // ================================================================ Modos

    private fun pintarModos() {
        binding.llModos.removeAllViews()
        modos.forEach { (clave, texto) ->
            val fila = ItemOpcionBinding.inflate(layoutInflater, binding.llModos, false)
            fila.tvOpcion.text = texto
            UiHelpers.marcarOpcion(fila, clave == modo)
            fila.root.setOnClickListener {
                modo = clave
                pintarModos()
                actualizarVisibilidad()
            }
            binding.llModos.addView(fila.root)
        }
    }

    private fun actualizarVisibilidad() {
        binding.containerFijas.isVisible = modo == MODO_FIJAS
        binding.containerCada.isVisible = modo == MODO_CADA_HORAS
        binding.containerNecesidad.isVisible = modo == MODO_NECESIDAD
        pintarTarjetaAccion()
    }

    // ================================================================ Modo 1: horas fijas

    private fun configurarModoFijas() {
        // Veces al día
        val botones = listOf(binding.btnVez1, binding.btnVez2, binding.btnVez3, binding.btnVez4)
        UiHelpers.seleccionUnica(botones, veces - 1) { indice ->
            veces = indice + 1
            horarios.clear()
            horarios.addAll(horariosPorDefecto.getValue(veces))
            pintarHorarios()
        }
        pintarHorarios()
        pintarDias()

        // Duración
        UiHelpers.seleccionUnica(
            listOf(binding.btnIndefinido, binding.btnPorDias), if (indefinido) 0 else 1
        ) { indice ->
            indefinido = indice == 0
            binding.etDiasTratamiento.isVisible = !indefinido
        }
        diasTratamiento?.let { binding.etDiasTratamiento.setText(it.toString()) }
        binding.etDiasTratamiento.isVisible = !indefinido
    }

    private fun pintarHorarios() {
        binding.llHorarios.removeAllViews()
        horarios.forEachIndexed { i, hhmm ->
            crearFilaHora(binding.llHorarios, hhmm) { nueva ->
                horarios[i] = nueva
                horarios.sort()
                pintarHorarios()
            }
        }
    }

    private fun pintarDias() {
        binding.llDias.removeAllViews()
        val densidad = resources.displayMetrics.density
        val tam = (36 * densidad).toInt()
        val margen = (4 * densidad).toInt()

        listOf("L", "M", "Mi", "J", "V", "S", "D").forEachIndexed { i, etiqueta ->
            val dia = i + 1
            val seleccionado = dia in dias
            val circulo = TextView(this).apply {
                text = etiqueta
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(if (seleccionado) Color.WHITE else Color.parseColor("#6B6B66"))
                setBackgroundResource(if (seleccionado) R.drawable.bg_dia_sel else R.drawable.bg_dia)
                layoutParams = LinearLayout.LayoutParams(tam, tam).apply { marginEnd = margen }
                setOnClickListener {
                    if (!dias.add(dia)) dias.remove(dia)
                    pintarDias()
                }
            }
            binding.llDias.addView(circulo)
        }
    }

    // ================================================================ Modo 2: cada X horas

    private fun configurarModoCadaHoras() {
        binding.etRepetirCada.setText(repetirCada.toString())
        binding.etRepetirCada.doAfterTextChanged { texto ->
            repetirCada = texto?.toString()?.toIntOrNull() ?: 0
            pintarGenerados()
        }
        pintarPrimeraToma()
        pintarGenerados()
    }

    private fun pintarPrimeraToma() {
        binding.llPrimeraToma.removeAllViews()
        crearFilaHora(binding.llPrimeraToma, primeraToma, destacada = true) { nueva ->
            primeraToma = nueva
            pintarPrimeraToma()
            pintarGenerados()
        }
    }

    /** Horarios calculados: solo lectura (se recalculan al cambiar la primera toma o el intervalo). */
    private fun pintarGenerados() {
        binding.llGenerados.removeAllViews()
        FrecuenciaConfig.generarHorarios(primeraToma, repetirCada).forEach { hhmm ->
            crearFilaHora(binding.llGenerados, hhmm)
        }
    }

    // ================================================================ Modo 3: según necesidad

    private fun configurarModoNecesidad() {
        dosisMaxima?.let { binding.etDosisMaxima.setText(it.toString()) }
    }

    // ================================================================ Tarjeta verde + modales

    private fun configurarTarjetaAccion() {
        supportFragmentManager.setFragmentResultListener(
            RecordatorioPrevioSheet.REQUEST_KEY, this
        ) { _, r ->
            recordatorioActivo = r.getBoolean(RecordatorioPrevioSheet.KEY_ACTIVO, true)
            recordatorioMin = r.getInt(RecordatorioPrevioSheet.KEY_MINUTOS, 15)
            pintarTarjetaAccion()
        }
        supportFragmentManager.setFragmentResultListener(
            TiempoMinimoSheet.REQUEST_KEY, this
        ) { _, r ->
            tiempoMinimo = r.getInt(TiempoMinimoSheet.KEY_HORAS, 4)
            pintarTarjetaAccion()
        }

        binding.cardAccion.setOnClickListener {
            if (modo == MODO_NECESIDAD) {
                TiempoMinimoSheet.newInstance(tiempoMinimo)
                    .show(supportFragmentManager, "tiempo_minimo")
            } else {
                RecordatorioPrevioSheet.newInstance(recordatorioActivo, recordatorioMin)
                    .show(supportFragmentManager, "recordatorio_previo")
            }
        }
        pintarTarjetaAccion()
    }

    private fun pintarTarjetaAccion() {
        if (modo == MODO_NECESIDAD) {
            binding.tvCardTitulo.text = "Tiempo mínimo entre tomas"
            binding.tvCardSubtitulo.text = FrecuenciaConfig.textoHoras(tiempoMinimo)
        } else {
            binding.tvCardTitulo.text = "Recordatorio Previo"
            binding.tvCardSubtitulo.text =
                FrecuenciaConfig.textoRecordatorio(recordatorioActivo, recordatorioMin)
        }
    }

    // ================================================================ Fila de hora (reutilizable)

    /**
     * @param destacada  fila verde (la "Primera toma del día")
     * @param alElegir   si es null la fila es de solo lectura (horarios generados)
     */
    private fun crearFilaHora(
        contenedor: LinearLayout,
        hhmm: String,
        destacada: Boolean = false,
        alElegir: ((String) -> Unit)? = null
    ) {
        val h = hhmm.substringBefore(":").toInt()
        val m = hhmm.substringAfter(":").toInt()

        val fila = ItemHorarioBinding.inflate(layoutInflater, contenedor, false)
        fila.ivIconoHora.setImageResource(if (h in 6..17) R.drawable.ic_sun else R.drawable.ic_moon)
        fila.tvHora.text = FrecuenciaConfig.hora12(hhmm)

        if (destacada) {
            val blanco = ColorStateList.valueOf(Color.WHITE)
            fila.root.setBackgroundResource(R.drawable.bg_opcion_sel)
            fila.tvHora.setTextColor(Color.WHITE)
            fila.ivIconoHora.imageTintList = blanco
            fila.ivEditar.imageTintList = blanco
        }

        if (alElegir == null) {
            fila.ivEditar.isVisible = false
            fila.root.isClickable = false
            fila.root.isFocusable = false
        } else {
            fila.root.setOnClickListener {
                TimePickerDialog(this, { _, hh, mm ->
                    alElegir(String.format(Locale.US, "%02d:%02d", hh, mm))
                }, h, m, false).show()
            }
        }
        contenedor.addView(fila.root)
    }

    // ================================================================ Guardar

    private fun guardar() {
        val config: FrecuenciaConfig = when (modo) {

            MODO_FIJAS -> {
                if (dias.isEmpty()) return avisar("Elige al menos un día de la semana")
                if (horarios.toSet().size != horarios.size) return avisar("Hay horarios repetidos")

                var duracion: Int? = null
                if (!indefinido) {
                    duracion = binding.etDiasTratamiento.text.toString().toIntOrNull()
                    if (duracion == null || duracion <= 0) {
                        binding.etDiasTratamiento.error = "Ingresa los días"
                        return
                    }
                }
                FrecuenciaConfig(
                    modo = MODO_FIJAS,
                    vecesAlDia = veces,
                    horarios = horarios.toList(),
                    diasSemana = dias.sorted(),
                    duracionIndefinida = indefinido,
                    duracionDias = duracion,
                    recordatorioPrevioActivo = recordatorioActivo,
                    recordatorioPrevioMin = recordatorioMin
                )
            }

            MODO_CADA_HORAS -> {
                if (repetirCada !in 1..23) {
                    binding.etRepetirCada.error = "1 a 23"
                    return
                }
                FrecuenciaConfig(
                    modo = MODO_CADA_HORAS,
                    vecesAlDia = 0,
                    horarios = FrecuenciaConfig.generarHorarios(primeraToma, repetirCada),
                    diasSemana = listOf(1, 2, 3, 4, 5, 6, 7),   // todos los días (el Figma no pide días)
                    duracionIndefinida = true,
                    repetirCadaHoras = repetirCada,
                    primeraToma = primeraToma,
                    recordatorioPrevioActivo = recordatorioActivo,
                    recordatorioPrevioMin = recordatorioMin
                )
            }

            else -> {   // MODO_NECESIDAD
                val texto = binding.etDosisMaxima.text.toString().trim()
                val dosis = texto.toIntOrNull()
                if (texto.isNotEmpty() && (dosis == null || dosis <= 0)) {
                    binding.etDosisMaxima.error = "Número inválido"
                    return
                }
                FrecuenciaConfig(
                    modo = MODO_NECESIDAD,
                    vecesAlDia = 0,
                    horarios = emptyList(),
                    diasSemana = emptyList(),
                    recordatorioPrevioActivo = false,
                    dosisMaximaDia = dosis,
                    tiempoMinimoHoras = tiempoMinimo
                )
            }
        }

        setResult(RESULT_OK, Intent().putExtra(EXTRA_FRECUENCIA, config))
        finish()
    }

    private fun avisar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
    }
}
