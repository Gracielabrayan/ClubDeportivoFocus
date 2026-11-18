package com.example.clubfocus.data.entity

import java.time.LocalDate

data class CarnetListado(
    val id: Int,
    val nroSocio: String,
    val fechaEmision: LocalDate,
    val fechaVencimiento: LocalDate,
    val nombre: String,
    val apellido: String,
    val dni: String
)
