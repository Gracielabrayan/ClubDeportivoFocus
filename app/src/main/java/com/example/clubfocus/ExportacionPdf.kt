package com.example.clubfocus

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.clubfocus.data.DocumentoFocusPdf
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun AppCompatActivity.configurarExportacionPdf(
    boton: Button,
    titulo: Int,
    nombre: String,
    subcarpeta: String,
    cargarDocumento: () -> DocumentoFocusPdf?
) {
    fun ejecutar(accion: suspend () -> Unit) {
        boton.isEnabled = false
        lifecycleScope.launch {
            try {
                accion()
            } catch (_: PdfNoDisponible) {
                Toast.makeText(this@configurarExportacionPdf, R.string.pdf_no_disponible, Toast.LENGTH_LONG).show()
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@configurarExportacionPdf, R.string.pdf_sin_app, Toast.LENGTH_LONG).show()
            } catch (_: IOException) {
                Toast.makeText(this@configurarExportacionPdf, R.string.pdf_error, Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                Toast.makeText(this@configurarExportacionPdf, R.string.pdf_error, Toast.LENGTH_LONG).show()
            } finally {
                boton.isEnabled = true
            }
        }
    }

    val guardar = registerForActivityResult(ActivityResultContracts.CreateDocument(DocumentoFocusPdf.MIME)) { uri ->
        if (uri != null) {
            ejecutar {
                withContext(Dispatchers.IO) {
                    try {
                        val documento = cargarDocumento() ?: throw PdfNoDisponible()
                        val salida = contentResolver.openOutputStream(uri, "wt")
                            ?: throw IOException("No se pudo abrir el destino del PDF")
                        salida.use { documento.escribir(applicationContext, it) }
                    } catch (error: IOException) {
                        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                        throw error
                    }
                }
                Toast.makeText(this, R.string.pdf_guardado, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun abrirOCompartir(compartir: Boolean) = ejecutar {
        val archivo = withContext(Dispatchers.IO) {
            val documento = cargarDocumento() ?: throw PdfNoDisponible()
            documento.crearTemporal(applicationContext, subcarpeta)
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.comprobantes", archivo, nombre)
        val envio = if (compartir) {
            Intent(Intent.ACTION_SEND).apply {
                type = DocumentoFocusPdf.MIME
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentoFocusPdf.MIME)
        }
        envio.clipData = ClipData.newRawUri(nombre, uri)
        envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(if (compartir) Intent.createChooser(envio, getString(R.string.pdf_compartir)) else envio)
    }

    val opciones = arrayOf(getString(R.string.pdf_abrir), getString(R.string.pdf_guardar), getString(R.string.pdf_compartir))
    boton.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setItems(opciones) { _, opcion ->
                when (opcion) {
                    0 -> abrirOCompartir(compartir = false)
                    1 -> ejecutar { guardar.launch(nombre) }
                    2 -> abrirOCompartir(compartir = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private class PdfNoDisponible : IOException()
