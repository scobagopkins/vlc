/*
 * UpnpGena.kt — GENA eventing for the DLNA renderer (design doc §5).
 *
 * What this replaces (audit P0-1/P1-12): the legacy code answered SUBSCRIBE with a
 * random SID and then forgot it. Here:
 *  - SID -> callback URLs / service / granted timeout / expiry is a real registry;
 *  - a renewal (SUBSCRIBE with SID, no CALLBACK/NT) returns the SAME SID and extends it;
 *  - every new subscription receives an initial NOTIFY (SEQ 0) with the full property
 *    set, ~50 ms after the 200 is flushed;
 *  - transport/URI/volume/mute changes publish LastChange (AVTransport/RenderingControl)
 *    or direct propertyset properties (ConnectionManager), coalesced at most every 250 ms;
 *  - delivery is asynchronous (Dispatchers.IO), per-subscription ordered via a Mutex,
 *    1 s / 4 s / 9 s retry backoff, 3 s connect+read timeouts, dropped after 5
 *    consecutive failed events; expired subscriptions are lazily skipped and swept
 *    every 30 s.
 *
 * Threading: notify*() may be called from HTTP threads or the main-thread sampler; they
 * only merge into a per-subscription pending map and trySend() to a conflated channel.
 * All socket I/O and SEQ bookkeeping happens on a single IO coroutine per subscription.
 */
