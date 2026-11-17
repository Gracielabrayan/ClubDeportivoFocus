package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.entity.Cuota
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.PagoHistorialItem
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.repository.CuotaRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Suppress("unused")
class CuotasDBHelper(context: Context) : ClubFocusDBHelper(context) {

    fun generarCuotaInicial(
        clienteId: Int,
        tipo: TipoCuota,
        monto: Double,
        montoFinal: Double,
        fechaVencimiento: LocalDate
    ): Long = try {
        insertarCuotaInicial(writableDatabase, clienteId, tipo, monto, montoFinal, fechaVencimiento)
    } catch (_: SQLiteException) {
        -1L
    }

    fun registrarPago(
        cuotaId: Int,
        clienteId: Int,
        fechaPago: LocalDate,
        montoFinal: Double,
        medioPago: MedioPago,
        promocion: Promocion?
    ): Int = writableDatabase.transaction {
        val condicion = "id = ? AND cliente_id = ? AND estado IN (?, ?)"
        val argumentos = arrayOf(
            cuotaId.toString(), clienteId.toString(),
            EstadoCuota.PENDIENTE.name, EstadoCuota.VENCIDA.name
        )
        val tipo = rawQuery("SELECT tipo FROM cuotas WHERE $condicion", argumentos).use { cursor ->
            if (cursor.moveToFirst()) TipoCuota.valueOf(cursor.getString(0)) else null
        } ?: return@transaction 0
        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val valores = ContentValues().apply {
            put("fecha_pago", fechaPago.format(formato))
            put("monto_final", montoFinal)
            put("medio_pago", medioPago.name)
            put("promocion", promocion?.name)
            put("estado", EstadoCuota.PAGADA.name)
            if (tipo == TipoCuota.MENSUAL) {
                val vencimiento = CuotaRepository.calcularFechaVencimiento(fechaPago, tipo)
                put("fecha_vencimiento", vencimiento.format(formato))
            }
        }

        val filasActualizadas = update("cuotas", valores, condicion, argumentos)
        if (filasActualizadas > 0) {
            val valoresCliente = ContentValues().apply { put("activo", 1) }
            val condicionCliente = if (tipo == TipoCuota.MENSUAL) "id = ?" else "id = ? AND es_socio = 0"
            update(
                "clientes", valoresCliente, condicionCliente, arrayOf(clienteId.toString())
            )
        }
        filasActualizadas
    }

