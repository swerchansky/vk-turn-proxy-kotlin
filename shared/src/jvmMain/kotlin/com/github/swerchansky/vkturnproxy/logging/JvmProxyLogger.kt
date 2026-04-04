package com.github.swerchansky.vkturnproxy.logging

import java.util.logging.Level
import java.util.logging.Logger

class JvmProxyLogger(name: String) : ProxyLogger {
    private val log = Logger.getLogger(name)

    override fun debug(tag: String, message: String) = log.fine("[$tag] $message")
    override fun info(tag: String, message: String) = log.info("[$tag] $message")
    override fun warn(tag: String, message: String) = log.warning("[$tag] $message")
    override fun error(tag: String, message: String, cause: Throwable?) =
        log.log(Level.SEVERE, "[$tag] $message", cause)
}
