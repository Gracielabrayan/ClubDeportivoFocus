package com.example.clubfocus.helperDB

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.core.database.sqlite.transaction
import com.example.clubfocus.data.CifradoCopiaClub
import com.example.clubfocus.data.CopiaNoValida
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.MedioPago
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.entity.Tarifas
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal class CopiaClubDBHelper(context: Context, nombreDb: String = NOMBRE) : ClubFocusDBHelper(context, nombreDb) {
    fun tieneAcceso(usuario: String): Boolean = esSuperAdmin(readableDatabase, usuario)

    fun exportar(usuario: String): ByteArray = writableDatabase.transaction(exclusive = false) {
        if (!esSuperAdmin(this, usuario)) throw CopiaSinPermiso()
        val salida = SalidaCopia()
        JsonWriter(salida.writer(Charsets.UTF_8)).use { json ->
            json.beginObject()
            TablaCopia.entries.forEach { tabla ->
                json.name(tabla.name.lowercase()).beginArray()
                val columnas = tabla.columnas.map { it.nombre }.toTypedArray()
                query(tabla.name.lowercase(), columnas, null, null, null, null, "id").use { cursor ->
                    while (cursor.moveToNext()) {
                        json.beginArray()
                        tabla.columnas.forEachIndexed { indice, columna ->
                            when {
                                cursor.isNull(indice) -> json.nullValue()
                                columna.tipo in listOf(TipoCampo.ID, TipoCampo.BOOLEANO, TipoCampo.CENTAVOS) ->
                                    json.value(cursor.getLong(indice))
                                columna.tipo == TipoCampo.IMPORTE -> json.value(cursor.getDouble(indice))
                                else -> json.value(cursor.getString(indice))
                            }
                        }
                        json.endArray()
                    }
                }
                json.endArray()
            }
            json.endObject()
        }
        salida.toByteArray()
    }

    fun restaurar(usuario: String, copia: DatosCopiaClub) = writableDatabase.transaction {
        if (!esSuperAdmin(this, usuario)) throw CopiaSinPermiso()
        val tablas = TablaCopia.entries.filter { it != TablaCopia.TARIFAS || copia.incluyeTarifas }
        tablas.asReversed().forEach { delete(it.name.lowercase(), null, null) }
        tablas.forEach { tabla ->
            copia.filas(tabla).forEach { fila ->
                if (insertOrThrow(tabla.name.lowercase(), null, fila) == -1L) throw CopiaNoValida()
            }
        }
    }

    private fun esSuperAdmin(db: SQLiteDatabase, usuario: String): Boolean = db.rawQuery(
        "SELECT 1 FROM usuarios WHERE usuario = ? AND LOWER(rol) = 'superadmin'",
        arrayOf(usuario)
    ).use { it.moveToFirst() }
}

internal class CopiaSinPermiso : IOException()

internal class DatosCopiaClub private constructor(private val tablas: Map<TablaCopia, List<ContentValues>>) {
    fun filas(tabla: TablaCopia): List<ContentValues> = tablas.getValue(tabla)

    val clientes: Int get() = filas(TablaCopia.CLIENTES).size
    val cuotas: Int get() = filas(TablaCopia.CUOTAS).size
    val pagos: Int get() = filas(TablaCopia.CUOTAS).count { it.getAsString("estado") == EstadoCuota.PAGADA.name }
    val carnets: Int get() = filas(TablaCopia.CARNETS).size
    val anulaciones: Int get() = filas(TablaCopia.PAGOS_ANULADOS).size
    val incluyeTarifas: Boolean get() = filas(TablaCopia.TARIFAS).isNotEmpty()

