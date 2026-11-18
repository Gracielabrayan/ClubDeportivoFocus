package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.FechasClub
import com.example.clubfocus.data.entity.AnulacionPago
import com.example.clubfocus.data.entity.Cuota
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.PagoHistorialItem
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.ResumenCaja
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.repository.CuotaRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

class CuotasDBHelper internal constructor(context: Context, nombreDb: String) : ClubFocusDBHelper(context, nombreDb) {
    constructor(context: Context) : this(context, NOMBRE)

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

    fun generarCuotaParaCliente(clienteId: Int, esSocio: Boolean): Cuota? = try {
        writableDatabase.transaction {
            val existente = obtenerCuotaImpagaPorCliente(clienteId)
            if (existente != null) return@transaction existente
            val dni = rawQuery(
                "SELECT dni FROM clientes WHERE id = ? AND es_socio = ?",
                arrayOf(clienteId.toString(), if (esSocio) "1" else "0")
            ).use {
                if (!it.moveToFirst()) return@transaction null
                it.getString(0)
            }
            if (tieneCuotaVigente(dni)) return@transaction null
            val cuota = CuotaRepository.prepararCuotaInicial(clienteId, esSocio, TarifasDBHelper.leer(this))
            val id = insertarCuotaInicial(
                this, cuota.clienteId, cuota.tipo, cuota.monto, cuota.montoFinal, cuota.fechaVencimiento
            )
            cuota.copy(id = id.toInt())
        }
    } catch (_: SQLiteException) {
        null
    }

    fun registrarPago(
        cuotaId: Int,
        clienteId: Int,
        fechaPago: LocalDate,
        montoFinal: Double,
        medioPago: MedioPago,
        promocion: Promocion?,
        clavePago: String = UUID.randomUUID().toString(),
        montoBaseEsperado: Double? = null,
        tipoEsperado: TipoCuota? = null
    ): Int = writableDatabase.transaction {
        val condicion = "id = ? AND cliente_id = ? AND estado IN (?, ?)"
        val argumentos = arrayOf(
            cuotaId.toString(), clienteId.toString(),
            EstadoCuota.PENDIENTE.name, EstadoCuota.VENCIDA.name
        )
        val datos = rawQuery(
            """
                SELECT q.tipo, q.fecha_vencimiento, q.monto, c.es_socio, c.nro_socio
                FROM cuotas q JOIN clientes c ON c.id = q.cliente_id
                WHERE q.id = ? AND q.cliente_id = ? AND q.estado IN (?, ?)
            """.trimIndent(), argumentos
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@transaction 0
            DatosCobro(
                TipoCuota.valueOf(cursor.getString(0)), cursor.getString(1), cursor.getDouble(2),
                cursor.getInt(3) == 1, cursor.getString(4)
            )
        }
        if (!montoFinal.isFinite() || montoFinal < 0 || montoFinal > datos.monto ||
            (montoBaseEsperado != null && montoBaseEsperado != datos.monto) ||
            (tipoEsperado != null && tipoEsperado != datos.tipo) ||
            (medioPago != MedioPago.TARJETA_CREDITO && promocion != null && promocion != Promocion.SIN_PROMOCION)
        ) return@transaction 0
        val tipo = datos.tipo
        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val valores = ContentValues().apply {
            put("fecha_pago", fechaPago.format(formato))
            put("monto_final", montoFinal)
            put("medio_pago", medioPago.name)
            put("promocion", promocion?.name)
            put("estado", EstadoCuota.PAGADA.name)
            put("vencimiento_antes_del_pago", datos.vencimiento)
            put("clave_pago", clavePago)
            val vencimiento = CuotaRepository.calcularFechaVencimiento(fechaPago, tipo)
            put("fecha_vencimiento", vencimiento.format(formato))
        }

        val filasActualizadas = update("cuotas", valores, condicion, argumentos)
        if (filasActualizadas > 0) {
            val valoresCliente = ContentValues().apply { put("activo", 1) }
            val condicionCliente = if (tipo == TipoCuota.MENSUAL) "id = ? AND es_socio = 1" else "id = ? AND es_socio = 0"
            update(
                "clientes", valoresCliente, condicionCliente, arrayOf(clienteId.toString())
            )
            if (tipo == TipoCuota.MENSUAL && datos.esSocio && !datos.nroSocio.isNullOrBlank()) {
                val vigencia = obtenerVigencia(this, clienteId, fechaPago, TipoCuota.MENSUAL)
                if (vigencia != null) {
                    CarnetDBHelper.guardarVigencia(this, datos.nroSocio, vigencia.fechaPago, vigencia.vencimiento)
                }
            }
        }
        filasActualizadas
    }

