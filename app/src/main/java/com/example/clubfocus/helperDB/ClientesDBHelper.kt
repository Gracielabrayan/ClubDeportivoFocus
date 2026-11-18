package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.entity.Cliente
import com.example.clubfocus.data.FechasClub
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.entity.SocioCuotaItem
import com.example.clubfocus.data.repository.CuotaRepository
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class ClientesDBHelper internal constructor(context: Context, nombreDb: String) : ClubFocusDBHelper(context, nombreDb) {
    constructor(context: Context) : this(context, NOMBRE)

    fun agregarCliente(
        nombre: String,
        apellido: String,
        dni: String,
        fechaNacimiento: String,
        email: String,
        aptoFisico: Boolean,
        esSocio: Boolean
    ): Long = writableDatabase.transaction {
        insertarCliente(this, nombre, apellido, dni, fechaNacimiento, email, aptoFisico, esSocio)
    }

    fun registrarClienteConCuotaInicial(
        nombre: String,
        apellido: String,
        dni: String,
        fechaNacimiento: String,
        email: String,
        aptoFisico: Boolean,
        esSocio: Boolean
    ): Long = writableDatabase.transaction {
        val clienteId = insertarCliente(this, nombre, apellido, dni, fechaNacimiento, email, aptoFisico, esSocio)
        if (clienteId == -1L) return@transaction -1L

        val cuota = CuotaRepository.prepararCuotaInicial(clienteId.toInt(), esSocio, TarifasDBHelper.leer(this))
        val cuotaId = CuotasDBHelper.insertarCuotaInicial(
            this, cuota.clienteId, cuota.tipo, cuota.monto, cuota.montoFinal, cuota.fechaVencimiento
        )
        if (cuotaId == -1L) throw SQLiteException("No se insertó la cuota inicial")
        clienteId
    }

    private fun insertarCliente(
        db: SQLiteDatabase,
        nombre: String,
        apellido: String,
        dni: String,
        fechaNacimiento: String,
        email: String,
        aptoFisico: Boolean,
        esSocio: Boolean
    ): Long {
        val nroSocioGenerado = if (esSocio) obtenerSiguienteNroSocio(db) else null

        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val fechaInscripcion = formatter.format(Date())

        val valores = ContentValues().apply {
            put("nombre", nombre)
            put("apellido", apellido)
            put("dni", dni)
            put("fecha_nacimiento", fechaNacimiento)
            put("email", email)
            put("apto_fisico", if (aptoFisico) 1 else 0)
            put("fecha_inscripcion", fechaInscripcion)
            put("es_socio", if (esSocio) 1 else 0)
            put("nro_socio", nroSocioGenerado)
        }

        return db.insert("clientes", null, valores)
    }

    fun existeCliente(dni: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT dni FROM clientes WHERE dni = ?", arrayOf(dni))
        val existe = cursor.count > 0
        cursor.close()
        return existe
    }

    fun buscarPorDni(dni: String): Cliente? =
        readableDatabase.rawQuery("SELECT * FROM clientes WHERE dni = ?", arrayOf(dni)).use {
            if (it.moveToFirst()) leerCliente(it) else null
        }

    fun buscarPorDniParcial(dni: String): MutableList<Cliente> {
        val clientes = mutableListOf<Cliente>()
        readableDatabase.rawQuery(
            "SELECT * FROM clientes WHERE dni LIKE ? ORDER BY apellido, nombre",
            arrayOf("$dni%")
        ).use { cursor ->
            while (cursor.moveToNext()) clientes.add(leerCliente(cursor))
        }
        return clientes
    }

    fun buscarPorId(id: Int): Cliente? =
        readableDatabase.rawQuery("SELECT * FROM clientes WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) leerCliente(it) else null
        }

    private fun leerCliente(cursor: Cursor): Cliente = Cliente(
        id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
        nombre = cursor.getString(cursor.getColumnIndexOrThrow("nombre")),
        apellido = cursor.getString(cursor.getColumnIndexOrThrow("apellido")),
        dni = cursor.getString(cursor.getColumnIndexOrThrow("dni")),
        fechaNacimiento = FechasClub.parsear(cursor.getString(cursor.getColumnIndexOrThrow("fecha_nacimiento"))),
        email = cursor.getString(cursor.getColumnIndexOrThrow("email")),
        aptoFisico = cursor.getInt(cursor.getColumnIndexOrThrow("apto_fisico")) == 1,
        activo = cursor.getInt(cursor.getColumnIndexOrThrow("activo")) == 1,
        esSocio = cursor.getInt(cursor.getColumnIndexOrThrow("es_socio")) == 1,
        fechaInscripcion = FechasClub.parsear(cursor.getString(cursor.getColumnIndexOrThrow("fecha_inscripcion"))),
        nroSocio = cursor.getString(cursor.getColumnIndexOrThrow("nro_socio")).orEmpty()
    )

    fun obtenerVencimientosSocios(): List<SocioCuotaItem> {
        val db = readableDatabase
        val lista = mutableListOf<SocioCuotaItem>()
        val hoy = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        val cursor = db.rawQuery(
            """
                SELECT
                    q.id AS cuota_id,
                    c.id AS cliente_id,
                    c.dni,
                    c.nombre,
                    c.apellido,
                    c.nro_socio,
                    c.activo,
                    q.fecha_vencimiento,
                    UPPER(q.estado) AS estado
                FROM clientes c
                INNER JOIN cuotas q
                    ON q.cliente_id = c.id
                WHERE c.es_socio = 1
                  AND (
                      UPPER(q.estado) IN ('PENDIENTE', 'VENCIDA')
                      OR (
                          UPPER(q.estado) = 'PAGADA'
                          AND q.fecha_vencimiento IN (?, ?)
                      )
                  )
                ORDER BY
                    CASE
                        WHEN UPPER(q.estado) = 'PAGADA' THEN 0
                        ELSE 1
                    END,
                    SUBSTR(q.fecha_vencimiento, 7, 4) DESC,
                    SUBSTR(q.fecha_vencimiento, 4, 2) DESC,
                    SUBSTR(q.fecha_vencimiento, 1, 2) DESC
            """.trimIndent(), arrayOf(hoy.format(formatter), hoy.toString())
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                val fechaVencimiento = c.getString(c.getColumnIndexOrThrow("fecha_vencimiento"))

                val fechaVencLocalDate = try {
                    LocalDate.parse(fechaVencimiento, formatter)
                } catch (_: Exception) {
                    try {
                        LocalDate.parse(fechaVencimiento)
                    } catch (_: Exception) {
                        null
                    }
                }

                val estadoCuota = EstadoCuota.valueOf(c.getString(c.getColumnIndexOrThrow("estado")))
                    .alConsultar(fechaVencLocalDate, hoy)

                if (estadoCuota == EstadoCuota.PENDIENTE || fechaVencLocalDate?.isAfter(hoy) == true) {
                    continue
                }

                val nombre = c.getString(c.getColumnIndexOrThrow("nombre")) ?: ""
                val apellido = c.getString(c.getColumnIndexOrThrow("apellido")) ?: ""
                val nroSocio = c.getString(c.getColumnIndexOrThrow("nro_socio")) ?: ""

                val socio = SocioCuotaItem(
                    cuotaId = c.getInt(c.getColumnIndexOrThrow("cuota_id")),
                    clienteId = c.getInt(c.getColumnIndexOrThrow("cliente_id")),
                    dni = c.getString(c.getColumnIndexOrThrow("dni")) ?: "",
                    nombreCompleto = "$nombre $apellido".trim(),
                    nroSocio = nroSocio,
                    fechaVencimiento = fechaVencimiento,
                    activo = c.getInt(c.getColumnIndexOrThrow("activo")) == 1,
                    estadoCuota = estadoCuota.name
                )

                lista.add(socio)
            }
        }
        return lista
    }

    fun actualizarCliente(
        id: Int,
        nombre: String,
        apellido: String,
        dni: String,
        fechaNacimiento: String,
        email: String,
        aptoFisico: Boolean
    ): Int {

        val db = writableDatabase

        val valores = ContentValues().apply {
            put("nombre", nombre)
            put("apellido", apellido)
            put("dni", dni)
            put("fecha_nacimiento", fechaNacimiento)
            put("email", email)
            put("apto_fisico", if (aptoFisico) 1 else 0)
        }

        return db.update(
            "clientes",
            valores,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    private fun actualizarUltimaCuotaPendiente(
        db: SQLiteDatabase,
        clienteId: Int,
        esSocio: Boolean
    ): Int {

        val fechaActual = LocalDate.now()

        val tipo = if (esSocio) TipoCuota.MENSUAL else TipoCuota.DIARIA
        val monto = TarifasDBHelper.leer(db).monto(tipo)

        val fechaVencimiento = if (esSocio) {
            fechaActual.plusMonths(1)
        } else {
            fechaActual
        }

        val valores = ContentValues().apply {
            put("tipo", tipo.name)
            put("monto", monto)
            put("monto_final", monto)
            put("estado", EstadoCuota.PENDIENTE.name)
            put(
                "fecha_vencimiento",
                fechaVencimiento.format(
                    DateTimeFormatter.ofPattern("dd/MM/yyyy")
                )
            )
        }

        return db.update(
            "cuotas",
            valores,
            """
            id = (
                SELECT id
                FROM cuotas
                WHERE cliente_id = ?
                  AND UPPER(estado) IN ('PENDIENTE', 'VENCIDA')
                ORDER BY id DESC
                LIMIT 1
            )
        """.trimIndent(),
            arrayOf(clienteId.toString())
        )
    }

    fun darDeBajaSocio(id: Int): Int = writableDatabase.transaction {
        val valores = ContentValues().apply {
            put("es_socio", 0)
            put("activo", 0)
        }

        val filas = update(
            "clientes",
            valores,
            "id = ? AND es_socio = 1",
            arrayOf(id.toString())
        )

        if (filas > 0) {
            actualizarUltimaCuotaPendiente(this, id, false)
        }

        filas
    }

    fun asociarCliente(id: Int): Int = writableDatabase.transaction {
        val nroGuardado = rawQuery(
            "SELECT nro_socio FROM clientes WHERE id = ? AND es_socio = 0",
            arrayOf(id.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@transaction 0
            cursor.getString(0)
        }

        val nroSocio = nroGuardado?.takeIf { it.isNotBlank() } ?: obtenerSiguienteNroSocio(this)
        val vigencia = CuotasDBHelper.obtenerVigencia(this, id, LocalDate.now(), TipoCuota.MENSUAL)
        val valores = ContentValues().apply {
            put("es_socio", 1)
            put("nro_socio", nroSocio)
            put("activo", if (vigencia != null) 1 else 0)
        }

        val filas = update("clientes", valores, "id = ?", arrayOf(id.toString()))
        if (filas > 0) {
            actualizarUltimaCuotaPendiente(this, id, true)
            if (vigencia != null) {
                CarnetDBHelper.guardarVigencia(this, nroSocio, vigencia.fechaPago, vigencia.vencimiento)
            }
        }

        filas
    }

    private fun obtenerSiguienteNroSocio(db: SQLiteDatabase): String {
        val cursor = db.rawQuery(
            """
                SELECT nro_socio FROM (
                    SELECT nro_socio FROM clientes
                    UNION ALL
                    SELECT nro_socio FROM carnets
                )
                WHERE nro_socio LIKE 'CAR100%'
                ORDER BY CAST(SUBSTR(nro_socio, 7) AS INTEGER) DESC
                LIMIT 1
            """.trimIndent(),
            null
        )

        var siguienteNumero = 1

        cursor.use {
            if (it.moveToFirst()) {
                val ultimoNroSocio = it.getString(0)
                val ultimoNumero = ultimoNroSocio.substring(6).toInt()
                siguienteNumero = ultimoNumero + 1
            }
        }

        return "CAR100$siguienteNumero"
    }
}
