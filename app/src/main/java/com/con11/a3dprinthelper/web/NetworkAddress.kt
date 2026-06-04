package com.con11.a3dprinthelper.web

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {
    fun localHttpUrl(port: Int): String {
        val address = localIpv4Address() ?: return "http://手机IP:$port/"
        return "http://$address:$port/"
    }

    private fun localIpv4Address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        return interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { !it.startsWith("127.") && !it.startsWith("169.254.") }
    }
}
