package udb.edu.sv.dsm.pastillapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import udb.edu.sv.dsm.pastillapp.databinding.ActivityWelcomeBinding
import androidx.appcompat.app.AppCompatDelegate

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // Sustituye con tu Client ID Web si varía
    private val webClientId = "466962833688-1hhrmivr6dmdmpjhef43t0v9lqthcskp.apps.googleusercontent.com"

    override fun onCreate(savedInstanceState: Bundle?) {

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        if (auth.currentUser != null) {
            irAHome()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.tvRegisterLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnGoogleLogin.setOnClickListener {
            iniciarSesionConGoogle()
        }
    }

    private fun iniciarSesionConGoogle() {
        val credentialManager = CredentialManager.create(this)

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(this@WelcomeActivity, request)
                val credential = result.credential

                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                    auth.signInWithCredential(firebaseCredential)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                guardarUsuarioGoogleEnFirestore()
                            } else {
                                Toast.makeText(this@WelcomeActivity, "Error Firebase Auth: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                } else {
                    Toast.makeText(this@WelcomeActivity, "Tipo de credencial no soportado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GetCredentialException) {
                Toast.makeText(this@WelcomeActivity, "Error de inicio de sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@WelcomeActivity, "Excepción inesperada: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun guardarUsuarioGoogleEnFirestore() {
        val user = auth.currentUser
        if (user == null) {
            irAHome()
            return
        }

        val userDoc = db.collection("usuarios").document(user.uid)

        val usuarioMap = hashMapOf(
            "nombre" to (user.displayName ?: "Usuario Google"),
            "correo" to (user.email ?: ""),
            "proveedor" to "google"
        )

        // Usamos merge() para crear o actualizar el documento sin sobreescribir si ya existe
        userDoc.set(usuarioMap, com.google.firebase.firestore.SetOptions.merge())
            .addOnCompleteListener {
                // Se ejecuta tanto si tiene éxito como si falla la base de datos
                irAHome()
            }
    }

    private fun irAHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}