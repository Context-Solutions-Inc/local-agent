package com.contextsolutions.mobileagent.link

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort discovery of this desktop's primary LAN IPv4 (PR #57) for the
 * pairing QR. Walks the up, non-loopback, non-virtual interfaces and returns the
 * first site-local IPv4 (192.168/10/172.16–31), preferring those over any other
 * routable IPv4. Returns null when there's no LAN address (the QR then shows a
 * "connect to a network" hint instead).
 */
object LanAddress {
    fun primaryIpv4(): String? {
        val candidates = mutableListOf<String>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                // Skip common virtual/container bridges.
                if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) return host // prefer site-local
                        candidates += host
                    }
                }
            }
        }
        return candidates.firstOrNull()
    }
}
