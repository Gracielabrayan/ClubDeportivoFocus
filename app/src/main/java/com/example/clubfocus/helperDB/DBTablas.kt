package com.example.clubfocus.helperDB

import android.database.sqlite.SQLiteDatabase

object DBTablas {

    fun crearTodasLasTablas(db: SQLiteDatabase) {
        // 1. Tabla Usuarios
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario TEXT UNIQUE NOT NULL,
                password TEXT NOT NULL,
                rol TEXT NOT NULL
            )
        """.trimIndent()
        )

        // 2. Tabla Clientes
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clientes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre TEXT NOT NULL,
                apellido TEXT NOT NULL,
                dni TEXT UNIQUE NOT NULL,
                fecha_nacimiento TEXT NOT NULL,
                email TEXT NOT NULL,
                apto_fisico INTEGER NOT NULL,
                fecha_inscripcion TEXT NOT NULL,
                activo INTEGER NOT NULL DEFAULT 0,
                es_socio INTEGER NOT NULL,
                nro_socio TEXT UNIQUE
            )
        """.trimIndent()
        )

        // 3. Tabla Cuotas
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cuotas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cliente_id INTEGER NOT NULL,
                tipo TEXT NOT NULL,
                monto REAL NOT NULL,
                monto_final REAL NOT NULL,
                fecha_pago TEXT,
                fecha_vencimiento TEXT NOT NULL,
                medio_pago TEXT,
                promocion TEXT,
                estado TEXT NOT NULL,
                FOREIGN KEY (cliente_id) REFERENCES clientes(id)
            )
        """.trimIndent()
        )

        // 4. Tabla Carnets
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS carnets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nro_socio TEXT NOT NULL UNIQUE,
                fecha_emision TEXT NOT NULL,
                fecha_vencimiento TEXT NOT NULL,
                FOREIGN KEY (nro_socio) REFERENCES clientes(nro_socio)
            )
        """.trimIndent()
        )
    }
}
