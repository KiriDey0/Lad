// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject

data class StationState(val json: JSONObject, val revision: Long, val at: Long)

class StationClient(
    private val api: YandexApi,
    val device: Device,
    private val scope: CoroutineScope,
    private val onState: (JSONObject) -> Unit,
    private val onConnection: (String, String?) -> Unit,
) {
    private val received = MutableStateFlow<StationState?>(null)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var ws: WebSocket? = null
    private var conversation = ""
    private var closed = false
    private var connectionJob: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    suspend fun connect() {
        closed = false
        connectionJob =
            scope.launch {
                var backoff = 1L
                while (isActive && !closed) {
                    try {
                        onConnection("connecting", null)
                        val before = received.value?.revision ?: 0
                        open()
                        withTimeout(15000) {
                            received.filterNotNull().first { it.revision > before }
                        }
                        backoff = 1
                        onConnection("connected", null)
                        while (isActive && !closed && ws != null) delay(1000)
                    } catch (e: CancellationException) {
                        if (e !is TimeoutCancellationException) throw e
                        onConnection("disconnected", "Станция не ответила. Переподключаюсь…")
                    } catch (e: Exception) {
                        onConnection("disconnected", e.message ?: "Станция недоступна")
                    }
                    if (!closed) {
                        delay(backoff * 1000)
                        backoff = (backoff * 2).coerceAtMost(30)
                    }
                }
            }
        withTimeout(16000) { received.filterNotNull().first() }
    }

    private suspend fun open() {
        conversation = api.stationToken(device)
        val certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(device.certificate.byteInputStream()) as X509Certificate
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw java.security.cert.CertificateException("Client TLS not supported")
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (
                        chain.isEmpty() ||
                            !MessageDigest.isEqual(
                                fingerprint,
                                MessageDigest.getInstance("SHA-256").digest(chain[0].encoded),
                            )
                    )
                        throw java.security.cert.CertificateException(
                            "Сертификат Станции не совпадает с аккаунтом"
                        )
                }
            }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val host = localIp(device.host)
        val address = if (host.contains(':')) "[$host]" else host
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, trust)
                .hostnameVerifier { hostname, _ -> hostname == host || hostname == address }
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        val connected = CompletableDeferred<Unit>()
        val connectionId = generation.incrementAndGet()
        ws =
            client.newWebSocket(
                Request.Builder().url("wss://$address:${device.port}/").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (closed || connectionId != generation.get()) {
                            webSocket.cancel()
                            return
                        }
                        ws = webSocket
                        connected.complete(Unit)
                        send(JSONObject().put("command", "softwareVersion"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (connectionId != generation.get()) return
                        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                        pending.remove(j.text("requestId"))?.let {
                            if (j.text("status", "SUCCESS").uppercase() in listOf("SUCCESS", "OK"))
                                it.complete(j)
                            else
                                it.completeExceptionally(
                                    IllegalStateException("Станция отклонила команду")
                                )
                        }
                        j.optJSONObject("state")?.let { s ->
                            received.value =
                                StationState(
                                    s,
                                    (received.value?.revision ?: 0) + 1,
                                    SystemClock.elapsedRealtime(),
                                )
                            scope.launch {
                                onConnection("connected", null)
                                onState(s)
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (connectionId != generation.get()) return
                        ws = null
                        pending.values.forEach {
                            it.completeExceptionally(
                                IllegalStateException("Соединение со Станцией потеряно")
                            )
                        }
                        pending.clear()
                        connected.completeExceptionally(
                            IllegalStateException("Не удалось подключиться к Станции")
                        )
                        scope.launch {
                            onConnection("disconnected", "Станция недоступна. Переподключаюсь…")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (connectionId == generation.get()) ws = null
                    }
                },
            )
        withTimeout(12000) { connected.await() }
    }

    private fun send(payload: JSONObject, id: String = UUID.randomUUID().toString()) {
        check(
            ws?.send(
                JSONObject()
                    .put("conversationToken", conversation)
                    .put("id", id)
                    .put("payload", payload)
                    .put("sentTime", System.currentTimeMillis())
                    .toString()
            ) == true
        ) {
            "Станция не подключена"
        }
    }

    suspend fun command(payload: JSONObject): JSONObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            send(payload, id)
            return withTimeout(7000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun waitAfter(
        revision: Long,
        timeout: Long = 8000,
        test: (JSONObject) -> Boolean,
    ) =
        withTimeout(timeout) {
            received.filterNotNull().first { it.revision > revision && test(it.json) }.json
        }

    suspend fun play(track: Track, position: Long, paused: Boolean): JSONObject {
        var revision = received.value?.revision ?: 0
        command(JSONObject().put("command", "playMusic").put("id", track.id).put("type", "track"))
        fun ready(s: JSONObject) =
            s.optJSONObject("playerState")?.let {
                it.text("id").substringBefore(':') == track.id.substringBefore(':') &&
                    it.optDouble("duration") > 0
            } == true
        var confirmed =
            waitAfter(revision) {
                ready(it) &&
                    it.optBoolean("playing") &&
                    (it.optJSONObject("playerState")?.optDouble("progress") ?: 100.0) < 5
            }
        if (position > 0) {
            for (attempt in 0..1) {
                revision = received.value?.revision ?: 0
                command(JSONObject().put("command", "rewind").put("position", position / 1000.0))
                try {
                    confirmed =
                        waitAfter(revision, 4500) {
                            ready(it) &&
                                kotlin.math.abs(
                                    (it.optJSONObject("playerState")?.optDouble("progress")
                                        ?: 0.0) - position / 1000.0
                                ) < 3
                        }
                    break
                } catch (e: TimeoutCancellationException) {
                    if (attempt == 1)
                        throw IllegalStateException("Станция не подтвердила позицию трека")
                }
            }
        }
        if (paused) {
            revision = received.value?.revision ?: 0
            command(JSONObject().put("command", "stop"))
            confirmed = waitAfter(revision, 4500) { ready(it) && !it.optBoolean("playing") }
        }
        return confirmed
    }

    fun close() {
        closed = true
        generation.incrementAndGet()
        connectionJob?.cancel()
        ws?.close(1000, "Client closed")
        ws = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}

class StationDiscovery(private val context: Context) {
    suspend fun discover(): Map<String, Triple<String, Int, String>> {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("lad-discovery").apply { setReferenceCounted(false) }
        val found = ConcurrentHashMap<String, Triple<String, Int, String>>()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {}

                override fun onDiscoveryStopped(type: String) {}

                override fun onStartDiscoveryFailed(type: String, error: Int) {}

                override fun onStopDiscoveryFailed(type: String, error: Int) {}

                override fun onServiceLost(service: NsdServiceInfo) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val id =
                                    info.attributes["deviceId"]?.toString(Charsets.UTF_8).orEmpty()
                                @Suppress("DEPRECATION") val host = info.host?.hostAddress.orEmpty()
                                val platform =
                                    info.attributes["platform"]?.toString(Charsets.UTF_8).orEmpty()
                                if (id.isNotBlank() && runCatching { localIp(host) }.isSuccess)
                                    found[id] = Triple(host, info.port, platform)
                            }
                        },
                    )
                }
            }
        try {
            lock.acquire()
            manager.discoverServices("_yandexio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(4500)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            if (lock.isHeld) lock.release()
        }
        return found.toMap()
    }
}
