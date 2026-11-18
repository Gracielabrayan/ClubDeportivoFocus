package com.example.clubfocus

import com.example.clubfocus.data.FechasClub
import com.example.clubfocus.data.entity.Promocion
import com.example.clubfocus.data.repository.CuotaRepository
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReglasCuotaTest {
    @Test
    fun rechazaNacimientosImposiblesYFuturosYAdmiteAnioBisiesto() {
        assertFalse(FechasClub.nacimientoValido("31/02/2000"))
        assertFalse(FechasClub.nacimientoValido("29/02/2001"))
        assertFalse(FechasClub.nacimientoValido(LocalDate.now().plusDays(1).format(FechasClub.formato)))
        assertTrue(FechasClub.nacimientoValido("29/02/2000"))
        assertTrue(FechasClub.nacimientoValido(LocalDate.now().format(FechasClub.formato)))
        assertEquals(LocalDate.of(2000, 2, 29), FechasClub.parsear("2000-02-29"))
    }

    @Test
    fun redondeaLaPromocionACentavosAntesDeGuardarElCobro() {
        assertEquals(46750.43, CuotaRepository.calcularMontoFinal(55000.50, Promocion.TRES_CUOTAS), 0.000001)
        assertEquals(18000.01, CuotaRepository.calcularMontoFinal(20000.01, Promocion.SEIS_CUOTAS), 0.000001)
    }
}