    companion object {
        fun leer(datos: ByteArray): DatosCopiaClub = try {
            JsonReader(datos.inputStream().reader(Charsets.UTF_8)).use { json ->
                json.beginObject()
                val tablas = TablaCopia.entries.associateWith { tabla ->
                    if (tabla in listOf(TablaCopia.PAGOS_ANULADOS, TablaCopia.TARIFAS) && !json.hasNext()) {
                        return@associateWith emptyList()
                    }
                    if (json.nextName() != tabla.name.lowercase()) throw CopiaNoValida()
                    json.beginArray()
                    val filas = buildList {
                        while (json.hasNext()) {
                            json.beginArray()
                            val fila = ContentValues()
                            tabla.columnas.forEach { it.leer(json, fila) }
                            json.endArray()
                            add(fila)
                        }
                    }
                    json.endArray()
                    filas
                }
                json.endObject()
                if (json.peek() != JsonToken.END_DOCUMENT) throw CopiaNoValida()
                validarRelaciones(tablas)
                DatosCopiaClub(tablas)
            }
        } catch (_: IllegalStateException) {
            throw CopiaNoValida()
        } catch (_: IllegalArgumentException) {
            throw CopiaNoValida()
        }

        private fun validarRelaciones(tablas: Map<TablaCopia, List<ContentValues>>) {
            tablas.values.forEach { filas ->
                if (filas.map { it.getAsInteger("id") }.toSet().size != filas.size) throw CopiaNoValida()
            }
            val clientes = tablas.getValue(TablaCopia.CLIENTES)
            val idsClientes = clientes.map { it.getAsInteger("id") }.toSet()
            val numerosSocio = clientes.mapNotNull { it.getAsString("nro_socio") }
            val carnets = tablas.getValue(TablaCopia.CARNETS)
            val cuotas = tablas.getValue(TablaCopia.CUOTAS)
            val idsCuotas = cuotas.map { it.getAsInteger("id") }.toSet()
            val tarifas = tablas.getValue(TablaCopia.TARIFAS)
            if (tarifas.isNotEmpty() && (tarifas.size != 1 || tarifas.single().getAsInteger("id") != 1)) {
                throw CopiaNoValida()
            }
            if (clientes.map { it.getAsString("dni") }.toSet().size != clientes.size ||
                numerosSocio.toSet().size != numerosSocio.size ||
                carnets.map { it.getAsString("nro_socio") }.toSet().size != carnets.size ||
                cuotas.any { it.getAsInteger("cliente_id") !in idsClientes } ||
                carnets.any { it.getAsString("nro_socio") !in numerosSocio } ||
                tablas.getValue(TablaCopia.PAGOS_ANULADOS).any { it.getAsInteger("cuota_id") !in idsCuotas }
            ) throw CopiaNoValida()
        }
    }
}

internal enum class TablaCopia(vararg val columnas: ColumnaCopia) {
    CLIENTES(
        ColumnaCopia("id", TipoCampo.ID), ColumnaCopia("nombre"), ColumnaCopia("apellido"), ColumnaCopia("dni"),
        ColumnaCopia("fecha_nacimiento", TipoCampo.FECHA), ColumnaCopia("email"),
        ColumnaCopia("apto_fisico", TipoCampo.BOOLEANO), ColumnaCopia("fecha_inscripcion", TipoCampo.FECHA),
        ColumnaCopia("activo", TipoCampo.BOOLEANO), ColumnaCopia("es_socio", TipoCampo.BOOLEANO),
        ColumnaCopia("nro_socio", opcional = true)
    ),
    CUOTAS(
        ColumnaCopia("id", TipoCampo.ID), ColumnaCopia("cliente_id", TipoCampo.ID),
        ColumnaCopia("tipo", TipoCampo.TIPO_CUOTA), ColumnaCopia("monto", TipoCampo.IMPORTE),
        ColumnaCopia("monto_final", TipoCampo.IMPORTE), ColumnaCopia("fecha_pago", TipoCampo.FECHA, opcional = true),
        ColumnaCopia("fecha_vencimiento", TipoCampo.FECHA), ColumnaCopia("medio_pago", TipoCampo.MEDIO, opcional = true),
        ColumnaCopia("promocion", TipoCampo.PROMOCION, opcional = true), ColumnaCopia("estado", TipoCampo.ESTADO),
        ColumnaCopia("vencimiento_antes_del_pago", TipoCampo.FECHA, opcional = true, puedeFaltar = true),
        ColumnaCopia("clave_pago", opcional = true, puedeFaltar = true)
    ),
    CARNETS(
        ColumnaCopia("id", TipoCampo.ID), ColumnaCopia("nro_socio"),
        ColumnaCopia("fecha_emision", TipoCampo.FECHA), ColumnaCopia("fecha_vencimiento", TipoCampo.FECHA)
    ),
    PAGOS_ANULADOS(
        ColumnaCopia("id", TipoCampo.ID), ColumnaCopia("cuota_id", TipoCampo.ID),
        ColumnaCopia("tipo", TipoCampo.TIPO_CUOTA), ColumnaCopia("monto_final", TipoCampo.IMPORTE),
        ColumnaCopia("fecha_pago", TipoCampo.FECHA, opcional = true), ColumnaCopia("fecha_vencimiento", TipoCampo.FECHA),
        ColumnaCopia("medio_pago", TipoCampo.MEDIO, opcional = true),
        ColumnaCopia("promocion", TipoCampo.PROMOCION, opcional = true),
        ColumnaCopia("fecha_anulacion"), ColumnaCopia("usuario"), ColumnaCopia("motivo")
    ),
    TARIFAS(
        ColumnaCopia("id", TipoCampo.ID),
        ColumnaCopia("mensual_centavos", TipoCampo.CENTAVOS),
        ColumnaCopia("diaria_centavos", TipoCampo.CENTAVOS)
    )
}

