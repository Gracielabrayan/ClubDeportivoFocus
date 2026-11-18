package com.example.clubfocus.data.entity

import java.math.BigDecimal
import java.math.RoundingMode

internal class ResumenCaja(val pagos: List<PagoHistorialItem>) {
    private val importes = pagos.groupingBy { it.medioPago }.fold(BigDecimal.ZERO) { subtotal, pago ->
        subtotal + BigDecimal.valueOf(pago.montoFinal).setScale(2, RoundingMode.HALF_UP)
    }

    val total: BigDecimal = importes.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val hayPagosSinMedio: Boolean get() = importes.containsKey(null)

    fun subtotal(medio: MedioPago?): BigDecimal = importes[medio] ?: BigDecimal.ZERO
}