package org.videolan.vlc.upnp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UpnpGena(
    private val scope: CoroutineScope,
    private val context: Context
) {

    /** Response for the HTTP layer: status code, extra headers (SID/TIMEOUT), body. */
    data class GenaResponse(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0)
    )

    private class Subscription(
        val sid: String,
        val service: String,
        val callbacks: List<String>
    ) {
        val lock = Any()
        val deliveryMutex = Mutex()
        val seq = AtomicInteger(0)
        val pending = LinkedHashMap<String, String>()
        var lastSent: Map<String, String> = emptyMap()
        @Volatile
        var timeoutSec: Int = UpnpConstants.SUB_TIMEOUT_SEC
        @Volatile
        var expiresAt: Long = 0L
        @Volatile
        var lastNotifiedAt: Long = 0L
        @Volatile
        var lastNotifiedOk: Boolean = false
        @Volatile
        var consecutiveFailures: Int = 0
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val lifecycleLock = Any()
    private val active = AtomicBoolean(true)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val jobs = ArrayList<Job>()

    private val callbackPattern = Regex("<([^>]+)>")

    init {
        jobs.add(scope.launch(Dispatchers.IO) { coalescerLoop() })
        jobs.add(scope.launch(Dispatchers.IO) { expiryLoop() })
    }

    // ------------------------------------------------------------------ SUBSCRIBE / UNSUBSCRIBE

    fun handleSubscribe(path: String, headers: Map<String, String>): GenaResponse {
        val service = UpnpConstants.SERVICE_BY_EVENT_PATH[path] ?: return GenaResponse(404)
        val sid = headers["sid"]?.trim()
        val callbackHeader = headers["callback"]
        val nt = headers["nt"]?.trim()
        val requestedTimeout = headers["timeout"]

        if (!sid.isNullOrEmpty()) {
            // Renewal: SID must be the only addressing header.
            if (!callbackHeader.isNullOrEmpty() || !nt.isNullOrEmpty()) {
                UpnpDebug.gena("SUBSCRIBE with SID and CALLBACK/NT -> 400")
                return GenaResponse(400)
            }
            val subscription = subscriptions[sid]
            if (subscription == null) {
                UpnpDebug.gena("SUBSCRIBE renewal for unknown SID $sid -> 412")
                return GenaResponse(412)
            }
            val granted = grantedTimeout(requestedTimeout, subscription.timeoutSec)
            subscription.timeoutSec = granted
            subscription.expiresAt = System.currentTimeMillis() + granted * 1000L
            UpnpDebug.inc("gena.renew")
            UpnpDebug.gena("SUBSCRIBE renewal sid=$sid timeout=${granted}s")
            publishDebug()
            return GenaResponse(
                200,
                listOf("SID" to subscription.sid, "TIMEOUT" to "Second-$granted")
            )
        }

        // New subscription: CALLBACK and NT are mandatory.
        val callbackRaw = callbackHeader
        if (callbackRaw.isNullOrBlank()) {
            UpnpDebug.gena("SUBSCRIBE missing CALLBACK -> 412")
            return GenaResponse(412)
        }
        val ntRaw = nt
        if (ntRaw.isNullOrBlank()) {
            UpnpDebug.gena("SUBSCRIBE missing NT -> 412")
            return GenaResponse(412)
        }
        val ntValue = ntRaw.removeSurrounding("\"").trim()
        if (!ntValue.equals(UpnpConstants.NT_EVENT, ignoreCase = true)) {
            UpnpDebug.gena("SUBSCRIBE bad NT '$ntRaw' -> 412")
            return GenaResponse(412)
        }
        val callbacks = parseCallbacks(callbackRaw)
        if (callbacks.isEmpty()) {
            UpnpDebug.gena("SUBSCRIBE unparsable CALLBACK '$callbackRaw' -> 412")
            return GenaResponse(412)
        }

        val serviceCount = subscriptions.values.count { it.service == service }
        if (serviceCount >= UpnpConstants.MAX_SUBS_PER_SERVICE || subscriptions.size >= UpnpConstants.MAX_SUBS_TOTAL) {
            UpnpDebug.gena("SUBSCRIBE limit reached ($serviceCount/$service) -> 503")
            return GenaResponse(503)
        }

        val granted = grantedTimeout(requestedTimeout, UpnpConstants.SUB_TIMEOUT_SEC)
        val sidValue = "uuid:" + UUID.randomUUID().toString()
        val subscription = Subscription(sidValue, service, callbacks)
        subscription.timeoutSec = granted
        subscription.expiresAt = System.currentTimeMillis() + granted * 1000L
        synchronized(lifecycleLock) { subscriptions[sidValue] = subscription }
        UpnpDebug.inc("gena.subscribe")
        UpnpDebug.gena("SUBSCRIBE new sid=$sidValue service=${shortService(service)} cb=$callbacks timeout=${granted}s")
        publishDebug()

        // Initial NOTIFY: scheduled after the 200 response has been flushed by HTTP.
        scope.launch(Dispatchers.IO) {
            delay(UpnpConstants.INITIAL_NOTIFY_DELAY_MS)
            if (!active.get() || !isActive(subscription)) return@launch
            enqueue(subscription, initialFields(service), initial = true)
        }

        return GenaResponse(
            200,
            listOf("SID" to sidValue, "TIMEOUT" to "Second-$granted")
        )
    }

    fun handleUnsubscribe(path: String, headers: Map<String, String>): GenaResponse {
        if (path !in UpnpConstants.SERVICE_BY_EVENT_PATH) return GenaResponse(404)
        val sid = headers["sid"]?.trim()
        if (sid.isNullOrEmpty()) {
            UpnpDebug.gena("UNSUBSCRIBE without SID -> 400")
            return GenaResponse(400)
        }
        val removed = synchronized(lifecycleLock) { subscriptions.remove(sid) }
        if (removed == null) {
            UpnpDebug.gena("UNSUBSCRIBE unknown SID $sid -> 412")
            return GenaResponse(412)
        }
        UpnpDebug.inc("gena.unsubscribe")
        UpnpDebug.gena("UNSUBSCRIBE sid=$sid")
        publishDebug()
        return GenaResponse(200)
    }

    fun close() {
        if (!active.compareAndSet(true, false)) return
        synchronized(lifecycleLock) { subscriptions.clear() }
        try {
            signal.close()
        } catch (_: Throwable) {
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        UpnpDebug.setSubscriptions(emptyList())
        UpnpDebug.log("Gena", "closed")
    }

    fun subscriptionCount(): Int = subscriptions.size

    /** One line per live subscription for /debug. */
    fun debugLines(): List<String> {
        val now = System.currentTimeMillis()
        return subscriptions.values
            .sortedBy { it.sid }
            .map { sub ->
                val expiresIn = ((sub.expiresAt - now) / 1000L).coerceAtLeast(0L)
                val last = if (sub.lastNotifiedAt == 0L) "never" else if (sub.lastNotifiedOk) "ok" else "failed"
                "${sub.sid}  ${shortService(sub.service)}  cb ${sub.callbacks.firstOrNull() ?: "-"}  " +
                    "expires in ${expiresIn}s  SEQ ${sub.seq.get()}  last NOTIFY $last  failures ${sub.consecutiveFailures}"
            }
    }

    // ------------------------------------------------------------------ producers

    /**
     * Publishes an AVTransport change. [state]/[uri]/[metadata] override the snapshot
     * when the action knows the new value before the 1 Hz sampler observes it.
     */
    fun notifyAvTransport(state: String?, uri: String? = null, metadata: String? = null) {
        if (!active.get()) return
        val fields = avTransportFields(state, uri, metadata)
        subscriptions.values.filter { it.service == UpnpConstants.SERVICE_AVT }.forEach { enqueue(it, fields) }
    }

    fun notifyRenderingControl(volume: Int, mute: Boolean) {
        if (!active.get()) return
        val fields = renderingControlFields(volume, mute)
        subscriptions.values.filter { it.service == UpnpConstants.SERVICE_RC }.forEach { enqueue(it, fields) }
    }

    fun notifyConnectionManager() {
        if (!active.get()) return
        val fields = connectionManagerFields()
        subscriptions.values.filter { it.service == UpnpConstants.SERVICE_CM }.forEach { enqueue(it, fields) }
    }

    // ------------------------------------------------------------------ field builders

    private fun avTransportFields(stateOverride: String?, uriOverride: String?, metadataOverride: String?): Map<String, String> {
        val snapshotState = UpnpPlaybackSnapshot.transportState
        val state = when {
            !stateOverride.isNullOrEmpty() -> stateOverride
            !snapshotState.isNullOrEmpty() -> snapshotState
            else -> "NO_MEDIA_PRESENT"
        }
        val uri = uriOverride ?: UpnpPlaybackSnapshot.uri.orEmpty()
        val metadata = metadataOverride ?: UpnpPlaybackSnapshot.metadata.orEmpty()
        val duration = UpnpActionsAvTransport.formatAvtDuration(UpnpPlaybackSnapshot.durationMs)
        val fields = LinkedHashMap<String, String>(16)
        fields["TransportState"] = state
        fields["TransportStatus"] = "OK"
        fields["CurrentTransportActions"] = UpnpActionsAvTransport.currentTransportActions(state)
        fields["AVTransportURI"] = uri
        fields["AVTransportURIMetaData"] = metadata
        fields["CurrentTrackURI"] = uri
        fields["CurrentTrackMetaData"] = metadata
        fields["CurrentTrackDuration"] = duration
        fields["CurrentMediaDuration"] = duration
        fields["CurrentPlayMode"] = "NORMAL"
        fields["TransportPlaySpeed"] = "1"
        fields["NumberOfTracks"] = if (uri.isEmpty()) "0" else "1"
        fields["CurrentTrack"] = if (uri.isEmpty()) "0" else "1"
        return fields
    }

    private fun renderingControlFields(volume: Int, mute: Boolean): Map<String, String> {
        val clamped = volume.coerceIn(0, 100)
        val fields = LinkedHashMap<String, String>(4)
        fields["PresetNameList"] = "FactoryDefaults"
        fields["Volume"] = clamped.toString()
        fields["Mute"] = if (mute) "1" else "0"
        fields["VolumeDB"] = UpnpActionsRenderingControl.volumeToDb(clamped).toString()
        return fields
    }

    private fun connectionManagerFields(): Map<String, String> {
        val fields = LinkedHashMap<String, String>(3)
        fields["SourceProtocolInfo"] = ""
        fields["SinkProtocolInfo"] = UpnpConstants.SINK_PROTOCOL_INFO
        fields["CurrentConnectionIDs"] = "0"
        return fields
    }

    private fun initialFields(service: String): Map<String, String> = when (service) {
        UpnpConstants.SERVICE_AVT -> avTransportFields(null, null, null)
        UpnpConstants.SERVICE_RC -> renderingControlFields(
            UpnpPlaybackSnapshot.volume.coerceIn(0, 100),
            UpnpPlaybackSnapshot.mute
        )
        UpnpConstants.SERVICE_CM -> connectionManagerFields()
        else -> emptyMap()
    }

    // ------------------------------------------------------------------ coalescing / delivery

    /** Merge the latest full field set into the subscription's pending map (never blocks). */
    private fun enqueue(subscription: Subscription, fields: Map<String, String>, initial: Boolean = false) {
        if (fields.isEmpty()) return
        synchronized(subscription.lock) {
            if (initial) subscription.lastSent = emptyMap()
            subscription.pending.putAll(fields)
        }
        signal.trySend(Unit)
    }

    private suspend fun coalescerLoop() {
        while (active.get()) {
            try {
                signal.receive()
            } catch (_: Throwable) {
                break
            }
            if (!active.get()) break
            delay(UpnpConstants.LASTCHANGE_MIN_INTERVAL_MS)
            subscriptions.values.forEach { subscription ->
                if (!active.get()) return@forEach
                if (!isActive(subscription)) return@forEach
                val fields = synchronized(subscription.lock) {
                    if (subscription.pending.isEmpty()) return@synchronized null
                    val merged = LinkedHashMap(subscription.pending)
                    subscription.pending.clear()
                    merged
                } ?: return@forEach
                val changed = synchronized(subscription.lock) {
                    if (subscription.lastSent.isEmpty()) {
                        fields
                    } else {
                        val diff = LinkedHashMap<String, String>()
                        fields.forEach { (key, value) ->
                            if (subscription.lastSent[key] != value) diff[key] = value
                        }
                        diff
                    }
                }
                if (changed.isEmpty()) return@forEach
                val body = buildNotifyBody(subscription.service, changed)
                if (body == null) {
                    UpnpDebug.inc("gena.notify.fail")
                    return@forEach
                }
                scope.launch(Dispatchers.IO) {
                    subscription.deliveryMutex.withLock {
                        if (!isActive(subscription)) return@withLock
                        val ok = deliverWithRetry(subscription, body)
                        if (ok) {
                            synchronized(subscription.lock) { subscription.lastSent = fields }
                        }
                    }
                }
            }
        }
        UpnpDebug.log("Gena", "coalescer loop finished")
    }

    private suspend fun expiryLoop() {
        while (active.get()) {
            delay(UpnpConstants.SUB_EXPIRY_SWEEP_MS)
            if (!active.get()) break
            val now = System.currentTimeMillis()
            val expired = subscriptions.values.filter { it.expiresAt in 1..now }
            expired.forEach { subscription ->
                synchronized(lifecycleLock) { subscriptions.remove(subscription.sid) }
                UpnpDebug.inc("gena.expired")
                UpnpDebug.gena("subscription ${subscription.sid} expired")
            }
            if (expired.isNotEmpty()) publishDebug()
        }
    }

    private fun isActive(subscription: Subscription): Boolean {
        return active.get() && subscription.expiresAt > System.currentTimeMillis() &&
            subscriptions[subscription.sid] === subscription
    }

    private suspend fun deliverWithRetry(subscription: Subscription, body: ByteArray): Boolean {
        var attempt = 0
        var lastError = ""
        while (attempt < UpnpConstants.NOTIFY_RETRY && active.get()) {
            val seq = subscription.seq.get()
            for (callback in subscription.callbacks) {
                if (deliverOnce(subscription, callback, seq, body)) {
                    subscription.seq.incrementAndGet()
                    subscription.consecutiveFailures = 0
                    subscription.lastNotifiedAt = System.currentTimeMillis()
                    subscription.lastNotifiedOk = true
                    UpnpDebug.inc("gena.notify.tx")
                    return true
                }
                lastError = "callback $callback failed"
            }
            attempt++
            if (attempt < UpnpConstants.NOTIFY_RETRY) {
                UpnpDebug.inc("gena.notify.retry")
                delay(UpnpConstants.NOTIFY_RETRY_BASE_MS * attempt * attempt)
            }
        }
        subscription.consecutiveFailures += 1
        subscription.lastNotifiedAt = System.currentTimeMillis()
        subscription.lastNotifiedOk = false
        UpnpDebug.inc("gena.notify.fail")
        UpnpDebug.gena("NOTIFY for ${subscription.sid} failed after ${subscription.consecutiveFailures} event(s): $lastError")
        if (subscription.consecutiveFailures >= UpnpConstants.NOTIFY_MAX_CONSECUTIVE_FAILURES) {
            synchronized(lifecycleLock) { subscriptions.remove(subscription.sid) }
            UpnpDebug.inc("gena.dropped")
            UpnpDebug.gena("dropping dead subscription ${subscription.sid}")
            publishDebug()
        }
        return false
    }

    private fun deliverOnce(subscription: Subscription, callback: String, seq: Int, body: ByteArray): Boolean {
        val url = try {
            URL(callback)
        } catch (e: Throwable) {
            UpnpDebug.log("Gena", "invalid callback url '$callback'", e)
            return false
        }
        if (!url.protocol.equals("http", ignoreCase = true)) {
            UpnpDebug.gena("unsupported callback scheme '${url.protocol}' (only http is delivered)")
            return false
        }
        val host = url.host ?: return false
        val port = if (url.port > 0) url.port else 80
        val target = (url.path?.ifEmpty { "/" } ?: "/") + (url.query?.let { "?$it" } ?: "")
        return try {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), UpnpConstants.NOTIFY_CONNECT_TIMEOUT_MS)
                socket.soTimeout = UpnpConstants.NOTIFY_READ_TIMEOUT_MS
                socket.tcpNoDelay = true
                val output = BufferedOutputStream(socket.getOutputStream(), 2048)
                val hostHeader = if (url.port > 0) "$host:${url.port}" else host
                val head = buildString(256) {
                    append("NOTIFY ").append(target).append(" HTTP/1.1\r\n")
                    append("HOST: ").append(hostHeader).append("\r\n")
                    append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                    append("CONTENT-LENGTH: ").append(body.size).append("\r\n")
                    append("NT: ").append(UpnpConstants.NT_EVENT).append("\r\n")
                    append("NTS: ").append(UpnpConstants.NTS_PROPCHANGE).append("\r\n")
                    append("SID: ").append(subscription.sid).append("\r\n")
                    append("SEQ: ").append(seq).append("\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                output.write(head.toByteArray(Charsets.ISO_8859_1))
                output.write(body)
                output.flush()
                val responseLine = readLine(BufferedInputStream(socket.getInputStream(), 512), 256)
                val success = responseLine != null &&
                    responseLine.startsWith("HTTP/1.", ignoreCase = true) &&
                    is2xx(responseLine)
                if (!success) UpnpDebug.gena("NOTIFY $target -> ${responseLine ?: "no response"}")
                success
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            UpnpDebug.gena("NOTIFY to $callback failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun is2xx(statusLine: String): Boolean {
        val parts = statusLine.split(' ')
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return code in 200..299
    }

    private fun readLine(input: InputStream, maxLength: Int): String? {
        val buffer = ByteArrayOutputStreamLocal()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.size == 0) null else String(buffer.bytes(), Charsets.ISO_8859_1)
            if (value == '\n'.code) {
                val bytes = buffer.bytes()
                val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            if (buffer.size >= maxLength) return null
            buffer.write(value)
        }
    }

    /** Minimal growable byte buffer (avoids importing ByteArrayOutputStream just for one read). */
    private class ByteArrayOutputStreamLocal {
        private var data = ByteArray(128)
        var size = 0
            private set

        fun write(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value.toByte()
        }

        fun bytes(): ByteArray = data.copyOf(size)
    }

    // ------------------------------------------------------------------ NOTIFY body builders

    /**
     * Body for one NOTIFY. AVTransport/RenderingControl wrap their properties in a single
     * escaped LastChange; ConnectionManager sends properties directly (design §5.6).
     */
    private fun buildNotifyBody(service: String, fields: Map<String, String>): ByteArray? {
        val propertiesXml = when (service) {
            UpnpConstants.SERVICE_AVT -> {
                val lastChange = buildLastChange(UpnpConstants.NS_AVT_LASTCHANGE, fields, rcs = false)
                "<e:property><LastChange>${textEscape(lastChange)}</LastChange></e:property>"
            }
            UpnpConstants.SERVICE_RC -> {
                val lastChange = buildLastChange(UpnpConstants.NS_RCS_LASTCHANGE, fields, rcs = true)
                "<e:property><LastChange>${textEscape(lastChange)}</LastChange></e:property>"
            }
            UpnpConstants.SERVICE_CM -> buildString(1024) {
                fields.forEach { (name, value) ->
                    append("<e:property><").append(name).append('>')
                    append(textEscape(value))
                    append("</").append(name).append("></e:property>")
                }
            }
            else -> return null
        }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<e:propertyset xmlns:e=\"${UpnpConstants.EVENT_NS}\">$propertiesXml</e:propertyset>"
        return body.toByteArray(Charsets.UTF_8)
    }

    /**
     * LastChange payload. AVT and RCS differ only by namespace and the channel attribute
     * on Volume/Mute/VolumeDB. Values are XML-attribute escaped; the whole payload is
     * then escaped as text by the caller.
     */
    private fun buildLastChange(namespace: String, fields: Map<String, String>, rcs: Boolean): String {
        val sb = StringBuilder(512)
        sb.append("<Event xmlns=\"").append(namespace).append("\">")
        sb.append("<InstanceID val=\"0\">")
        fields.forEach { (name, value) ->
            sb.append('<').append(name)
            if (rcs && name in RCS_CHANNEL_FIELDS) sb.append(" channel=\"Master\"")
            sb.append(" val=\"").append(attributeEscape(value)).append("\"/>")
        }
        sb.append("</InstanceID></Event>")
        return sb.toString()
    }

    private fun attributeEscape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun textEscape(value: String): String {
        val sb = StringBuilder(value.length + 32)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun grantedTimeout(rawHeader: String?, fallback: Int): Int {
        if (rawHeader.isNullOrBlank()) return fallback.coerceIn(UpnpConstants.SUB_TIMEOUT_MIN_SEC, UpnpConstants.SUB_TIMEOUT_SEC)
        val value = rawHeader.trim()
        if (value.equals("Second-infinite", ignoreCase = true)) return UpnpConstants.SUB_TIMEOUT_SEC
        val seconds = value.removePrefix("Second-").removePrefix("second-").trim().toIntOrNull() ?: return fallback
        return seconds.coerceIn(UpnpConstants.SUB_TIMEOUT_MIN_SEC, UpnpConstants.SUB_TIMEOUT_SEC)
    }

    private fun parseCallbacks(raw: String): List<String> {
        val result = ArrayList<String>(2)
        callbackPattern.findAll(raw).forEach { match ->
            val candidate = match.groupValues[1].trim()
            if (candidate.isEmpty()) return@forEach
            try {
                val url = URL(candidate)
                val scheme = url.protocol?.lowercase(Locale.US)
                if (scheme == "http" || scheme == "https") {
                    if (scheme == "https") UpnpDebug.gena("https callback accepted but not deliverable: $candidate")
                    result.add(candidate)
                }
            } catch (_: Throwable) {
                UpnpDebug.gena("invalid CALLBACK url: $candidate")
            }
        }
        return result
    }

    private fun shortService(service: String): String = when (service) {
        UpnpConstants.SERVICE_AVT -> "AVT"
        UpnpConstants.SERVICE_RC -> "RC"
        UpnpConstants.SERVICE_CM -> "CM"
        else -> service
    }

    private fun publishDebug() {
        UpnpDebug.setSubscriptions(debugLines())
    }

    private companion object {
        /** RenderingControl variables that carry a channel attribute in LastChange. */
        val RCS_CHANNEL_FIELDS = setOf("Volume", "Mute", "VolumeDB")
    }
}
