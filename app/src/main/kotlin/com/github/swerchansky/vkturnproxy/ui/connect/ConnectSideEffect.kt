package com.github.swerchansky.vkturnproxy.ui.connect

sealed class ConnectSideEffect {
    data class ShowError(val message: String) : ConnectSideEffect()
    data class ShowFavoriteNameDialog(val address: String) : ConnectSideEffect()
    data class ShowClipboardDetected(val link: String) : ConnectSideEffect()
    object RequestClipboardPaste : ConnectSideEffect()
    object OpenConnectionDetail : ConnectSideEffect()
    object RequestNotificationPermission : ConnectSideEffect()
    object ShowQuickOptions : ConnectSideEffect()
}
