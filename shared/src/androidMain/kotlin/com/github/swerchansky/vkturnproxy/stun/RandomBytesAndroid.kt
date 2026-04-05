package com.github.swerchansky.vkturnproxy.stun

import java.security.SecureRandom

internal actual fun randomBytes(size: Int): ByteArray =
    ByteArray(size).also { SecureRandom().nextBytes(it) }
