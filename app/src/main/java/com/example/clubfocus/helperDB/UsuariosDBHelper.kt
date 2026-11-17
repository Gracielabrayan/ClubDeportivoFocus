package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.Contrasenas

data class AdministradorCuenta(val id: Int, val usuario: String)

class UsuariosDBHelper internal constructor(context: Context, nombreDb: String) : ClubFocusDBHelper(context, nombreDb) {
    constructor(context: Context) : this(context, NOMBRE)

    fun obtenerRol(usuario: String, contrasena: String): String? {
        val cuenta = readableDatabase.rawQuery(
            "SELECT rol, password FROM usuarios WHERE usuario = ?",
            arrayOf(usuario)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }
        return cuenta?.takeIf { Contrasenas.coincide(contrasena, it.second) }?.first
    }

    fun requiereSuperAdmin(): Boolean = !haySuperAdmin(readableDatabase)

    fun crearSuperAdminInicial(usuario: String, contrasena: String): Long {
        val valores = prepararCuenta(usuario, contrasena, "SuperAdmin") ?: return -1L
        return writableDatabase.transaction {
            if (haySuperAdmin(this)) return@transaction -1L
            insert("usuarios", null, valores)
        }
    }

    fun obtenerRolPorUsuario(usuario: String): String? {
        return readableDatabase.rawQuery(
            "SELECT rol FROM usuarios WHERE usuario = ?",
            arrayOf(usuario)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow("rol")) else null
        }
    }

    fun obtenerAdministradores(): List<AdministradorCuenta> {
        val administradores = mutableListOf<AdministradorCuenta>()
        readableDatabase.rawQuery(
            "SELECT id, usuario FROM usuarios WHERE LOWER(rol) = LOWER(?) ORDER BY usuario",
            arrayOf("Administrador")
        ).use { cursor ->
            while (cursor.moveToNext()) {
                administradores.add(
                    AdministradorCuenta(
                        id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        usuario = cursor.getString(cursor.getColumnIndexOrThrow("usuario"))
                    )
                )
            }
        }
        return administradores
    }

    fun crearAdministrador(usuario: String, contrasena: String): Long {
        val valores = prepararCuenta(usuario, contrasena, "Administrador") ?: return -1L
        return writableDatabase.insert("usuarios", null, valores)
    }

    private fun prepararCuenta(usuario: String, contrasena: String, rol: String): ContentValues? {
        val nombre = usuario.trim()
        if (nombre.isBlank() || !Contrasenas.esValidaParaAlta(contrasena)) return null
        return ContentValues().apply {
            put("usuario", nombre)
            put("password", Contrasenas.guardar(contrasena))
            put("rol", rol)
        }
    }

    private fun haySuperAdmin(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM usuarios WHERE LOWER(rol) = 'superadmin' LIMIT 1", null
    ).use { it.moveToFirst() }

    fun eliminarAdministrador(id: Int): Int {
        return writableDatabase.delete(
            "usuarios",
            "id = ? AND LOWER(rol) = LOWER(?)",
            arrayOf(id.toString(), "Administrador")
        )
    }
}
