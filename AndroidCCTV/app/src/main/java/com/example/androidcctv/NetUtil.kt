package com.example.androidcctv

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    /**
     * 인터페이스 열거는 생각보다 비싸고(특히 VPN 이 붙으면 더 늘어난다) 상태 조회마다 불리므로
     * 잠깐 캐시해 둔다. IP 가 바뀌어도 최대 이 시간만큼만 늦게 반영된다.
     */
    private const val TTL_MS = 15_000L

    private val lock = Object()
    private var cached: List<String> = emptyList()
    private var cachedAt = 0L

    /** Wi-Fi·이더넷·VPN·USB 테더링 등에서 얻은 IPv4 주소 목록(루프백 제외). */
    fun localIpv4(): List<String> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (cachedAt != 0L && now - cachedAt < TTL_MS) return cached
        }
        val fresh = scan()
        synchronized(lock) {
            cached = fresh
            cachedAt = now
        }
        return fresh
    }

    fun primaryIp(): String = localIpv4().firstOrNull() ?: "127.0.0.1"

    /** 네트워크가 바뀐 직후 등 즉시 다시 읽어야 할 때 */
    fun invalidate() {
        synchronized(lock) { cachedAt = 0L }
    }

    private fun scan(): List<String> {
        val out = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        out.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (e: Exception) {
            // 네트워크 정보를 못 읽으면 빈 목록
        }
        // 192.168.x / 10.x 같은 사설망 주소를 앞으로
        return out.sortedByDescending { it.startsWith("192.168.") || it.startsWith("10.") }
    }
}
