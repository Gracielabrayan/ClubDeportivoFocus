package com.example.clubfocus

import android.graphics.Bitmap
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.entity.Tarifas
import com.example.clubfocus.helperDB.TarifasDBHelper
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TarifasPantallaTest {
    @Test
    fun abreDesdeElPanelValidaLosPreciosYGuardaConComaDecimal() {
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val context = instrumentacion.targetContext
        val usuario = "tarifas-${UUID.randomUUID()}"
        val preferencias = context.getSharedPreferences("ClubFocusPrefs", 0)
        val usuarioAnterior = preferencias.getString("usuarioLogueado", null)
        TarifasDBHelper(context).use { helper ->
            val originales = helper.obtenerTarifas()
            helper.writableDatabase.execSQL(
                "INSERT INTO usuarios(usuario,password,rol) VALUES(?,?,'SuperAdmin')",
                arrayOf(usuario, UUID.randomUUID().toString())
            )
            preferencias.edit().putString("usuarioLogueado", usuario).commit()
            try {
                ActivityScenario.launch(AdministrarUsuariosActivity::class.java).use { escenario ->
                    val monitor = instrumentacion.addMonitor(TarifasActivity::class.java.name, null, false)
                    try {
                        escenario.onActivity { it.findViewById<Button>(R.id.btnTarifas).performClick() }
                        val pantalla = requireNotNull(instrumentacion.waitForMonitorWithTimeout(monitor, 15000))
                        instrumentacion.waitForIdleSync()
                        instrumentacion.uiAutomation.takeScreenshot()?.let { captura ->
                            File(context.cacheDir, "tarifas-preview.png").outputStream().use {
                                captura.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            captura.recycle()
                        }
                        instrumentacion.runOnMainSync {
                            val mensual = pantalla.findViewById<EditText>(R.id.etTarifaMensual)
                            val diaria = pantalla.findViewById<EditText>(R.id.etTarifaDiaria)
                            val guardar = pantalla.findViewById<Button>(R.id.btnGuardarTarifas)
                            mensual.setText("0")
                            diaria.setText("25000,75")
                            guardar.performClick()
                            assertNotNull(mensual.error)
                            mensual.setText("55000,50")
                            guardar.performClick()
                        }
                        val limite = System.nanoTime() + 10_000_000_000L
                        while (helper.obtenerTarifas() != Tarifas(5500050, 2500075) && System.nanoTime() < limite) {
                            Thread.sleep(100)
                        }
                        assertEquals(Tarifas(5500050, 2500075), helper.obtenerTarifas())
                        instrumentacion.waitForIdleSync()
                    } finally {
                        instrumentacion.removeMonitor(monitor)
                    }
                }
            } finally {
                assertTrue(helper.guardarTarifas(usuario, originales))
                helper.writableDatabase.delete("usuarios", "usuario = ?", arrayOf(usuario))
                preferencias.edit().putString("usuarioLogueado", usuarioAnterior).commit()
            }
        }
    }
}