    fun tieneCuotaVigente(dniCliente: String): Boolean {

        val db = readableDatabase
        val hoy = LocalDate.now()

        return db.rawQuery(
            """
            SELECT q.fecha_vencimiento
            FROM cuotas q
            INNER JOIN clientes c
                ON c.id = q.cliente_id
            WHERE c.dni = ?
              AND UPPER(q.estado) = 'PAGADA'
              AND q.tipo = CASE WHEN c.es_socio = 1 THEN 'MENSUAL' ELSE 'DIARIA' END
        """.trimIndent(),
            arrayOf(dniCliente)
        ).use { cursor ->

            while (cursor.moveToNext()) {

                val fechaVencimientoTexto = cursor.getString(
                    cursor.getColumnIndexOrThrow("fecha_vencimiento")
                )

                if (fechaVencimientoTexto.isNullOrBlank()) {
                    continue
                }

                val fechaVencimiento = try {
                    LocalDate.parse(
                        fechaVencimientoTexto,
                        DateTimeFormatter.ofPattern("dd/MM/yyyy")
                    )
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(fechaVencimientoTexto)
                    } catch (_: DateTimeParseException) {
                        continue
                    }
                }

                if (!hoy.isAfter(fechaVencimiento)) {
                    return@use true
                }
            }

            false
        }
    }

    data class PagoParaComprobante(
        val nombreCompleto: String,
        val dni: String,
        val fechaPago: String,
        val tipo: TipoCuota,
        val montoFinal: Double,
        val medioPago: MedioPago,
        val promocion: Promocion?
    )

    fun obtenerPagoParaComprobante(cuotaId: Int): PagoParaComprobante? {
        return readableDatabase.rawQuery(
            """
                SELECT c.nombre, c.apellido, c.dni, q.fecha_pago,
                       q.tipo, q.monto_final, q.medio_pago, q.promocion
                FROM cuotas q
                INNER JOIN clientes c ON q.cliente_id = c.id
                WHERE q.id = ? AND UPPER(q.estado) = 'PAGADA'
            """.trimIndent(),
            arrayOf(cuotaId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val fechaPago = cursor.getString(cursor.getColumnIndexOrThrow("fecha_pago"))
            val medio = cursor.getString(cursor.getColumnIndexOrThrow("medio_pago"))
            if (fechaPago.isNullOrBlank() || medio.isNullOrBlank()) return@use null

            val nombre = cursor.getString(cursor.getColumnIndexOrThrow("nombre"))
            val apellido = cursor.getString(cursor.getColumnIndexOrThrow("apellido"))
            val promocion = cursor.getString(cursor.getColumnIndexOrThrow("promocion"))
            PagoParaComprobante(
                nombreCompleto = "$nombre $apellido".trim(),
                dni = cursor.getString(cursor.getColumnIndexOrThrow("dni")),
                fechaPago = fechaPago,
                tipo = TipoCuota.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("tipo"))),
                montoFinal = cursor.getDouble(cursor.getColumnIndexOrThrow("monto_final")),
                medioPago = MedioPago.valueOf(medio),
                promocion = promocion?.takeIf { it.isNotBlank() }?.let(Promocion::valueOf)
            )
        }
    }

    fun obtenerCuotasPorCliente(clienteId: Int): List<Cuota> {
        val db = readableDatabase
        val listaCuotas = mutableListOf<Cuota>()
        val hoy = LocalDate.now()

        val cursor = db.rawQuery(
            "SELECT * FROM cuotas WHERE cliente_id = ? ORDER BY id DESC",
            arrayOf(clienteId.toString())
        )

        cursor.use { c ->
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

            while (c.moveToNext()) {
                val fechaPagoTexto = c.getString(c.getColumnIndexOrThrow("fecha_pago"))
                val vencimiento = parsearVencimiento(c.getString(c.getColumnIndexOrThrow("fecha_vencimiento")), formatter)
                val medioPagoTexto = c.getString(c.getColumnIndexOrThrow("medio_pago"))
                val promocionTexto = c.getString(c.getColumnIndexOrThrow("promocion"))

                val cuota = Cuota(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    clienteId = c.getInt(c.getColumnIndexOrThrow("cliente_id")),
                    tipo = TipoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("tipo"))),
                    monto = c.getDouble(c.getColumnIndexOrThrow("monto")),
                    montoFinal = c.getDouble(c.getColumnIndexOrThrow("monto_final")),
                    fecha = fechaPagoTexto?.let { fecha -> LocalDate.parse(fecha, formatter) },
                    fechaVencimiento = vencimiento,
                    estado = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                        .alConsultar(vencimiento, hoy),
                    medioPago = if (!medioPagoTexto.isNullOrBlank()) MedioPago.valueOf(medioPagoTexto) else null,
                    promocion = if (!promocionTexto.isNullOrBlank()) Promocion.valueOf(promocionTexto) else null
                )

                listaCuotas.add(cuota)
            }
        }

        return listaCuotas
    }

    fun obtenerCuotaImpagaPorCliente(clienteId: Int): Cuota? {
        val db = readableDatabase
        val hoy = LocalDate.now()

        val cursor = db.rawQuery(
            """
            SELECT * FROM cuotas
            WHERE cliente_id = ?
              AND estado IN (?, ?)
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(
                clienteId.toString(),
                EstadoCuota.PENDIENTE.name,
                EstadoCuota.VENCIDA.name
            )
        )

        cursor.use { c ->
            if (c.moveToFirst()) {
                val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                val vencimiento = parsearVencimiento(c.getString(c.getColumnIndexOrThrow("fecha_vencimiento")), formatter)
                val fechaPagoTexto = c.getString(c.getColumnIndexOrThrow("fecha_pago"))
                val medioPagoTexto = c.getString(c.getColumnIndexOrThrow("medio_pago"))
                val promocionTexto = c.getString(c.getColumnIndexOrThrow("promocion"))

                return Cuota(
                    id = c.getInt(c.getColumnIndexOrThrow("id")),
                    clienteId = c.getInt(c.getColumnIndexOrThrow("cliente_id")),
                    tipo = TipoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("tipo"))),
                    monto = c.getDouble(c.getColumnIndexOrThrow("monto")),
                    montoFinal = c.getDouble(c.getColumnIndexOrThrow("monto_final")),
                    fecha = fechaPagoTexto?.let { fecha -> LocalDate.parse(fecha, formatter) },
                    fechaVencimiento = vencimiento,
                    estado = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                        .alConsultar(vencimiento, hoy),
                    medioPago = if (!medioPagoTexto.isNullOrBlank()) MedioPago.valueOf(medioPagoTexto) else null,
                    promocion = if (!promocionTexto.isNullOrBlank()) Promocion.valueOf(promocionTexto) else null
                )
            }
        }

        return null
    }

    fun obtenerCuotasVencidasOPorVencer(): List<Cuota> {
        val db = readableDatabase
        val cuotas = mutableListOf<Cuota>()
        val hoy = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val cursor = db.rawQuery(
            """SELECT * FROM cuotas
                WHERE estado != 'PAGADA'
                ORDER BY id ASC
            """.trimIndent(),
            null
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                val fechaVencimiento = parsearVencimiento(c.getString(c.getColumnIndexOrThrow("fecha_vencimiento")), formatter)

                if (!fechaVencimiento.isAfter(hoy)) {
                    val fechaPagoTexto = c.getString(c.getColumnIndexOrThrow("fecha_pago"))
                    val medioPagoTexto = c.getString(c.getColumnIndexOrThrow("medio_pago"))
                    val promocionTexto = c.getString(c.getColumnIndexOrThrow("promocion"))

                    cuotas.add(
                        Cuota(
                            id = c.getInt(c.getColumnIndexOrThrow("id")),
                            clienteId = c.getInt(c.getColumnIndexOrThrow("cliente_id")),
                            tipo = TipoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("tipo"))),
                            monto = c.getDouble(c.getColumnIndexOrThrow("monto")),
                            montoFinal = c.getDouble(c.getColumnIndexOrThrow("monto_final")),
                            fecha = fechaPagoTexto?.let { fecha -> LocalDate.parse(fecha, formatter) },
                            fechaVencimiento = fechaVencimiento,
                            estado = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                                .alConsultar(fechaVencimiento, hoy),
                            medioPago = if (!medioPagoTexto.isNullOrBlank()) MedioPago.valueOf(medioPagoTexto) else null,
                            promocion = if (!promocionTexto.isNullOrBlank()) Promocion.valueOf(promocionTexto) else null
                        )
                    )
                }
            }
        }

        return cuotas
    }

    fun obtenerHistorialPagos(): List<PagoHistorialItem> {
        val db = readableDatabase
        val lista = mutableListOf<PagoHistorialItem>()
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val hoy = LocalDate.now()

        val consulta = """
            SELECT 
                q.id AS cuota_id,
                q.cliente_id,
                c.nombre,
                c.apellido,
                c.dni,
                q.tipo,
                q.monto_final,
                q.fecha_pago,
                q.fecha_vencimiento,
                q.medio_pago,
                q.estado
            FROM cuotas q
                INNER JOIN clientes c ON q.cliente_id = c.id
            ORDER BY
                CASE
                    WHEN UPPER(q.estado) = 'PAGADA' THEN 0
                    ELSE 1
                END,
                SUBSTR(q.fecha_pago, 7, 4) DESC,
                SUBSTR(q.fecha_pago, 4, 2) DESC,
                SUBSTR(q.fecha_pago, 1, 2) DESC
            """.trimIndent()

        val cursor = db.rawQuery(consulta, null)

        cursor.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(c.getColumnIndexOrThrow("nombre")) ?: ""
                val apellido = c.getString(c.getColumnIndexOrThrow("apellido")) ?: ""
                val vencimiento = parsearVencimiento(c.getString(c.getColumnIndexOrThrow("fecha_vencimiento")), formatter)
                val fechaPagoTexto = c.getString(c.getColumnIndexOrThrow("fecha_pago"))
                val medioPagoTexto = c.getString(c.getColumnIndexOrThrow("medio_pago"))

                lista.add(
                    PagoHistorialItem(
                        cuotaId = c.getInt(c.getColumnIndexOrThrow("cuota_id")),
                        clienteId = c.getInt(c.getColumnIndexOrThrow("cliente_id")),
                        nombreCompleto = "$nombre $apellido".trim(),
                        dni = c.getString(c.getColumnIndexOrThrow("dni")) ?: "",
                        tipo = TipoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("tipo"))),
                        montoFinal = c.getDouble(c.getColumnIndexOrThrow("monto_final")),
                        fechaVencimiento = vencimiento,
                        fechaPago = fechaPagoTexto?.let { fecha -> LocalDate.parse(fecha, formatter) },
                        estado = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                            .alConsultar(vencimiento, hoy),
                        medioPago = if (!medioPagoTexto.isNullOrBlank()) MedioPago.valueOf(medioPagoTexto) else null
                    )
                )
            }
        }

        return lista
    }

    internal data class VigenciaMensual(val fechaPago: LocalDate, val vencimiento: LocalDate)

    companion object {
        internal fun insertarCuotaInicial(
            db: SQLiteDatabase,
            clienteId: Int,
            tipo: TipoCuota,
            monto: Double,
            montoFinal: Double,
            fechaVencimiento: LocalDate
        ): Long {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            val valores = ContentValues().apply {
                put("cliente_id", clienteId)
                put("tipo", tipo.name)
                put("monto", monto)
                put("monto_final", montoFinal)
                putNull("fecha_pago")
                put("fecha_vencimiento", fechaVencimiento.format(formatter))
                putNull("medio_pago")
                putNull("promocion")
                put("estado", EstadoCuota.PENDIENTE.name)
            }
            return db.insertOrThrow("cuotas", null, valores)
        }

        internal fun obtenerVigenciaMensual(db: SQLiteDatabase, clienteId: Int, hoy: LocalDate): VigenciaMensual? {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            return db.rawQuery(
                """
                    SELECT fecha_pago, fecha_vencimiento FROM cuotas
                    WHERE cliente_id = ? AND tipo = 'MENSUAL' AND UPPER(estado) = 'PAGADA'
                    ORDER BY id DESC
                """.trimIndent(),
                arrayOf(clienteId.toString())
            ).use { cursor ->
                var vigente: VigenciaMensual? = null
                while (cursor.moveToNext()) {
                    val fechaPago = cursor.getString(0) ?: continue
                    val vigencia = try {
                        VigenciaMensual(
                            parsearVencimiento(fechaPago, formatter),
                            parsearVencimiento(cursor.getString(1), formatter)
                        )
                    } catch (_: DateTimeParseException) {
                        continue
                    }
                    if (!vigencia.vencimiento.isBefore(hoy) &&
                        (vigente == null || vigencia.vencimiento.isAfter(vigente.vencimiento))
                    ) {
                        vigente = vigencia
                    }
                }
                vigente
            }
        }

        private fun parsearVencimiento(texto: String, formatter: DateTimeFormatter): LocalDate = try {
            LocalDate.parse(texto, formatter)
        } catch (_: DateTimeParseException) {
            LocalDate.parse(texto)
        }
    }
}
