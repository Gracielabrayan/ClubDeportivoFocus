package com.example.clubfocus

import android.content.ActivityNotFoundException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.example.clubfocus.data.CifradoCopiaClub
import com.example.clubfocus.data.Contrasenas
import com.example.clubfocus.data.CopiaNoValida
import com.example.clubfocus.helperDB.CopiaClubDBHelper
import com.example.clubfocus.helperDB.CopiaSinPermiso
import com.example.clubfocus.helperDB.DatosCopiaClub
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CopiaClubActivity : AppCompatActivity() {
    private val estado by viewModels<EstadoExportacionCopia>()
    private lateinit var botonCrear: Button
    private lateinit var botonRestaurar: Button
    private val usuario: String
        get() = getSharedPreferences("ClubFocusPrefs", MODE_PRIVATE).getString("usuarioLogueado", "").orEmpty()

    private val guardarArchivo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val copia = estado.copiaParaGuardar
        estado.copiaParaGuardar = null
        if (uri != null) {
            ejecutar {
                withContext(Dispatchers.IO) {
                    try {
                        if (copia == null) throw IOException("No hay una exportación preparada")
                        verificarAcceso()
                        val salida = contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                        salida.use { it.write(copia) }
                    } catch (error: IOException) {
                        borrarDocumentoNuevo(uri)
                        throw error
                    } catch (error: SecurityException) {
                        borrarDocumentoNuevo(uri)
                        throw error
                    }
                }
                avisar(R.string.copia_guardada)
            }
        }
    }

    private val abrirArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pedirContrasena(nueva = false) { contrasena -> prepararRestauracion(uri, contrasena) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_copia_club)
        botonCrear = findViewById(R.id.btnCrearCopiaClub)
        botonRestaurar = findViewById(R.id.btnRestaurarCopiaClub)
        findViewById<Button>(R.id.btnVolverCopiaClub).setOnClickListener { finish() }

        ejecutar { withContext(Dispatchers.IO) { verificarAcceso() } }
        botonCrear.setOnClickListener {
            pedirContrasena(nueva = true) { contrasena ->
                ejecutar {
                    estado.copiaParaGuardar = withContext(Dispatchers.IO) {
                        CopiaClubDBHelper(applicationContext).use {
                            CifradoCopiaClub.cifrar(it.exportar(usuario), contrasena)
                        }
                    }
                    val fecha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    try {
                        guardarArchivo.launch("Copia-ClubFocus-$fecha.clubfocus")
                    } catch (error: ActivityNotFoundException) {
                        estado.copiaParaGuardar = null
                        throw error
                    }
                }
            }
        }
        botonRestaurar.setOnClickListener { ejecutar { abrirArchivo.launch(arrayOf("*/*")) } }
    }

    private fun prepararRestauracion(uri: Uri, contrasena: String) = ejecutar {
        val copia = withContext(Dispatchers.IO) {
            verificarAcceso()
            val entrada = contentResolver.openInputStream(uri) ?: throw IOException()
            entrada.use { DatosCopiaClub.leer(CifradoCopiaClub.descifrar(it, contrasena)) }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.copia_confirmar_titulo)
            .setMessage(getString(
                R.string.copia_confirmar_resumen, copia.clientes, copia.cuotas, copia.pagos, copia.carnets, copia.anulaciones
            ) + "\n\n" + getString(
                if (copia.incluyeTarifas) R.string.copia_tarifas_incluidas else R.string.copia_tarifas_anteriores
            ))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.copia_reemplazar) { _, _ ->
                ejecutar {
                    withContext(Dispatchers.IO) {
                        CopiaClubDBHelper(applicationContext).use { it.restaurar(usuario, copia) }
                    }
                    avisar(R.string.copia_restaurada)
                }
            }
            .show()
    }

    private fun pedirContrasena(nueva: Boolean, continuar: (String) -> Unit) {
        val campos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = (24 * resources.displayMetrics.density).toInt()
            setPadding(margen, margen / 2, margen, 0)
        }
        fun campo(hint: Int) = EditText(this).apply {
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            isSaveEnabled = false
            campos.addView(this)
        }
        val clave = campo(R.string.copia_contrasena)
        val repetir = if (nueva) campo(R.string.login_confirmar_contrasena) else null
        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (nueva) R.string.copia_crear else R.string.copia_restaurar)
            .setView(campos)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.copia_continuar, null)
            .show()

        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val contrasena = clave.text.toString()
            when {
                !Contrasenas.esValidaParaAlta(contrasena) -> clave.error = getString(R.string.contrasena_requisitos)
                repetir != null && repetir.text.toString() != contrasena ->
                    repetir.error = getString(R.string.login_contrasenas_distintas)
                else -> {
                    clave.text.clear()
                    repetir?.text?.clear()
                    dialogo.dismiss()
                    continuar(contrasena)
                }
            }
        }
    }

    private fun verificarAcceso() {
        CopiaClubDBHelper(applicationContext).use { if (!it.tieneAcceso(usuario)) throw CopiaSinPermiso() }
    }

    private fun borrarDocumentoNuevo(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun ejecutar(accion: suspend () -> Unit) {
        botonCrear.isEnabled = false
        botonRestaurar.isEnabled = false
        lifecycleScope.launch {
            try {
                accion()
            } catch (_: CopiaSinPermiso) {
                avisar(R.string.admin_panel_solo_superadmin)
                finish()
            } catch (_: CopiaNoValida) {
                avisar(R.string.copia_no_valida)
            } catch (_: IOException) {
                avisar(R.string.copia_error)
            } catch (_: SQLiteException) {
                avisar(R.string.copia_error)
            } catch (_: SecurityException) {
                avisar(R.string.copia_error)
            } catch (_: GeneralSecurityException) {
                avisar(R.string.copia_error)
            } catch (_: ActivityNotFoundException) {
                avisar(R.string.copia_error)
            } finally {
                if (!isFinishing) {
                    botonCrear.isEnabled = true
                    botonRestaurar.isEnabled = true
                }
            }
        }
    }

    private fun avisar(mensaje: Int) = Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
}

internal class EstadoExportacionCopia : ViewModel() {
    var copiaParaGuardar: ByteArray? = null
}
