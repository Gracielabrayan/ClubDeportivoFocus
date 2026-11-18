package com.example.clubfocus.data.entity

import java.math.BigDecimal

data class Tarifas(val mensualCentavos: Long, val diariaCentavos: Long) {
    fun monto(tipo: TipoCuota): Double = BigDecimal.valueOf(
        if (tipo == TipoCuota.MENSUAL) mensualCentavos else diariaCentavos, 2
    ).toDouble()

    companion object {
        const val MAX_CENTAVOS = 99_999_999_999L

        fun parsearCentavos(texto: String): Long? {
            val importe = texto.trim().replace(',', '.')
            if (!Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(importe)) return null
            return importe.toBigDecimal().movePointRight(2).longValueExact()
                .takeIf { it in 1..MAX_CENTAVOS }
        }
    }
}
