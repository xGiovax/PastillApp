package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import udb.edu.sv.dsm.pastillapp.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = auth.currentUser
        if (user == null) {
            irAWelcome()
            return
        }

        binding.tvCorreoUsuario.text = user.email ?: "Sin correo"

        // Cargar datos guardados en Firestore
        db.collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val nombre = document.getString("nombre") ?: ""
                    val apellido = document.getString("apellido") ?: ""
                    val nombreCompleto = "$nombre $apellido".trim()
                    binding.tvNombreUsuario.text = if (nombreCompleto.isNotEmpty()) nombreCompleto else (user.displayName ?: "Usuario")
                } else {
                    binding.tvNombreUsuario.text = user.displayName ?: "Usuario"
                }
            }
            .addOnFailureListener {
                binding.tvNombreUsuario.text = user.displayName ?: "Usuario"
            }

        binding.btnCerrarSesion.setOnClickListener {
            auth.signOut()
            irAWelcome()
        }
    }

    private fun irAWelcome() {
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}