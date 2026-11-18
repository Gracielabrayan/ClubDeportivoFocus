package com.example.clubfocus

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.data.CifradoCopiaClub
import com.example.clubfocus.data.CopiaNoValida
import com.example.clubfocus.helperDB.CopiaClubDBHelper
import com.example.clubfocus.helperDB.CopiaSinPermiso
import com.example.clubfocus.helperDB.DatosCopiaClub
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopiaClubTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val origen = "origen-${UUID.randomUUID()}.db"
    private val destino = "destino-${UUID.randomUUID()}.db"

    @After
    fun eliminarBasesDePrueba() {
        context.deleteDatabase(origen)
        context.deleteDatabase(destino)
    }

    @Test
    fun restauraUnaCopiaCifradaEnOtraBaseYConservaLasCuentasLocales() {
        val clave = UUID.randomUUID().toString()
        val texto = CopiaClubDBHelper(context, origen).use { db ->
            cargarDatos(db, "10000001", "CAR1001", "Origen")
            assertThrows(CopiaSinPermiso::class.java) { db.exportar("operador") }
            db.exportar("Origen")
        }

        val cifrada = CifradoCopiaClub.cifrar(texto, clave)
        assertFalse(cifrada.toString(Charsets.UTF_8).contains("10000001"))
        assertFalse(texto.toString(Charsets.UTF_8).contains("usuarios"))
        assertThrows(CopiaNoValida::class.java) {
            CifradoCopiaClub.descifrar(cifrada.inputStream(), UUID.randomUUID().toString())
        }
        val copia = DatosCopiaClub.leer(CifradoCopiaClub.descifrar(cifrada.inputStream(), clave))
        assertEquals(1, copia.clientes)
        assertEquals(1, copia.cuotas)
        assertEquals(1, copia.pagos)
        assertEquals(1, copia.carnets)

        CopiaClubDBHelper(context, destino).use { db ->
            cargarDatos(db, "10000002", "CAR1002", "Destino")
            assertThrows(CopiaSinPermiso::class.java) { db.restaurar("operador", copia) }
            assertEquals("10000002", consultar(db, "SELECT dni FROM clientes"))
            db.restaurar("Destino", copia)
            assertEquals("10000001", consultar(db, "SELECT dni FROM clientes"))
            assertEquals("PAGADA", consultar(db, "SELECT estado FROM cuotas"))
            assertEquals("CAR1001", consultar(db, "SELECT nro_socio FROM carnets"))
            assertEquals("Destino", consultar(db, "SELECT usuario FROM usuarios WHERE rol = 'SuperAdmin'"))
            assertEquals("2", consultar(db, "SELECT COUNT(*) FROM usuarios"))
        }
    }

    @Test
    fun siFallaUnaInsercionLaRestauracionNoBorraLosDatosAnteriores() {
        val copia = CopiaClubDBHelper(context, origen).use { db ->
            cargarDatos(db, "10000001", "CAR1001", "Origen")
            DatosCopiaClub.leer(db.exportar("Origen"))
        }

        CopiaClubDBHelper(context, destino).use { db ->
            cargarDatos(db, "10000002", "CAR1002", "Destino")
            db.writableDatabase.execSQL(
                "CREATE TRIGGER fallo_carnet BEFORE INSERT ON carnets BEGIN SELECT RAISE(ABORT, 'fallo de prueba'); END"
            )
            assertThrows(SQLiteException::class.java) { db.restaurar("Destino", copia) }
            assertEquals("10000002", consultar(db, "SELECT dni FROM clientes"))
            assertEquals("PAGADA", consultar(db, "SELECT estado FROM cuotas"))
            assertEquals("CAR1002", consultar(db, "SELECT nro_socio FROM carnets"))
        }
    }

    private fun cargarDatos(helper: CopiaClubDBHelper, dni: String, numero: String, propietario: String) {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO usuarios(usuario,password,rol) VALUES(?,?,'SuperAdmin'), ('operador',?,'Administrador')",
            arrayOf(propietario, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        )
        db.execSQL(
            """
                INSERT INTO clientes(id,nombre,apellido,dni,fecha_nacimiento,email,apto_fisico,fecha_inscripcion,es_socio,nro_socio)
                VALUES(10,'María','González',?,'01/01/2000','cliente@example.invalid',1,'01/11/2026',1,?)
            """.trimIndent(),
            arrayOf(dni, numero)
        )
        db.execSQL(
            """
                INSERT INTO cuotas(id,cliente_id,tipo,monto,monto_final,fecha_pago,fecha_vencimiento,medio_pago,estado)
                VALUES(20,10,'MENSUAL',45000,45000,'01/11/2026','01/12/2026','EFECTIVO','PAGADA')
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO carnets(id,nro_socio,fecha_emision,fecha_vencimiento) VALUES(30,?,'01/11/2026','01/12/2026')",
            arrayOf(numero)
        )
    }

    private fun consultar(db: CopiaClubDBHelper, consulta: String): String = db.readableDatabase.rawQuery(consulta, null)
        .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
}
