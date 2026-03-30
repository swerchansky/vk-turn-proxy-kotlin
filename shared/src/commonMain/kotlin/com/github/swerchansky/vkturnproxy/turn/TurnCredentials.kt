package com.github.swerchansky.vkturnproxy.turn

data class TurnCredentials(
    val username: String,
    val password: String,
    /** host:port, e.g. "turn.vk.com:3478" */
    val address: String,
)
