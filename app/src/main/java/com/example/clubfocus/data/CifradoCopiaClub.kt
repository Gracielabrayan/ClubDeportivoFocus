package com.example.clubfocus.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CifradoCopiaClub {
    private val firma = "CLUBFOCUS\u0001".toByteArray(Charsets.US_ASCII)
    private const val BYTES_SAL = 16
    private const val BYTES_IV = 12
    private const val BITS_ETIQUETA = 128
    private const val ITERACIONES_V1 = 600_000
    const val MAX_BYTES = 16 * 1024 * 1024
    private val aleatorio = SecureRandom()

    fun cifrar(datos: ByteArray, contrasena: String): ByteArray {
        val sal = ByteArray(BYTES_SAL).also(aleatorio::nextBytes)
        val iv = ByteArray(BYTES_IV).also(aleatorio::nextBytes)
        val cabecera = firma + sal + iv
        if (datos.size > MAX_BYTES - cabecera.size - BITS_ETIQUETA / 8) throw CopiaNoValida()
        return cabecera + iniciar(Cipher.ENCRYPT_MODE, contrasena, sal, iv, cabecera).doFinal(datos)
    }

    fun descifrar(entrada: InputStream, contrasena: String): ByteArray {
        val datos = leerArchivo(entrada)
        val inicioIv = firma.size + BYTES_SAL
        val inicioContenido = inicioIv + BYTES_IV
        if (datos.size < inicioContenido + BITS_ETIQUETA / 8 ||
            !datos.copyOfRange(0, firma.size).contentEquals(firma)
        ) throw CopiaNoValida()

        val cabecera = datos.copyOfRange(0, inicioContenido)
        val sal = datos.copyOfRange(firma.size, inicioIv)
        val iv = datos.copyOfRange(inicioIv, inicioContenido)
        return try {
            iniciar(Cipher.DECRYPT_MODE, contrasena, sal, iv, cabecera)
                .doFinal(datos, inicioContenido, datos.size - inicioContenido)
        } catch (_: AEADBadTagException) {
            throw CopiaNoValida()
        }
    }

    private fun iniciar(modo: Int, contrasena: String, sal: ByteArray, iv: ByteArray, cabecera: ByteArray): Cipher {
        val clave = Contrasenas.derivar(contrasena, sal, ITERACIONES_V1)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(modo, SecretKeySpec(clave, "AES"), GCMParameterSpec(BITS_ETIQUETA, iv))
                updateAAD(cabecera)
            }
        } finally {
            clave.fill(0)
        }
    }

    private fun leerArchivo(entrada: InputStream): ByteArray {
        val salida = ByteArrayOutputStream()
        val bloque = ByteArray(8192)
        while (true) {
            val leidos = entrada.read(bloque)
            if (leidos < 0) return salida.toByteArray()
            if (salida.size() + leidos > MAX_BYTES) throw CopiaNoValida()
            salida.write(bloque, 0, leidos)
        }
    }
}

internal class CopiaNoValida : IOException()
