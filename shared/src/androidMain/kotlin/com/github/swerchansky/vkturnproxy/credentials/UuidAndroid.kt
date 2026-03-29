package com.github.swerchansky.vkturnproxy.credentials

import java.util.UUID

internal actual fun generateUUID(): String = UUID.randomUUID().toString()
