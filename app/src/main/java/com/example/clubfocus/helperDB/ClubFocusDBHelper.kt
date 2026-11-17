package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.clubfocus.data.Contrasenas
import java.security.MessageDigest

open class ClubFocusDBHelper internal constructor(context: Context, nombreDb: String = NOMBRE) :
    SQLiteOpenHelper(context, nombreDb, null, VERSION) {
    final override fun onCreate(db: SQLiteDatabase) {
        DBTablas.crearTodasLasTablas(db)
    }

    final override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrarCredenciales(db)
    }

    private fun migrarCredenciales(db: SQLiteDatabase) {
        db.compileStatement("PRAGMA secure_delete = ON").use { it.simpleQueryForLong() }
        val cuentas = db.rawQuery("SELECT id, usuario, password FROM usuarios", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(CuentaAnterior(cursor.getInt(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

        cuentas.forEach { cuenta ->
            val id = arrayOf(cuenta.id.toString())
            if (esCuentaDeDemostracion(cuenta)) {
                db.delete("usuarios", "id = ?", id)
            } else {
                val credencial = ContentValues().apply { put("password", Contrasenas.guardar(cuenta.contrasena)) }
                db.update("usuarios", credencial, "id = ?", id)
            }
        }
    }

    private fun esCuentaDeDemostracion(cuenta: CuentaAnterior): Boolean {
        val credencial = "${cuenta.usuario}\u0000${cuenta.contrasena}".toByteArray(Charsets.UTF_8)
        val huella = MessageDigest.getInstance("SHA-256").digest(credencial).joinToString("") { "%02x".format(it) }
        return huella in HUELLAS_DEMOSTRACION_V1
    }

    private data class CuentaAnterior(val id: Int, val usuario: String, val contrasena: String)

    companion object {
        internal const val NOMBRE = "ClubFocus.db"
        internal const val VERSION = 2

        // Identifica exclusivamente las credenciales que la versión 1 precargaba.
        private val HUELLAS_DEMOSTRACION_V1 = setOf(
            "1d38013397193f42a0688d260c4aa555ddc9d8a0e609e050b253cce2dfa484bf",
            "773d409e02ae3404469e5b863a8a7ff658ccfa9a4ade1009f317266926dae5bd"
        )
    }
}
