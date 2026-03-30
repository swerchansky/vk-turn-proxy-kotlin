package com.github.swerchansky.vkturnproxy.turn

import java.security.SecureRandom

private val rng = SecureRandom()

internal actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }
