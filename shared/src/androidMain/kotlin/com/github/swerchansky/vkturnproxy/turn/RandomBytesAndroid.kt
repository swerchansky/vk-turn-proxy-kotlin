package com.github.swerchansky.vkturnproxy.turn

import java.security.SecureRandom

internal actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
