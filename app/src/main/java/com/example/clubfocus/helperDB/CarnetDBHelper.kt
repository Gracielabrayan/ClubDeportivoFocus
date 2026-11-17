package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.entity.CarnetListado
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.repository.CuotaRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CarnetDBHelper(context: Context) : ClubFocusDBHelper(context) {

    fun emitirORenovarPorPago(nroSocio: String, fechaPago: LocalDate) = writableDatabase.transaction {
        val vencimiento = CuotaRepository.calcularFechaVencimiento(fechaPago, TipoCuota.MENSUAL)
        guardarVigencia(this, nroSocio, fechaPago, vencimiento)
    }

    fun obtenerCarnetsConDatosSocio(): List<CarnetListado> {
        val lista = mutableListOf<CarnetListado>()
        val db = readableDatabase
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val consulta = """
            SELECT
                carnets.id,
                carnets.nro_socio,
                carnets.fecha_emision,
                carnets.fecha_vencimiento,
                clientes.nombre,
                clientes.apellido,
                clientes.dni
            FROM carnets
            INNER JOIN clientes
                ON carnets.nro_socio = clientes.nro_socio
            WHERE clientes.es_socio = 1 AND clientes.activo = 1
            ORDER BY clientes.apellido ASC
        """.trimIndent()

        val cursor = db.rawQuery(consulta, null)

        cursor.use {
            while (it.moveToNext()) {
                lista.add(
                    CarnetListado(
                        id = it.getInt(it.getColumnIndexOrThrow("id")),
                        nroSocio = it.getString(
                            it.getColumnIndexOrThrow("nro_socio")
                        ),
                        fechaEmision = LocalDate.parse(
                            it.getString(
                                it.getColumnIndexOrThrow("fecha_emision")
                            ),
                            formatter
                        ),
                        fechaVencimiento = LocalDate.parse(
                            it.getString(
                                it.getColumnIndexOrThrow("fecha_vencimiento")
                            ),
                            formatter
                        ),
                        nombre = it.getString(
                            it.getColumnIndexOrThrow("nombre")
                        ),
                        apellido = it.getString(
                            it.getColumnIndexOrThrow("apellido")
                        ),
                        dni = it.getString(
                            it.getColumnIndexOrThrow("dni")
                        )
                    )
                )
            }
        }

        return lista
    }

    fun buscarCarnetConDatosPorId(carnetId: Int): CarnetListado? {
        val db = readableDatabase
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val consulta = """
            SELECT
                carnets.id,
                carnets.nro_socio,
                carnets.fecha_emision,
                carnets.fecha_vencimiento,
                clientes.nombre,
                clientes.apellido,
                clientes.dni
            FROM carnets
            INNER JOIN clientes
                ON carnets.nro_socio = clientes.nro_socio
            WHERE carnets.id = ? AND clientes.es_socio = 1 AND clientes.activo = 1
            LIMIT 1
        """.trimIndent()

        val cursor = db.rawQuery(
            consulta,
            arrayOf(carnetId.toString())
        )

        cursor.use {
            if (it.moveToFirst()) {
                return CarnetListado(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    nroSocio = it.getString(
                        it.getColumnIndexOrThrow("nro_socio")
                    ),
                    fechaEmision = LocalDate.parse(
                        it.getString(
                            it.getColumnIndexOrThrow("fecha_emision")
                        ),
                        formatter
                    ),
                    fechaVencimiento = LocalDate.parse(
                        it.getString(
                            it.getColumnIndexOrThrow("fecha_vencimiento")
                        ),
                        formatter
                    ),
                    nombre = it.getString(
                        it.getColumnIndexOrThrow("nombre")
                    ),
                    apellido = it.getString(
                        it.getColumnIndexOrThrow("apellido")
                    ),
                    dni = it.getString(
                        it.getColumnIndexOrThrow("dni")
                    )
                )
            }
        }

        return null
    }

    fun buscarCarnetPorDni(dni: String): CarnetListado? {
        val db = readableDatabase
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val cursor = db.rawQuery(
            """
                SELECT carnets.id, carnets.nro_socio,
                       carnets.fecha_emision, carnets.fecha_vencimiento,
                       clientes.nombre, clientes.apellido, clientes.dni
                FROM carnets
                INNER JOIN clientes
                    ON carnets.nro_socio = clientes.nro_socio
                WHERE clientes.dni = ? AND clientes.es_socio = 1 AND clientes.activo = 1
                ORDER BY carnets.id DESC
                LIMIT 1
            """.trimIndent(),
            arrayOf(dni)
        )

        return cursor.use {
            if (it.moveToFirst()) {
                CarnetListado(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    nroSocio = it.getString(it.getColumnIndexOrThrow("nro_socio")),
                    fechaEmision = LocalDate.parse(
                        it.getString(it.getColumnIndexOrThrow("fecha_emision")),
                        formatter
                    ),
                    fechaVencimiento = LocalDate.parse(
                        it.getString(it.getColumnIndexOrThrow("fecha_vencimiento")),
                        formatter
                    ),
                    nombre = it.getString(it.getColumnIndexOrThrow("nombre")),
                    apellido = it.getString(it.getColumnIndexOrThrow("apellido")),
                    dni = it.getString(it.getColumnIndexOrThrow("dni"))
                )
            } else {
                null
            }
        }
    }

    companion object {
        internal fun guardarVigencia(
            db: SQLiteDatabase,
            nroSocio: String,
            emision: LocalDate,
            vencimiento: LocalDate
        ) {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val valores = ContentValues().apply {
                put("fecha_emision", emision.format(formatter))
                put("fecha_vencimiento", vencimiento.format(formatter))
            }

            if (db.update("carnets", valores, "nro_socio = ?", arrayOf(nroSocio)) == 0) {
                valores.put("nro_socio", nroSocio)
                db.insertOrThrow("carnets", null, valores)
            }
        }
    }
}
