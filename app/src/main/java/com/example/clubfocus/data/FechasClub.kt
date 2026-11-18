package com.example.clubfocus.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

internal object FechasClub {
    val formato: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")
    private val estricto = formato.withResolverStyle(ResolverStyle.STRICT)

    fun parsear(texto: String): LocalDate = try {
        LocalDate.parse(texto, formato)
    } catch (_: DateTimeParseException) {
        LocalDate.parse(texto)
    }

    fun nacimientoValido(texto: String): Boolean = try {
        Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}").matches(texto) &&
            !LocalDate.parse(texto, estricto).isAfter(LocalDate.now())
    } catch (_: DateTimeParseException) {
        false
    }
}
