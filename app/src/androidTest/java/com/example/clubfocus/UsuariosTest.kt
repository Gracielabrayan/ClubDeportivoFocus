package com.example.clubfocus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.clubfocus.helperDB.DBTablas
import com.example.clubfocus.helperDB.UsuariosDBHelper
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsuariosTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombreDb = "usuarios-${UUID.randomUUID()}.db"

    @After
    fun eliminarBaseDePrueba() {
        context.deleteDatabase(nombreDb)
    }

    @Test
    fun baseNuevaVaciaPermiteUnSoloSuperAdminYGuardaClavesConSalesDistintas() {
        val contrasena = UUID.randomUUID().toString()
        UsuariosDBHelper(context, nombreDb).use { usuarios ->
            val db = usuarios.readableDatabase
            assertTrue(usuarios.requiereSuperAdmin())
            listOf("usuarios", "clientes", "cuotas", "carnets").forEach { tabla ->
                assertEquals(0, contar(db, tabla))
            }

            assertEquals(-1L, usuarios.crearSuperAdminInicial("propietario", "corta"))
            assertTrue(usuarios.crearSuperAdminInicial("propietario", contrasena) > 0)
            assertFalse(usuarios.requiereSuperAdmin())
            assertEquals(-1L, usuarios.crearSuperAdminInicial("otro", contrasena))
            assertEquals("SuperAdmin", usuarios.obtenerRol("propietario", contrasena))
            assertNull(usuarios.obtenerRol("propietario", UUID.randomUUID().toString()))

            assertTrue(usuarios.crearAdministrador("operador", contrasena) > 0)
            assertEquals("Administrador", usuarios.obtenerRol("operador", contrasena))
            val claveSuperAdmin = credencial(db, "propietario")
            val claveAdmin = credencial(db, "operador")
            assertNotEquals(contrasena, claveSuperAdmin)
            assertNotEquals(contrasena, claveAdmin)
            assertNotEquals(claveSuperAdmin, claveAdmin)
            assertEquals(2, contar(db, "usuarios"))
        }
    }

    @Test
    fun migraLaClaveAnteriorYConservaLosRegistrosDelClub() {
        val contrasenaAnterior = UUID.randomUUID().toString().take(8)
        context.openOrCreateDatabase(nombreDb, Context.MODE_PRIVATE, null).use { db ->
            DBTablas.crearTodasLasTablas(db)
            db.execSQL("DROP TABLE pagos_anulados")
            db.execSQL("DROP TABLE cuotas")
            db.execSQL(
                """
                    CREATE TABLE cuotas (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, cliente_id INTEGER NOT NULL REFERENCES clientes(id),
                        tipo TEXT NOT NULL, monto REAL NOT NULL, monto_final REAL NOT NULL, fecha_pago TEXT,
                        fecha_vencimiento TEXT NOT NULL, medio_pago TEXT, promocion TEXT, estado TEXT NOT NULL
                    )
                """.trimIndent()
            )
            db.version = 1
            db.execSQL(
                "INSERT INTO usuarios(usuario, password, rol) VALUES(?, ?, ?)",
                arrayOf("admin", contrasenaAnterior, "Administrador")
            )
            db.execSQL(
                """
                    INSERT INTO clientes(id, nombre, apellido, dni, fecha_nacimiento, email,
                      apto_fisico, fecha_inscripcion, es_socio, nro_socio)
                    VALUES(1, 'Cliente', 'De prueba', '10000001', '01/01/2000', 'cliente@example.invalid',
                      1, '01/01/2026', 1, 'CAR1001')
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO cuotas(cliente_id,tipo,monto,monto_final,fecha_vencimiento,estado) " +
                    "VALUES(1,'MENSUAL',45000,45000,'01/02/2026','PENDIENTE')"
            )
            db.execSQL(
                "INSERT INTO carnets(nro_socio,fecha_emision,fecha_vencimiento) " +
                    "VALUES('CAR1001','01/01/2026','01/02/2026')"
            )
        }

        UsuariosDBHelper(context, nombreDb).use { usuarios ->
            assertTrue(usuarios.requiereSuperAdmin())
            assertEquals("Administrador", usuarios.obtenerRol("admin", contrasenaAnterior))
            val db = usuarios.readableDatabase
            assertEquals(6, db.version)
            assertNotEquals(contrasenaAnterior, credencial(db, "admin"))
            listOf("clientes", "cuotas", "carnets").forEach { tabla -> assertEquals(1, contar(db, tabla)) }
            assertEquals(0, contar(db, "pagos_anulados"))
        }
    }

    private fun credencial(db: SQLiteDatabase, usuario: String): String =
        db.rawQuery("SELECT password FROM usuarios WHERE usuario = ?", arrayOf(usuario)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun contar(db: SQLiteDatabase, tabla: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $tabla", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
