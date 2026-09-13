package com.codex.chat.core.update

import java.io.File
import java.security.MessageDigest

object ApkVerifier {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun coincide(esperado: String, real: String): Boolean {
        if (esperado.length != 64 || real.length != 64) return false
        var dif = 0
        for (i in 0 until 64) {
            dif = dif or (esperado[i].lowercaseChar().code xor real[i].lowercaseChar().code)
        }
        return dif == 0
    }
}
