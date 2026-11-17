package com.example.clubfocus

import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.provider.OpenableColumns
import android.widget.Button
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.DocumentoFocusPdf
import com.example.clubfocus.data.documentoPdf
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.helperDB.CarnetDBHelper
import com.example.clubfocus.helperDB.ClientesDBHelper
import com.example.clubfocus.helperDB.CuotasDBHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarnetPdfTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clientes = ClientesDBHelper(context)
    private val cuotas = CuotasDBHelper(context)
    private val carnets = CarnetDBHelper(context)
    private var clienteId = -1

    @After
    fun eliminarDatosDePrueba() {
        if (clienteId > 0) {
            clientes.buscarPorId(clienteId)?.let { cliente ->
                carnets.writableDatabase.delete("carnets", "nro_socio = ?", arrayOf(cliente.nroSocio))
            }
            val id = arrayOf(clienteId.toString())
            cuotas.writableDatabase.delete("cuotas", "cliente_id = ?", id)
            clientes.writableDatabase.delete("clientes", "id = ?", id)
        }
        carnets.close()
        cuotas.close()
        clientes.close()
    }

    @Test
    fun generaElCarnetConSusFechasYBloqueaElPdfAlVencerOAlDarDeBaja() {
        val hoy = LocalDate.now()
        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val dni = generateSequence { Random.nextInt(10_000_000, 100_000_000).toString() }
            .first { !clientes.existeCliente(it) }
        clienteId = clientes.agregarCliente(
            "María Belén", "González Fernández", dni, "01/01/2000", "cliente@example.invalid", true, true
        ).toInt()
        val socio = requireNotNull(clientes.buscarPorId(clienteId))
        val cuota = cuotas.generarCuotaInicial(clienteId, TipoCuota.MENSUAL, 45_000.0, 45_000.0, hoy).toInt()
        assertEquals(1, cuotas.registrarPago(cuota, clienteId, hoy, 45_000.0, MedioPago.EFECTIVO, null))
        carnets.emitirORenovarPorPago(socio.nroSocio, hoy)

        val carnet = requireNotNull(carnets.buscarCarnetPorDni(dni))
        comprobarBotonPdf(carnet.id, habilitado = true)
        val archivo = carnet.documentoPdf(context).crearTemporal(context, "carnets")
        try {
            val nombre = "Carnet-Focus-${carnet.nroSocio}.pdf"
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.comprobantes", archivo, nombre)
            val resolver = context.contentResolver
            assertEquals(DocumentoFocusPdf.MIME, resolver.getType(uri))
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(nombre, cursor.getString(0))
            } ?: error("No se pudo consultar el nombre del carnet")

            PdfRenderer(requireNotNull(resolver.openFileDescriptor(uri, "r"))).use { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.openPage(0).use { pagina ->
                    assertEquals(420, pagina.width)
                    assertEquals(298, pagina.height)
                }
            }
        } finally {
            if (InstrumentationRegistry.getArguments().getString("conservarPdfParaRevision") != "true") {
                archivo.delete()
            }
        }

        carnets.writableDatabase.execSQL(
            "UPDATE carnets SET fecha_vencimiento = ? WHERE id = ?",
            arrayOf(hoy.minusDays(1).format(formato), carnet.id.toString())
        )
        comprobarBotonPdf(carnet.id, habilitado = false)

        carnets.emitirORenovarPorPago(socio.nroSocio, hoy)
        assertEquals(1, clientes.darDeBajaSocio(clienteId))
        comprobarBotonPdf(carnet.id, habilitado = false)
    }

    private fun comprobarBotonPdf(carnetId: Int, habilitado: Boolean) {
        val intent = Intent(context, CarnetActivity::class.java).putExtra("carnet_id", carnetId)
        ActivityScenario.launch<CarnetActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(habilitado, activity.findViewById<Button>(R.id.btnOpcionesCarnetPdf).isEnabled)
            }
        }
    }
}
