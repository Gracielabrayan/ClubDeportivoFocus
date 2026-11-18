package com.example.clubfocus.data.repository

import com.example.clubfocus.data.entity.Cuota
import com.example.clubfocus.data.entity.EstadoCuota
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.entity.TipoCuota
import com.example.clubfocus.data.entity.Tarifas
import java.time.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode

object CuotaRepository {


    // Cálculo automático del vencimiento
    internal fun calcularFechaVencimiento(
        fecha: LocalDate,
        tipo: TipoCuota
    ): LocalDate {
        // Vencimiento según el tipo de cliente
        return when (tipo) {
            TipoCuota.MENSUAL -> fecha.plusMonths(1)
            TipoCuota.DIARIA -> fecha
        }
    }

    // Cálculo automático del monto final si corresponde o no promociones
    fun calcularMontoFinal(
        monto: Double,
        promocion: Promocion
    ): Double {

        val factor = when (promocion) {
            Promocion.SIN_PROMOCION -> BigDecimal.ONE
            Promocion.TRES_CUOTAS -> BigDecimal("0.85")
            Promocion.SEIS_CUOTAS -> BigDecimal("0.90")
        }
        return BigDecimal.valueOf(monto).multiply(factor).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    fun aplicarPromocion(
        cuota: Cuota,
        promocion: Promocion
    ) {
        cuota.promocion = promocion

        cuota.montoFinal = calcularMontoFinal(cuota.monto, promocion)
    }

    // Devuelve la cuota lista para pasarla al Helper, como un DTO
    fun prepararCuotaInicial(
        clienteId: Int,
        esSocio: Boolean,
        tarifas: Tarifas
    ): Cuota {

        val tipo = if (esSocio) {
            TipoCuota.MENSUAL
        } else {
            TipoCuota.DIARIA
        }

        val monto = tarifas.monto(tipo)
        val fechaActual = LocalDate.now()
        val vencimiento = calcularFechaVencimiento(fechaActual, tipo)

        return Cuota(
            id = 0, // Todavía no fue insertada en SQLite
            clienteId = clienteId,
            tipo = tipo,
            monto = monto,
            montoFinal = monto,
            fecha = null, // Aún no fue pagada
            fechaVencimiento = vencimiento,
            estado = EstadoCuota.PENDIENTE,
            medioPago = null,
            promocion = null
        )
    }
}