    internal fun anularPago(cuotaId: Int, clavePago: String?, usuario: String, motivo: String): Boolean =
        writableDatabase.transaction {
            val razon = motivo.trim()
            if (razon.length !in 1..250 || !UsuariosDBHelper.consultarRol(this, usuario).equals("SuperAdmin", ignoreCase = true)) {
                return@transaction false
            }
            val ahora = LocalDateTime.now()
            val hoy = ahora.toLocalDate()
            val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            rawQuery(
                """
                    SELECT q.*, c.es_socio, c.nro_socio FROM cuotas q
                    JOIN clientes c ON c.id = q.cliente_id
                    WHERE q.id = ? AND q.estado = 'PAGADA' AND COALESCE(q.clave_pago, '') = ?
                """.trimIndent(), arrayOf(cuotaId.toString(), clavePago.orEmpty())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@transaction false
                fun texto(campo: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(campo))
                val clienteId = cursor.getInt(cursor.getColumnIndexOrThrow("cliente_id"))
                val tipo = texto("tipo")
                val esSocio = cursor.getInt(cursor.getColumnIndexOrThrow("es_socio")) == 1
                val nroSocio = texto("nro_socio")
                val vencimientoPagado = requireNotNull(texto("fecha_vencimiento"))
                val vencimiento = parsearVencimiento(texto("vencimiento_antes_del_pago") ?: vencimientoPagado, formato)
                val auditoria = ContentValues().apply {
                    put("cuota_id", cuotaId)
                    put("tipo", tipo)
                    put("monto_final", cursor.getDouble(cursor.getColumnIndexOrThrow("monto_final")))
                    put("fecha_pago", texto("fecha_pago"))
                    put("fecha_vencimiento", vencimientoPagado)
                    put("medio_pago", texto("medio_pago"))
                    put("promocion", texto("promocion"))
                    put("fecha_anulacion", ahora.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                    put("usuario", usuario)
                    put("motivo", razon)
                }
                check(insertOrThrow("pagos_anulados", null, auditoria) > 0)

                val valores = ContentValues().apply {
                    put("estado", EstadoCuota.PENDIENTE.alConsultar(vencimiento, hoy).name)
                    put("fecha_vencimiento", vencimiento.format(formato))
                    put("monto_final", cursor.getDouble(cursor.getColumnIndexOrThrow("monto")))
                    listOf("fecha_pago", "medio_pago", "promocion", "clave_pago", "vencimiento_antes_del_pago")
                        .forEach(::putNull)
                }
                check(update("cuotas", valores, "id = ?", arrayOf(cuotaId.toString())) == 1)

                val tipoActual = if (esSocio) TipoCuota.MENSUAL else TipoCuota.DIARIA
                if (tipo == tipoActual.name) {
                    val vigencia = obtenerVigencia(this, clienteId, hoy, tipoActual)
                    val estado = ContentValues().apply { put("activo", if (vigencia == null) 0 else 1) }
                    update("clientes", estado, "id = ?", arrayOf(clienteId.toString()))
                    if (esSocio && vigencia != null && !nroSocio.isNullOrBlank()) {
                        CarnetDBHelper.guardarVigencia(this, nroSocio, vigencia.fechaPago, vigencia.vencimiento)
                    }
                }
                true
            }
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
        val promocion: Promocion?,
        val clavePago: String?,
        val vencimientoAnteriorConocido: Boolean
    )

    fun obtenerPagoParaComprobante(cuotaId: Int, claveEsperada: String? = null): PagoParaComprobante? {
        return readableDatabase.rawQuery(
            """
                SELECT c.nombre, c.apellido, c.dni, q.fecha_pago,
                       q.tipo, q.monto_final, q.medio_pago, q.promocion, q.clave_pago, q.vencimiento_antes_del_pago
                FROM cuotas q
                INNER JOIN clientes c ON q.cliente_id = c.id
                WHERE q.id = ? AND UPPER(q.estado) = 'PAGADA'
            """.trimIndent(),
            arrayOf(cuotaId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val clavePago = cursor.getString(cursor.getColumnIndexOrThrow("clave_pago"))
            if (claveEsperada != null && claveEsperada != clavePago.orEmpty()) return@use null

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
                promocion = promocion?.takeIf { it.isNotBlank() }?.let(Promocion::valueOf),
                clavePago = clavePago,
                vencimientoAnteriorConocido = !cursor.isNull(cursor.getColumnIndexOrThrow("vencimiento_antes_del_pago"))
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
                    fecha = fechaPagoTexto?.let(FechasClub::parsear),
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
                    fecha = fechaPagoTexto?.let(FechasClub::parsear),
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


    fun obtenerHistorialPagos(): List<PagoHistorialItem> = consultarPagos(readableDatabase)

    internal fun obtenerResumenCaja(usuario: String, dia: LocalDate): ResumenCaja? =
        writableDatabase.transaction(exclusive = false) {
            if (!UsuariosDBHelper.consultarRol(this, usuario).equals("SuperAdmin", ignoreCase = true)) {
                return@transaction null
            }
            ResumenCaja(consultarPagos(this, dia))
        }

    private fun consultarPagos(db: SQLiteDatabase, dia: LocalDate? = null): List<PagoHistorialItem> {
        val lista = mutableListOf<PagoHistorialItem>()
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val hoy = LocalDate.now()
        val filtro = if (dia != null) "WHERE q.estado = 'PAGADA' AND q.fecha_pago IN (?, ?)" else ""
        val archivo = if (dia == null) """
            UNION ALL
            SELECT a.cuota_id, q.cliente_id, a.tipo, a.monto_final, a.fecha_pago,
                a.fecha_vencimiento, a.medio_pago, 'PAGADA', NULL,
                a.fecha_anulacion, a.usuario, a.motivo
            FROM pagos_anulados a JOIN cuotas q ON q.id = a.cuota_id
        """.trimIndent() else ""

        val consulta = """
            SELECT q.*, c.nombre, c.apellido, c.dni
            FROM (
                SELECT id AS cuota_id, cliente_id, tipo, monto_final, fecha_pago,
                    fecha_vencimiento, medio_pago, estado, clave_pago,
                    NULL AS fecha_anulacion, NULL AS usuario_anulacion, NULL AS motivo_anulacion
                FROM cuotas
                $archivo
            ) q JOIN clientes c ON q.cliente_id = c.id
            $filtro
            ORDER BY
                CASE
                    WHEN UPPER(q.estado) = 'PAGADA' THEN 0
                    ELSE 1
                END,
                CASE WHEN SUBSTR(q.fecha_pago, 5, 1) = '-' THEN q.fecha_pago
                     ELSE SUBSTR(q.fecha_pago, 7, 4) || '-' ||
                          SUBSTR(q.fecha_pago, 4, 2) || '-' || SUBSTR(q.fecha_pago, 1, 2)
                END DESC
            """.trimIndent()

        val argumentos = dia?.let { arrayOf(it.format(formatter), it.toString()) }
        val cursor = db.rawQuery(consulta, argumentos)

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
                        fechaPago = fechaPagoTexto?.let { fecha -> parsearVencimiento(fecha, formatter) },
                        estado = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                            .alConsultar(vencimiento, hoy),
                        medioPago = if (!medioPagoTexto.isNullOrBlank()) MedioPago.valueOf(medioPagoTexto) else null,
                        clavePago = c.getString(c.getColumnIndexOrThrow("clave_pago")),
                        anulacion = c.getString(c.getColumnIndexOrThrow("fecha_anulacion"))?.let { fecha ->
                            AnulacionPago(
                                fecha,
                                c.getString(c.getColumnIndexOrThrow("usuario_anulacion")),
                                c.getString(c.getColumnIndexOrThrow("motivo_anulacion"))
                            )
                        }
                    )
                )
            }
        }

        return lista
    }

    private data class DatosCobro(
        val tipo: TipoCuota, val vencimiento: String, val monto: Double,
        val esSocio: Boolean, val nroSocio: String?
    )

    internal data class VigenciaCuota(val fechaPago: LocalDate, val vencimiento: LocalDate)

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

        internal fun obtenerVigencia(db: SQLiteDatabase, clienteId: Int, hoy: LocalDate, tipo: TipoCuota): VigenciaCuota? {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            return db.rawQuery(
                """
                    SELECT fecha_pago, fecha_vencimiento FROM cuotas
                    WHERE cliente_id = ? AND tipo = ? AND UPPER(estado) = 'PAGADA'
                    ORDER BY id DESC
                """.trimIndent(),
                arrayOf(clienteId.toString(), tipo.name)
            ).use { cursor ->
                var vigente: VigenciaCuota? = null
                while (cursor.moveToNext()) {
                    val fechaPago = cursor.getString(0) ?: continue
                    val vigencia = try {
                        VigenciaCuota(
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
