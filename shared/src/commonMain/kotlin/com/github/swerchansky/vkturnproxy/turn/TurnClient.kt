package com.github.swerchansky.vkturnproxy.turn

enum class RequestedAddressFamily(val code: Int) {
    IPv4(0x01),
    IPv6(0x02),
}
