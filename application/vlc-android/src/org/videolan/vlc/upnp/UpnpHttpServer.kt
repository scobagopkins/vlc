/*
 * UpnpHttpServer.kt — HTTP/1.1 front end for the DLNA renderer (design doc §4.1/§4.2).
 *
 * Responsibilities:
 *  - bind the first free port in 8188..8199 (never 8088/8080/8081; SO_REUSEADDR before
 *    bind so a quick restart works), expose the actual port, optional ephemeral fallback;
 *  - accept loop on Dispatchers.IO with a hard cap of HTTP_MAX_CONNECTIONS;
 *  - parse HTTP/1.1 with CRLFCRLF, header/body caps, Content-Length + chunked bodies,
 *    Expect: 100-continue, case-insensitive headers, duplicate SOAPACTION -> 400;
 *  - exact-path routing (never startsWith/contains): description, SCPDs, SOAP control,
 *    GENA subscribe/unsubscribe, /debug, /debug.json, /self-test, / ;
 *  - always answer with Content-Length + Connection: close and close the socket;
 *  - delegate SOAP parsing to UpnpSoapHandler and eventing to UpnpGena.
 *
 * No third-party libraries: java.net + java.io only. Every network operation runs off
 * the main thread; nothing here calls PlaybackService directly (SOAP reads the
 * UpnpPlaybackSnapshot and enqueues bridge operations).
 *
 * Public API:
 *   bind(): Int                (throws PortUnavailableException)  — call before SSDP
 *   port: Int                  actual bound port
 *   install(gena, soap)        (+ overload install(gena, bridge, soap))
 *   attachSsdp(ssdp)           enables the SSDP loopback probe in /self-test
 *   start() / close() / selfTest()
 */
