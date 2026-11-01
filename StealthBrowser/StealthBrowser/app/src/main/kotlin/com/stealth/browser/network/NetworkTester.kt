package com.stealth.browser.network

import com.stealth.browser.engine.core.NetworkConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class ConnectionTestResult(
    val ok: Boolean,
    val latencyMs: Long,
    val egressIp: String?,
    val error: String?,
)

@Singleton
class NetworkTester @Inject constructor() {

    /**
     * REAL connectivity test: performs an HTTPS request through the proxy.
     * The egress IP echo endpoint lets the caller verify traffic left via
     * the proxy (not the device) — this is what enforces "no silent fallback":
     * the request is made with an OkHttp client whose ONLY route is the proxy,
     * so a dead proxy produces an error, not a direct connection.
     */
    fun testProxy(cfg: NetworkConfig): ConnectionTestResult {
        val proxy = cfg.proxy
            ?: return ConnectionTestResult(ok = true, latencyMs = 0, egressIp = null, error = null) // direct: still reachable but nothing to route
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .proxy(java.net.Proxy(
                when (proxy.type) {
                    ProxyType.HTTP -> java.net.Proxy.Type.HTTP
                    ProxyType.HTTPS -> java.net.Proxy.Type.HTTP
                    ProxyType.SOCKS5 -> java.net.Proxy.Type.SOCKS
                    else -> java.net.Proxy.Type.HTTP
                },
                InetSocketAddress(proxy.host, proxy.port),
            ))
        if (proxy.username != null && proxy.password != null) {
            val authenticator = okhttp3.Authenticator { _, response ->
                val credential = okhttp3.Credentials.basic(proxy.username, String(proxy.password))
                response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
            builder.proxyAuthenticator(authenticator)
        }
        val started = System.nanoTime()
        return try {
            val response: Response = builder.build()
                .newCall(
                    Request.Builder()
                        .url("https://ifconfig.me/ip")
                        .header("User-Agent", "curl/8")
                        .build(),
                ).execute()
            val latency = (System.nanoTime() - started) / 1_000_000
            val ip = response.body?.string()?.trim()
            response.close()
            ConnectionTestResult(
                ok = response.isSuccessful || ip?.isNotBlank() == true,
                latencyMs = latency,
                egressIp = ip,
                error = null,
            )
        } catch (e: IOException) {
            ConnectionTestResult(ok = false, latencyMs = -1, egressIp = null, error = e.message)
        }
    }

    /**
     * DoH/DoT provider reachability probe using plain HTTPS to each provider's
     * trace endpoint (Cloudflare / Google). Does NOT install engine DoH — see
     * ENGINE_CAPABILITIES.md for the honest DoH story on the current backing.
     */
    fun testSecureDns(providerTraceUrl: String): ConnectionTestResult {
        val client = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(8)).build()
        val started = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(providerTraceUrl).build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                ConnectionTestResult(
                    ok = res.isSuccessful,
                    latencyMs = (System.nanoTime() - started) / 1_000_000,
                    egressIp = Regex("ip=([^\n]+)").find(body)?.groupValues?.get(1),
                    error = if (res.isSuccessful) null else "HTTP ${res.code}",
                )
            }
        } catch (e: IOException) {
            ConnectionTestResult(false, -1, null, e.message)
        }
    }
}
