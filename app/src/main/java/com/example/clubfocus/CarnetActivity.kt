package com.example.clubfocus

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.clubfocus.data.documentoPdf
import com.example.clubfocus.helperDB.CarnetDBHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CarnetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_carnet)

        val botonPdf = findViewById<Button>(R.id.btnOpcionesCarnetPdf)
        findViewById<TextView>(R.id.txtVolver).setOnClickListener { finish() }

        val carnetId = intent.getIntExtra("carnet_id", -1)
        val dni = intent.getStringExtra("clienteDNI")
        val carnet = CarnetDBHelper(this).use { helper ->
            when {
                carnetId != -1 -> helper.buscarCarnetConDatosPorId(carnetId)
                !dni.isNullOrBlank() -> helper.buscarCarnetPorDni(dni)
                else -> null
            }
        }

        if (carnet == null) {
            botonPdf.isEnabled = false
            Toast.makeText(this, R.string.carnet_no_disponible, Toast.LENGTH_SHORT).show()
            return
        }

        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        findViewById<TextView>(R.id.txtNroSocio).text = carnet.nroSocio
        findViewById<TextView>(R.id.txtNombreSocio).text = "${carnet.nombre} ${carnet.apellido}"
        findViewById<TextView>(R.id.txtFechaEmision).text = carnet.fechaEmision.format(formato)
        findViewById<TextView>(R.id.txtFechaVencimiento).text = carnet.fechaVencimiento.format(formato)

        botonPdf.isEnabled = !carnet.fechaVencimiento.isBefore(LocalDate.now())
        configurarExportacionPdf(
            botonPdf, R.string.carnet_pdf_opciones, "Carnet-Focus-${carnet.nroSocio}.pdf", "carnets"
        ) {
            CarnetDBHelper(applicationContext).use { it.buscarCarnetConDatosPorId(carnet.id) }
                ?.takeUnless { it.fechaVencimiento.isBefore(LocalDate.now()) }
                ?.documentoPdf(applicationContext)
        }
    }
}
