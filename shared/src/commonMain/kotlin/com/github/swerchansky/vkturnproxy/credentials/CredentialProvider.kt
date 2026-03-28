package com.github.swerchansky.vkturnproxy.credentials

import com.github.swerchansky.vkturnproxy.turn.TurnCredentials

interface CredentialProvider {
    suspend fun getCredentials(link: String): TurnCredentials
}
