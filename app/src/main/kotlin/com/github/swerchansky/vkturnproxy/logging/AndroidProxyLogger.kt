package com.github.swerchansky.vkturnproxy.logging

import android.util.Log

class AndroidProxyLogger(
    private val onUiLog: (String) -> Unit,
) : ProxyLogger {

    override fun debug(tag: String, message: String) {
        Log.d(tag, message)
        // debug-уровень только в logcat, не засоряем UI
    }

    override fun info(tag: String, message: String) {
        Log.i(tag, message)
        onUiLog(message)
    }

    override fun warn(tag: String, message: String) {
        Log.w(tag, message)
        onUiLog("⚠ $message")
    }

    override fun error(tag: String, message: String, cause: Throwable?) {
        Log.e(tag, message, cause)
        onUiLog("✕ $message")
    }
}
