package com.github.swerchansky.vkturnproxy.stun

import java.security.SecureRandom

private val rng = SecureRandom()

internal actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }
