package com.example.clubfocus

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ConfirmarPagoActivity : AppCompatActivity() {

    private val abrirComprobante = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) volverAlMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_confirmar_pago)

        val btnComprobante =
            findViewById<TextView>(R.id.btnImprimirComprobante)

        val btnCarnet =
            findViewById<TextView>(R.id.btnImprimirCarnet)

        val btnMenu =
            findViewById<TextView>(R.id.btnIrMenu)

        val esSocio =
            intent.getBooleanExtra("esSocio", false)

        val dni =
            intent.getStringExtra("clienteDNI") ?: ""

        val cuotaId = intent.getIntExtra(ComprobantePagoExitoso.EXTRA_CUOTA_ID, -1)
        val clavePago = intent.getStringExtra(ComprobantePagoExitoso.EXTRA_CLAVE_PAGO)

        btnCarnet.visibility = if (esSocio) View.VISIBLE else View.GONE

        // IR AL COMPROBANTE

        btnComprobante.setOnClickListener {

            val intent = Intent(this, ComprobantePagoExitoso::class.java)

            intent.putExtra(ComprobantePagoExitoso.EXTRA_CUOTA_ID, cuotaId)
            intent.putExtra(ComprobantePagoExitoso.EXTRA_CLAVE_PAGO, clavePago)

            abrirComprobante.launch(intent)
        }

        // IR AL CARNET

        btnCarnet.setOnClickListener {

            if (esSocio) {

                val intent = Intent(this, CarnetActivity::class.java)

                intent.putExtra("clienteDNI", dni)

                startActivity(intent)
            }
        }

        // VOLVER AL MENÚ

        btnMenu.setOnClickListener { volverAlMenu() }
    }

    private fun volverAlMenu() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}
