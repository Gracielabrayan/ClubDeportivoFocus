package com.example.clubfocus.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object Contrasenas {
    private const val FORMATO = "pbkdf2-sha256-v1"
    private const val ITERACIONES = 600_000
    private const val BYTES_SAL = 16
    private const val BYTES_HASH = 32
    private val aleatorio = SecureRandom()

    fun esValidaParaAlta(contrasena: String): Boolean = contrasena.length in 12..128 && contrasena.isNotBlank()

    fun guardar(contrasena: String): String {
        val sal = ByteArray(BYTES_SAL).also(aleatorio::nextBytes)
        val hash = derivar(contrasena, sal)
        return "$FORMATO:${codificar(sal)}:${codificar(hash)}"
    }

    fun coincide(contrasena: String, guardada: String): Boolean {
        val partes = guardada.split(':', limit = 3)
        if (partes.size != 3 || partes[0] != FORMATO) return false

        return try {
            val sal = Base64.decode(partes[1], Base64.NO_WRAP)
            val hash = Base64.decode(partes[2], Base64.NO_WRAP)
            sal.size == BYTES_SAL && hash.size == BYTES_HASH &&
                MessageDigest.isEqual(hash, derivar(contrasena, sal))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun codificar(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    internal fun derivar(contrasena: String, sal: ByteArray, iteraciones: Int = ITERACIONES): ByteArray {
        val caracteres = contrasena.toCharArray()
        val especificacion = PBEKeySpec(caracteres, sal, iteraciones, BYTES_HASH * Byte.SIZE_BITS)
        caracteres.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(especificacion).encoded
        } finally {
            especificacion.clearPassword()
        }
    }
}
