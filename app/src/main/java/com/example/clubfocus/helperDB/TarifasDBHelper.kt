package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.entity.Tarifas

class TarifasDBHelper internal constructor(context: Context, nombreDb: String) : ClubFocusDBHelper(context, nombreDb) {
    constructor(context: Context) : this(context, NOMBRE)

    fun obtenerTarifas(): Tarifas = leer(readableDatabase)

    fun guardarTarifas(usuario: String, tarifas: Tarifas): Boolean = writableDatabase.transaction {
        if (!UsuariosDBHelper.consultarRol(this, usuario).equals("SuperAdmin", ignoreCase = true) ||
            tarifas.mensualCentavos !in 1..Tarifas.MAX_CENTAVOS ||
            tarifas.diariaCentavos !in 1..Tarifas.MAX_CENTAVOS
        ) return@transaction false
        val valores = ContentValues().apply {
            put("mensual_centavos", tarifas.mensualCentavos)
            put("diaria_centavos", tarifas.diariaCentavos)
        }
        update("tarifas", valores, "id = 1", null) == 1
    }

    companion object {
        internal fun leer(db: SQLiteDatabase): Tarifas =
            db.rawQuery("SELECT mensual_centavos, diaria_centavos FROM tarifas WHERE id = 1", null).use {
                check(it.moveToFirst()) { "Falta la configuración de tarifas" }
                Tarifas(it.getLong(0), it.getLong(1))
            }
    }
}