package org.videolan.vlc.upnp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UpnpHttpServer(
    private val scope: CoroutineScope,
    private val context: Context,
    private val description: UpnpDeviceDescription
) {

    /** Convenience constructor used by some wiring paths (UDN only). */
    constructor(scope: CoroutineScope, context: Context, udn: String) :
        this(scope, context, UpnpDeviceDescription(context, udn))

    /** Thrown when 8188..8199 (and the ephemeral fallback) are all unavailable. */
    class PortUnavailableException(message: String) : IOException(message)

    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val connectionJobs = CopyOnWriteArrayList<Job>()
    private val triedPorts = CopyOnWriteArrayList<Int>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var gena: UpnpGena? = null

    @Volatile
    private var soap: UpnpSoapHandler? = null

    @Volatile
    private var ssdp: SsdpServer? = null

    @Volatile
    private var boundPort = 0

    val port: Int
        get() = boundPort

    // ------------------------------------------------------------------ setup

    fun install(gena: UpnpGena, soap: UpnpSoapHandler) {
        this.gena = gena
        this.soap = soap
    }

    /** Design-doc call shape: the bridge is an object so only the presence is used. */
    @Suppress("UNUSED_PARAMETER")
    fun install(gena: UpnpGena, bridge: UpnpPlaybackBridge, soap: UpnpSoapHandler) = install(gena, soap)

    fun attachSsdp(ssdp: SsdpServer?) {
        this.ssdp = ssdp
    }

    /**
     * Binds the first usable port. Deterministic order: preferred port from settings,
     * then 8188..8199, then the OS-assigned ephemeral port as a last resort.
     * SO_REUSEADDR is set BEFORE bind (legacy defect D3/D8).
     */
    fun bind(): Int {
        serverSocket?.let { return boundPort }
        val candidates = LinkedHashSet<Int>()
        val preferred = UpnpConstants.preferredPort(context)
        if (preferred in UpnpConstants.HTTP_PORTS && preferred !in UpnpConstants.FORBIDDEN_PORTS) {
            candidates.add(preferred)
        }
        UpnpConstants.HTTP_PORTS.forEach { if (it !in UpnpConstants.FORBIDDEN_PORTS) candidates.add(it) }

        for (candidate in candidates) {
            triedPorts.add(candidate)
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate), UpnpConstants.HTTP_ACCEPT_BACKLOG)
                socket.soTimeout = 0
                serverSocket = socket
                boundPort = socket.localPort
                UpnpDebug.setPortAttempts(triedPorts.toList())
                UpnpDebug.log("Http", "bound HTTP server on port $boundPort")
                UpnpDebug.inc("http.bind.ok")
                return boundPort
            } catch (e: IOException) {
                UpnpDebug.inc("http.bindFail.$candidate")
                UpnpDebug.log("Http", "HTTP port $candidate unavailable: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (UpnpConstants.HTTP_EPHEMERAL_FALLBACK) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0), UpnpConstants.HTTP_ACCEPT_BACKLOG)
                socket.soTimeout = 0
                serverSocket = socket
                boundPort = socket.localPort
                UpnpDebug.setPortAttempts(triedPorts.toList())
                UpnpDebug.log("Http", "ephemeral fallback bound on port $boundPort")
                UpnpDebug.inc("http.bind.ephemeral")
                return boundPort
            } catch (e: IOException) {
                UpnpDebug.log("Http", "ephemeral bind failed", e)
            }
        }
        UpnpDebug.setPortAttempts(triedPorts.toList())
        throw PortUnavailableException("No free HTTP port in ${UpnpConstants.HTTP_PORTS}")
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = serverSocket ?: run {
            running.set(false)
            UpnpDebug.log("Http", "start() called without bind()")
            return
        }
        UpnpDebug.log("Http", "accept loop starting on port ${socket.localPort}")
        val runtime = UpnpDebug.runtime()
        UpnpDebug.setRuntime(
            runtime.copy(
                running = true,
                httpPort = socket.localPort,
                udn = description.udn,
                friendlyName = description.friendlyName,
                startedAt = if (runtime.startedAt > 0L) runtime.startedAt else System.currentTimeMillis()
            )
        )
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
    }

    fun close() {
        // Also cleans up when bind() succeeded but start() was never reached.
        if (!running.compareAndSet(true, false) && serverSocket == null) return
        UpnpDebug.log("Http", "closing HTTP server")
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        acceptJob?.cancel()
        acceptJob = null
        connectionJobs.forEach { it.cancel() }
        connectionJobs.clear()
        serverSocket = null
        UpnpDebug.setRuntime(UpnpDebug.runtime().copy(running = false))
    }

    // ------------------------------------------------------------------ accept loop

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                if (running.get()) UpnpDebug.log("Http", "accept failed: ${e.message}")
                break
            } catch (e: Throwable) {
                if (running.get()) UpnpDebug.log("Http", "accept failed", e)
                break
            }
            UpnpDebug.inc("http.accept")
            if (activeConnections.incrementAndGet() > UpnpConstants.HTTP_MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                UpnpDebug.inc("http.rejected")
                UpnpDebug.log("Http", "connection cap reached, rejecting ${client.inetAddress?.hostAddress}")
                try {
                    client.soTimeout = 1_000
                    writeRawResponse(
                        client.getOutputStream(),
                        STATUS_SERVICE_UNAVAILABLE,
                        TEXT_PLAIN,
                        "Too many connections\n".toByteArray(Charsets.UTF_8),
                        emptyList(),
                        false
                    )
                } catch (_: Throwable) {
                }
                closeQuietly(client)
                continue
            }
            val job = scope.launch(Dispatchers.IO) {
                try {
                    handleConnection(client)
                } finally {
                    activeConnections.decrementAndGet()
                    closeQuietly(client)
                }
            }
            connectionJobs.add(job)
            connectionJobs.removeAll { !it.isActive }
        }
        UpnpDebug.log("Http", "accept loop finished")
    }

    // ------------------------------------------------------------------ connection handling

    private fun handleConnection(socket: Socket) {
        var headOnly = false
        var method = ""
        try {
            socket.soTimeout = UpnpConstants.HTTP_SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 4096)
            val output = BufferedOutputStream(socket.getOutputStream(), 4096)

            val headBytes = readHead(input)
            if (headBytes == null) {
                UpnpDebug.inc("http.error")
                return
            }
            val head = String(headBytes, Charsets.ISO_8859_1)
            val parsed = parseHead(head)
            if (parsed == null) {
                UpnpDebug.inc("http.error")
                respond(output, STATUS_BAD_REQUEST, TEXT_PLAIN, "Bad request\n".toByteArray(Charsets.UTF_8), emptyList(), false)
                return
            }
            method = parsed.method
            headOnly = method == "HEAD"

            // 100-continue must be written BEFORE reading the body.
            val expectContinue = parsed.headers["expect"]?.contains("100-continue", ignoreCase = true) == true
            if (expectContinue) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }

            val body = readBody(input, parsed.headers, method) ?: run {
                UpnpDebug.inc("http.error")
                respond(output, STATUS_BAD_REQUEST, TEXT_PLAIN, "Bad body\n".toByteArray(Charsets.UTF_8), emptyList(), false)
                return
            }

            UpnpDebug.inc("http.request")
            UpnpDebug.inc("http.path.${sanitizeCounterKey(parsed.path)}")
            if (parsed.path != UpnpConstants.DEBUG_HTML && parsed.path != UpnpConstants.DEBUG_JSON) {
                UpnpDebug.log("Http", "$method ${parsed.path} from ${socket.inetAddress?.hostAddress}")
            }

            when (method) {
                "GET", "HEAD" -> handleGet(parsed, output, socket, headOnly)
                "POST" -> handlePost(parsed, body, output, headOnly)
                "SUBSCRIBE", "UNSUBSCRIBE" -> handleGena(parsed, output, headOnly)
                else -> respond(
                    output,
                    STATUS_METHOD_NOT_ALLOWED,
                    TEXT_PLAIN,
                    "Method not allowed\n".toByteArray(Charsets.UTF_8),
                    listOf("Allow" to "GET, HEAD, POST, SUBSCRIBE, UNSUBSCRIBE"),
                    headOnly
                )
            }
            output.flush()
        } catch (e: HeaderTooLargeException) {
            UpnpDebug.inc("http.error")
            UpnpDebug.log("Http", "header too large from ${socket.inetAddress?.hostAddress}")
            trapAndRespond(socket, STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE, "Request header too large\n")
        } catch (e: BodyTooLargeException) {
            UpnpDebug.inc("http.error")
            UpnpDebug.log("Http", "body too large from ${socket.inetAddress?.hostAddress}")
            trapAndRespond(socket, STATUS_PAYLOAD_TOO_LARGE, "Request body too large\n")
        } catch (e: SocketTimeoutException) {
            UpnpDebug.inc("http.timeout")
            UpnpDebug.log("Http", "socket timeout from ${socket.inetAddress?.hostAddress}")
            trapAndRespond(socket, STATUS_REQUEST_TIMEOUT, "Request timed out\n")
        } catch (e: IOException) {
            UpnpDebug.inc("http.error")
            UpnpDebug.log("Http", "connection error: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            UpnpDebug.inc("http.error")
            UpnpDebug.log("Http", "unexpected connection error", e)
        }
    }

    private fun trapAndRespond(socket: Socket, status: String, message: String) {
        try {
            val output = BufferedOutputStream(socket.getOutputStream(), 512)
            respond(output, status, TEXT_PLAIN, message.toByteArray(Charsets.UTF_8), emptyList(), false)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ routing

    private fun handleGet(parsed: ParsedRequest, output: BufferedOutputStream, socket: Socket, headOnly: Boolean) {
        val path = parsed.path
        when (path) {
            UpnpConstants.DESC, UpnpConstants.DESC_ALIAS ->
                respond(output, STATUS_OK, TEXT_XML, description.buildBytes(), emptyList(), headOnly)

            UpnpConstants.SCPD_AVT, UpnpConstants.SCPD_RC, UpnpConstants.SCPD_CM -> {
                val scpd = UpnpScpd.forPath(path)
                if (scpd == null) {
                    respond(output, STATUS_NOT_FOUND, TEXT_PLAIN, "Not found\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                } else {
                    respond(output, STATUS_OK, TEXT_XML, scpd.toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            UpnpConstants.ROOT, UpnpConstants.INDEX_HTML -> {
                if (!isAllowedDebugSource(socket)) {
                    respond(output, STATUS_FORBIDDEN, TEXT_PLAIN, "Forbidden\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                } else {
                    respond(output, STATUS_OK, TEXT_HTML, UpnpDebug.html(condensed = true).toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            UpnpConstants.DEBUG_HTML -> {
                if (!isAllowedDebugSource(socket)) {
                    respond(output, STATUS_FORBIDDEN, TEXT_PLAIN, "Forbidden\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                } else {
                    UpnpDebug.log("Http", "GET /debug")
                    respond(output, STATUS_OK, TEXT_HTML, UpnpDebug.html().toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            UpnpConstants.DEBUG_JSON -> {
                if (!isAllowedDebugSource(socket)) {
                    respond(output, STATUS_FORBIDDEN, TEXT_PLAIN, "Forbidden\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                } else {
                    respond(output, STATUS_OK, APPLICATION_JSON, UpnpDebug.json().toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            UpnpConstants.SELF_TEST -> {
                if (!isAllowedDebugSource(socket)) {
                    respond(output, STATUS_FORBIDDEN, TEXT_PLAIN, "Forbidden\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                } else {
                    respond(output, STATUS_OK, TEXT_PLAIN, selfTest().toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            in UpnpConstants.SERVICE_BY_CONTROL_PATH -> respond(
                output,
                STATUS_METHOD_NOT_ALLOWED,
                TEXT_PLAIN,
                "Use POST\n".toByteArray(Charsets.UTF_8),
                listOf("Allow" to "POST"),
                headOnly
            )

            in UpnpConstants.SERVICE_BY_EVENT_PATH -> respond(
                output,
                STATUS_METHOD_NOT_ALLOWED,
                TEXT_PLAIN,
                "Use SUBSCRIBE/UNSUBSCRIBE\n".toByteArray(Charsets.UTF_8),
                listOf("Allow" to "SUBSCRIBE, UNSUBSCRIBE"),
                headOnly
            )

            else -> respond(output, STATUS_NOT_FOUND, TEXT_PLAIN, "Not found\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
        }
    }

    private fun handlePost(parsed: ParsedRequest, body: String, output: BufferedOutputStream, headOnly: Boolean) {
        val path = parsed.path
        when {
            path in UpnpConstants.SERVICE_BY_CONTROL_PATH -> {
                val handler = soap
                if (handler == null) {
                    UpnpDebug.inc("http.error")
                    respond(output, STATUS_SERVICE_UNAVAILABLE, TEXT_PLAIN, "SOAP handler unavailable\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                    return
                }
                val result = try {
                    handler.handle(path, parsed.headers, body)
                } catch (e: Throwable) {
                    UpnpDebug.inc("http.error")
                    UpnpDebug.log("Http", "SOAP handler crashed for $path", e)
                    null
                }
                if (result == null) {
                    respond(
                        output,
                        STATUS_INTERNAL_ERROR,
                        TEXT_XML,
                        SoapResult.fault(UpnpError.ActionFailed).body.toByteArray(Charsets.UTF_8),
                        emptyList(),
                        headOnly
                    )
                } else {
                    respond(output, statusLine(result.httpStatus), result.contentType, result.body.toByteArray(Charsets.UTF_8), emptyList(), headOnly)
                }
            }

            path in UpnpConstants.SERVICE_BY_EVENT_PATH -> respond(
                output,
                STATUS_METHOD_NOT_ALLOWED,
                TEXT_PLAIN,
                "Use SUBSCRIBE/UNSUBSCRIBE\n".toByteArray(Charsets.UTF_8),
                listOf("Allow" to "SUBSCRIBE, UNSUBSCRIBE"),
                headOnly
            )

            path == UpnpConstants.SELF_TEST -> {
                // POST is accepted as well: the debug page uses a button (GET link for simplicity).
                respond(output, STATUS_OK, TEXT_PLAIN, selfTest().toByteArray(Charsets.UTF_8), emptyList(), headOnly)
            }

            path in listOf(
                UpnpConstants.DESC,
                UpnpConstants.DESC_ALIAS,
                UpnpConstants.SCPD_AVT,
                UpnpConstants.SCPD_RC,
                UpnpConstants.SCPD_CM,
                UpnpConstants.ROOT,
                UpnpConstants.INDEX_HTML,
                UpnpConstants.DEBUG_HTML,
                UpnpConstants.DEBUG_JSON
            ) -> respond(
                output,
                STATUS_METHOD_NOT_ALLOWED,
                TEXT_PLAIN,
                "Use GET\n".toByteArray(Charsets.UTF_8),
                listOf("Allow" to "GET, HEAD"),
                headOnly
            )

            else -> respond(output, STATUS_NOT_FOUND, TEXT_PLAIN, "Not found\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
        }
    }

    private fun handleGena(parsed: ParsedRequest, output: BufferedOutputStream, headOnly: Boolean) {
        val path = parsed.path
        if (path !in UpnpConstants.SERVICE_BY_EVENT_PATH) {
            respond(output, STATUS_NOT_FOUND, TEXT_PLAIN, "Not found\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
            return
        }
        val manager = gena
        if (manager == null) {
            UpnpDebug.inc("http.error")
            respond(output, STATUS_SERVICE_UNAVAILABLE, TEXT_PLAIN, "Eventing unavailable\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
            return
        }
        val result = try {
            if (parsed.method == "SUBSCRIBE") {
                manager.handleSubscribe(path, parsed.headers)
            } else {
                manager.handleUnsubscribe(path, parsed.headers)
            }
        } catch (e: Throwable) {
            UpnpDebug.inc("http.error")
            UpnpDebug.log("Http", "GENA handler crashed for $path", e)
            null
        }
        if (result == null) {
            respond(output, STATUS_INTERNAL_ERROR, TEXT_PLAIN, "Eventing error\n".toByteArray(Charsets.UTF_8), emptyList(), headOnly)
            return
        }
        UpnpDebug.inc("http.gena.${parsed.method.lowercase(Locale.US)}")
        respond(output, statusLine(result.status), null, result.body, result.headers, headOnly)
    }

    // ------------------------------------------------------------------ /self-test

    /**
     * Runs the three on-demand checks of design doc §8.3 (blocking, ~2-8 s):
     *  1) SSDP multicast loopback probe (if SsdpServer is attached);
     *  2) HTTP self-fetch of /description.xml on every advertised interface;
     *  3) HEAD probe of the current media URI (if any) to reveal "renderer accepted the
     *     URI but the TV cannot reach the media server".
     */
    fun selfTest(): String {
        val sb = StringBuilder(1024)
        sb.append("VLC DLNA renderer self-test\n")
        sb.append("===========================\n\n")

        try {
            val ssdpServer = ssdp
            if (ssdpServer == null) {
                sb.append("SSDP multicast loopback: SKIPPED — SSDP server not attached\n\n")
            } else {
                sb.append(ssdpServer.multicastSelfTest()).append('\n')
            }
        } catch (e: Throwable) {
            sb.append("SSDP multicast loopback: FAIL — ${e.javaClass.simpleName}: ${e.message}\n\n")
        }

        val runtime = UpnpDebug.runtime()
        val httpPort = if (runtime.httpPort > 0) runtime.httpPort else boundPort
        val udn = if (runtime.udn.isNotEmpty()) runtime.udn else description.udn
        val interfaces = UpnpDebug.interfaces()
        if (interfaces.isEmpty()) {
            sb.append("HTTP self-fetch: SKIPPED — no interface published\n\n")
        } else if (httpPort <= 0) {
            sb.append("HTTP self-fetch: SKIPPED — HTTP server not bound\n\n")
        } else {
            sb.append("HTTP self-fetch (GET /description.xml):\n")
            interfaces.forEach { iface ->
                val url = "http://${iface.ip}:$httpPort${UpnpConstants.DESC}"
                sb.append("  ").append(url).append(" -> ")
                sb.append(httpSelfFetch(url, udn)).append('\n')
            }
            sb.append('\n')
        }

        val mediaUri = UpnpPlaybackSnapshot.uri.orEmpty()
        if (mediaUri.isEmpty()) {
            sb.append("Media URL probe: SKIPPED — no current media URI\n")
        } else {
            sb.append("Media URL probe: ").append(mediaUri).append(" -> ")
            sb.append(mediaProbe(mediaUri)).append('\n')
        }
        UpnpDebug.log("Http", "self-test executed")
        return sb.toString()
    }

    private fun httpSelfFetch(url: String, udn: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val body = if (code == 200) {
                conn.inputStream.use { stream -> readLimited(stream, 8192) }
            } else {
                ""
            }
            when {
                code != 200 -> "FAIL (HTTP $code)"
                !contentType.contains("xml", ignoreCase = true) -> "FAIL (content-type $contentType)"
                !body.contains(udn) -> "FAIL (UDN missing from description)"
                else -> "PASS (200, ${body.length} bytes)"
            }
        } catch (e: Throwable) {
            "FAIL (${e.javaClass.simpleName}: ${e.message})"
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun mediaProbe(uri: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL(uri).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.setRequestProperty("Connection", "close")
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val length = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (code in 200..399) {
                "PASS (HTTP $code${if (length >= 0) ", length $length" else ""})"
            } else {
                "FAIL (HTTP $code) — the TV cannot reach the media server (VLAN/ACL/expired URL?)"
            }
        } catch (e: Throwable) {
            "FAIL (${e.javaClass.simpleName}: ${e.message}) — the TV cannot reach the media URL"
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ HTTP parsing

    private fun readHead(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        var p1 = -1
        var p2 = -1
        var p3 = -1
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (out.size() == 0) return null
                throw IOException("connection closed inside request head")
            }
            out.write(value)
            if (out.size() > UpnpConstants.HTTP_HEADER_MAX_BYTES) throw HeaderTooLargeException()
            // CRLFCRLF ...
            if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && value == '\n'.code) {
                return out.toByteArray()
            }
            // ... or LF-only line endings (tolerated).
            if (p1 == '\n'.code && value == '\n'.code) {
                return out.toByteArray()
            }
            p3 = p2
            p2 = p1
            p1 = value
        }
    }

    private fun parseHead(head: String): ParsedRequest? {
        val normalized = head.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.isEmpty()) return null
        val requestLine = lines[0].trim()
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val rawTarget = parts[1]
        val headers = LinkedHashMap<String, String>()
        var sawSoapAction = false
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            if (name == "soapaction") {
                if (sawSoapAction) return null // duplicate SOAPACTION -> 400
                sawSoapAction = true
            }
            if (!headers.containsKey(name)) headers[name] = value
        }
        val path = decodePath(normalizePath(rawTarget)) ?: return null
        return ParsedRequest(method, path, headers)
    }

    private fun normalizePath(target: String): String {
        var value = target
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            val schemeEnd = value.indexOf("://")
            val slash = value.indexOf('/', schemeEnd + 3)
            value = if (slash >= 0) value.substring(slash) else "/"
        }
        value = value.substringBefore('?')
        value = value.substringBefore('#')
        return if (value.isEmpty()) "/" else value
    }

    private fun decodePath(path: String): String? {
        return try {
            // '+' is a literal in a path, not a space.
            URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>, method: String): String? {
        val transferEncoding = headers["transfer-encoding"]
        val rawLength = headers["content-length"]
        val isPost = method == "POST"

        if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
            val bytes = readChunked(input)
            return decodeBody(bytes)
        }

        val length: Long = when {
            rawLength == null -> if (isPost) return null else 0L
            else -> rawLength.trim().toLongOrNull() ?: return null
        }
        if (length < 0) return null
        if (length > UpnpConstants.HTTP_BODY_MAX_BYTES) throw BodyTooLargeException()
        if (length == 0L) return ""
        val buffer = ByteArray(length.toInt())
        readFully(input, buffer, 0, buffer.size)
        return decodeBody(buffer)
    }

    private fun decodeBody(bytes: ByteArray): String {
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            offset = 3
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    private fun readChunked(input: BufferedInputStream): ByteArray {
        val result = ByteArrayOutputStream(1024)
        while (true) {
            val line = readLine(input, 128) ?: throw IOException("chunked body: EOF in size line")
            val sizeToken = line.substringBefore(';').trim()
            val size = sizeToken.toIntOrNull(16) ?: throw IOException("chunked body: invalid size '$sizeToken'")
            if (size == 0) {
                // Consume optional trailers until the blank line.
                var guard = 0
                while (guard++ < 32) {
                    val trailer = readLine(input, 512) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (result.size().toLong() + size > UpnpConstants.HTTP_BODY_MAX_BYTES) throw BodyTooLargeException()
            val chunk = ByteArray(size)
            readFully(input, chunk, 0, size)
            result.write(chunk)
            readLine(input, 8) // trailing CRLF after the chunk data
        }
        return result.toByteArray()
    }

    private fun readLine(input: InputStream, maxLength: Int): String? {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val value = input.read()
            if (value == -1) return if (out.size() == 0) null else String(out.toByteArray(), Charsets.ISO_8859_1)
            if (value == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            if (out.size() >= maxLength) throw IOException("line too long")
            out.write(value)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var readTotal = 0
        while (readTotal < length) {
            val count = input.read(buffer, offset + readTotal, length - readTotal)
            if (count == -1) throw IOException("unexpected EOF in body")
            readTotal += count
        }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream(1024)
        val buffer = ByteArray(1024)
        var total = 0
        while (total < max) {
            val count = input.read(buffer)
            if (count == -1) break
            out.write(buffer, 0, count)
            total += count
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ response writing

    private fun respond(
        output: BufferedOutputStream,
        status: String,
        contentType: String?,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>>,
        headOnly: Boolean
    ) {
        writeResponse(output, status, contentType, body, extraHeaders, headOnly)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: String,
        contentType: String?,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>>,
        headOnly: Boolean
    ) {
        val header = buildString(512) {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Date: ").append(httpDate()).append("\r\n")
            append("Server: ").append(UpnpConstants.SERVER_HEADER).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun writeRawResponse(
        stream: OutputStream,
        status: String,
        contentType: String?,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>>,
        headOnly: Boolean
    ) {
        val output = if (stream is BufferedOutputStream) stream else BufferedOutputStream(stream, 512)
        writeResponse(output, status, contentType, body, extraHeaders, headOnly)
    }

    private fun httpDate(): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    // ------------------------------------------------------------------ security helpers

    /**
     * /debug, /debug.json, / and /self-test are restricted to private (RFC1918),
     * loopback or link-local sources: the renderer must never be a public debug surface.
     */
    private fun isAllowedDebugSource(socket: Socket): Boolean {
        val address = socket.inetAddress ?: return false
        return isPrivateAddress(address)
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
    }

    // ------------------------------------------------------------------ internal types

    private class ParsedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    private class HeaderTooLargeException : IOException("request header too large")

    private class BodyTooLargeException : IOException("request body too large")

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    /** GENA responses carry an Int status; translate it into an HTTP status line. */
    private fun statusLine(code: Int): String = when (code) {
        200 -> STATUS_OK
        400 -> STATUS_BAD_REQUEST
        403 -> STATUS_FORBIDDEN
        404 -> STATUS_NOT_FOUND
        405 -> STATUS_METHOD_NOT_ALLOWED
        412 -> "412 Precondition Failed"
        500 -> STATUS_INTERNAL_ERROR
        503 -> STATUS_SERVICE_UNAVAILABLE
        else -> if (code in 200..299) "$code OK" else "$code Error"
    }

    private fun sanitizeCounterKey(path: String): String {
        val sb = StringBuilder(path.length)
        for (ch in path) {
            sb.append(if (ch.isLetterOrDigit() || ch == '/' || ch == '.' || ch == '_') ch else '_')
        }
        return sb.toString().trim('/').ifEmpty { "root" }
    }

    private companion object {
        const val STATUS_OK = "200 OK"
        const val STATUS_BAD_REQUEST = "400 Bad Request"
        const val STATUS_FORBIDDEN = "403 Forbidden"
        const val STATUS_NOT_FOUND = "404 Not Found"
        const val STATUS_METHOD_NOT_ALLOWED = "405 Method Not Allowed"
        const val STATUS_REQUEST_TIMEOUT = "408 Request Timeout"
        const val STATUS_PAYLOAD_TOO_LARGE = "413 Payload Too Large"
        const val STATUS_REQUEST_HEADER_FIELDS_TOO_LARGE = "431 Request Header Fields Too Large"
        const val STATUS_INTERNAL_ERROR = "500 Internal Server Error"
        const val STATUS_SERVICE_UNAVAILABLE = "503 Service Unavailable"

        const val TEXT_XML = "text/xml; charset=\"utf-8\""
        const val TEXT_HTML = "text/html; charset=\"utf-8\""
        const val TEXT_PLAIN = "text/plain; charset=\"utf-8\""
        const val APPLICATION_JSON = "application/json; charset=\"utf-8\""
    }
}
