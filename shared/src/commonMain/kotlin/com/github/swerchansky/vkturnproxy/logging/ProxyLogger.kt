package com.github.swerchansky.vkturnproxy.logging

interface ProxyLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, cause: Throwable? = null)
}

object NoOpLogger : ProxyLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String) = Unit
    override fun error(tag: String, message: String, cause: Throwable?) = Unit
}
