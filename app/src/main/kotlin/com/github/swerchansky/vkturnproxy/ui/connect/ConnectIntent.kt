package com.github.swerchansky.vkturnproxy.ui.connect

sealed class ConnectIntent {
    data class LinkChanged(val value: String) : ConnectIntent()
    data class PeerChanged(val value: String) : ConnectIntent()
    object ConnectClicked : ConnectIntent()
    object DisconnectClicked : ConnectIntent()
    data class AddFavoriteConfirmed(val name: String, val address: String) : ConnectIntent()
    data class FavoriteRemoved(val address: String) : ConnectIntent()
    data class FavoriteSelected(val address: String) : ConnectIntent()
    data class ClipboardLinkDetected(val link: String) : ConnectIntent()
    data class ClipboardLinkAccepted(val link: String) : ConnectIntent()
    object PasteFromClipboard : ConnectIntent()
    object DetailCardTapped : ConnectIntent()
    object StarButtonClicked : ConnectIntent()
    object QuickOptionsRequested : ConnectIntent()
    data class QuickOptionsNConnectionsSet(val n: Int) : ConnectIntent()
}
