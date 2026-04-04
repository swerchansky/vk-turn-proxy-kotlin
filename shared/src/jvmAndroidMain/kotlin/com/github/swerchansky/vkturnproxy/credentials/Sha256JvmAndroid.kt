package com.github.swerchansky.vkturnproxy.credentials

import java.security.MessageDigest

internal actual fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
