package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import udb.edu.sv.dsm.pastillapp.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(0, statusBarInsets.top, 0, 0)
            insets
        }

        obtenerNombreUsuario()
        configurarFechaHora()
        configurarBotones()
        configurarNavegacion()
    }

    private fun obtenerNombreUsuario() {
        val user = auth.currentUser ?: return

        // 1. Si el usuario tiene displayName (ej: inició sesión con Google)
        val googleName = user.displayName
        if (!googleName.isNullOrBlank()) {
            actualizarSaludo(googleName)
            return
        }

        // 2. Si es correo/contraseña, buscar primero en Firestore
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                val firestoreName = if (document.exists()) {
                    document.getString("nombre")
                        ?: document.getString("name")
                        ?: document.getString("username")
                } else null

                if (!firestoreName.isNullOrBlank()) {
                    actualizarSaludo(firestoreName)
                } else {
                    // Fallback 1: Si no se encuentra en Firestore, toma el nombre antes del '@' (ej: alberto@gmail.com -> Alberto)
                    val emailName = user.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
                    actualizarSaludo(emailName)
                }
            }
            .addOnFailureListener {
                // Fallback 2: Si la consulta a Firestore falla (sin conexión, etc.), usa el correo
                val emailName = user.email?.substringBefore("@")?.replaceFirstChar { it.uppercase() }
                actualizarSaludo(emailName)
            }
    }

    private fun actualizarSaludo(nombreCompleto: String?) {
        if (nombreCompleto.isNullOrEmpty()) {
            binding.tvGreeting.text = "¡Hola!"
            return
        }
        val primerNombre = nombreCompleto.trim().split(" ").firstOrNull() ?: nombreCompleto
        binding.tvGreeting.text = "¡Hola $primerNombre!"
    }

    private fun configurarFechaHora() {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy\nhh:mm a", Locale.getDefault())
        binding.tvDateTime.text = dateFormat.format(Date())
    }

    private fun configurarBotones() {
        binding.btnAddMedicine.setOnClickListener {
            Toast.makeText(this, "Agregar medicamento", Toast.LENGTH_SHORT).show()
        }

        // Evento al presionar el ícono discreto de cerrar sesión
        binding.btnLogout.setOnClickListener {
            cerrarSesion()
        }
    }

    private fun cerrarSesion() {
        auth.signOut()

        val intent = Intent(this, WelcomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun configurarNavegacion() {
        binding.bottomNavigation.selectedItemId = R.id.nav_inicio

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_reportes -> true
                R.id.nav_calendario -> true
                R.id.nav_inicio -> true
                R.id.nav_medicamentos -> true
                R.id.nav_config -> true
                else -> false
            }
        }
    }
}