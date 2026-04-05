package com.github.swerchansky.vkturnproxy.credentials.vk

import java.util.UUID

internal actual fun generateUUID(): String = UUID.randomUUID().toString()