internal enum class TipoCampo { TEXTO, ID, BOOLEANO, IMPORTE, CENTAVOS, FECHA, TIPO_CUOTA, ESTADO, MEDIO, PROMOCION }

internal class ColumnaCopia(
    val nombre: String,
    val tipo: TipoCampo = TipoCampo.TEXTO,
    private val opcional: Boolean = false,
    private val puedeFaltar: Boolean = false
) {
    fun leer(json: JsonReader, fila: ContentValues) {
        if (puedeFaltar && !json.hasNext()) {
            fila.putNull(nombre)
            return
        }
        if (json.peek() == JsonToken.NULL) {
            if (!opcional) throw CopiaNoValida()
            json.nextNull()
            fila.putNull(nombre)
            return
        }
        when (tipo) {
            TipoCampo.CENTAVOS -> {
                if (json.peek() != JsonToken.NUMBER) throw CopiaNoValida()
                val valor = json.nextLong()
                if (valor !in 1..Tarifas.MAX_CENTAVOS) throw CopiaNoValida()
                fila.put(nombre, valor)
            }
            TipoCampo.ID, TipoCampo.BOOLEANO -> {
                if (json.peek() != JsonToken.NUMBER) throw CopiaNoValida()
                val valor = json.nextInt()
                if (tipo == TipoCampo.ID && valor <= 0 || tipo == TipoCampo.BOOLEANO && valor !in 0..1) throw CopiaNoValida()
                fila.put(nombre, valor)
            }
            TipoCampo.IMPORTE -> {
                if (json.peek() != JsonToken.NUMBER) throw CopiaNoValida()
                val importe = json.nextDouble()
                if (!importe.isFinite() || importe < 0) throw CopiaNoValida()
                fila.put(nombre, importe)
            }
            else -> {
                if (json.peek() != JsonToken.STRING) throw CopiaNoValida()
                val texto = json.nextString()
                val valor = when (tipo) {
                    TipoCampo.FECHA -> normalizarFecha(texto)
                    TipoCampo.TIPO_CUOTA -> TipoCuota.valueOf(texto).name
                    TipoCampo.ESTADO -> EstadoCuota.valueOf(texto).name
                    TipoCampo.MEDIO -> MedioPago.valueOf(texto).name
                    TipoCampo.PROMOCION -> Promocion.valueOf(texto).name
                    else -> texto
                }
                fila.put(nombre, valor)
            }
        }
    }

    private fun normalizarFecha(texto: String): String {
        val fecha = try {
            LocalDate.parse(texto, FORMATO_FECHA)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(texto)
            } catch (_: DateTimeParseException) {
                throw CopiaNoValida()
            }
        }
        return fecha.format(FORMATO_FECHA)
    }

    companion object {
        private val FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

private class SalidaCopia : ByteArrayOutputStream() {
    override fun write(b: Int) {
        if (size() >= CifradoCopiaClub.MAX_BYTES) throw CopiaNoValida()
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > CifradoCopiaClub.MAX_BYTES - size()) throw CopiaNoValida()
        super.write(b, off, len)
    }
}
