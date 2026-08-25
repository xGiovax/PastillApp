package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import udb.edu.sv.dsm.pastillapp.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegistrar.setOnClickListener {
            val nombre = binding.etNombre.text.toString().trim()
            val apellido = binding.etApellido.text.toString().trim()
            val correo = binding.etCorreo.text.toString().trim()
            val pass = binding.etContrasena.text.toString().trim()

            if (nombre.isEmpty() || apellido.isEmpty() || correo.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pass.length < 6) {
                Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            registrarUsuario(nombre, apellido, correo, pass)
        }

        binding.tvLoginLink.setOnClickListener {
            finish()
        }
    }

    private fun registrarUsuario(nombre: String, apellido: String, correo: String, pass: String) {
        auth.createUserWithEmailAndPassword(correo, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid ?: return@addOnCompleteListener

                    // Guardar perfil en Firestore vinculado al UID
                    val usuarioMap = hashMapOf(
                        "nombre" to nombre,
                        "apellido" to apellido,
                        "correo" to correo,
                        "proveedor" to "correo"
                    )

                    db.collection("usuarios").document(uid)
                        .set(usuarioMap)
                        .addOnSuccessListener {
                            Toast.makeText(this, "Registro exitoso", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this, HomeActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Error al guardar perfil: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this, "Error de registro: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}