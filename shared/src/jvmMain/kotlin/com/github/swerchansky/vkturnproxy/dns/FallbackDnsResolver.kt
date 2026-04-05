package com.github.swerchansky.vkturnproxy.dns

import com.github.swerchansky.vkturnproxy.config.TurnProxyConfig
import com.github.swerchansky.vkturnproxy.error.TurnProxyError
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DNS resolver with a fallback list of servers and an in-memory LRU cache.
 *
 * Matches Go's bschaatsbergen/dnsdialer:
 *  - Resolvers: 77.88.8.8, 77.88.8.1, 8.8.8.8, 8.8.4.4, 1.1.1.1 (all :53)
 *  - Strategy: fallback (try each resolver until success)
 *  - Cache: 100 entries, 10-hour TTL
 */
object FallbackDnsResolver {

    val DEFAULT_RESOLVERS = listOf(
        "77.88.8.8:53",
        "77.88.8.1:53",
        "8.8.8.8:53",
        "8.8.4.4:53",
        "1.1.1.1:53",
    )

    private val cache = LruCache<String, CacheEntry>(maxSize = TurnProxyConfig.DNS_CACHE_SIZE)
    private val cacheTtl: Duration = Duration.ofHours(TurnProxyConfig.DNS_CACHE_TTL_HOURS)

    /**
     * Resolves [hostname] to an IPv4 address using the fallback resolver chain.
     * Results are cached for 10 hours.
     */
    fun resolve(hostname: String): InetAddress {
        // Return if already an IP
        runCatching { InetAddress.getByName(hostname).also { if (it.hostName == hostname) return it } }

        cache.get(hostname)?.let { entry ->
            if (!entry.isExpired()) return entry.address
        }

        val address = resolveWithFallback(hostname)
        cache.put(hostname, CacheEntry(address, System.currentTimeMillis() + cacheTtl.toMillis()))
        return address
    }

    private fun resolveWithFallback(hostname: String): InetAddress {
        val resolvers = DEFAULT_RESOLVERS.map { addr ->
            val parts = addr.split(":")
            SimpleResolver(parts[0]).apply {
                port = parts.getOrNull(1)?.toIntOrNull() ?: 53
                setTimeout(Duration.ofSeconds(3))
            }
        }

        var lastError: Exception? = null
        for (resolver in resolvers) {
            try {
                val lookup = Lookup(hostname, Type.A)
                lookup.setResolver(resolver)
                lookup.setCache(null) // we manage our own cache
                val records = lookup.run()
                if (records != null && records.isNotEmpty()) {
                    val aRecord = records[0] as org.xbill.DNS.ARecord
                    return aRecord.address
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw TurnProxyError.DnsResolutionFailed(hostname)
    }
}

private data class CacheEntry(val address: InetAddress, val expiresAt: Long) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
}

private class LruCache<K, V>(private val maxSize: Int) {
    private val lock = ReentrantReadWriteLock()
    private val map = LinkedHashMap<K, V>(maxSize, 0.75f, true)

    fun get(key: K): V? = lock.read { map[key] }

    fun put(key: K, value: V) = lock.write {
        if (map.size >= maxSize) {
            map.remove(map.keys.first())
        }
        map[key] = value
    }
}
