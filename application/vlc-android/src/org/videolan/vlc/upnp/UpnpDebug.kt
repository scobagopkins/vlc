/*
 * UpnpDebug.kt — diagnostics for the DLNA renderer: thread-safe counters, a bounded
 * ring buffer of events, a runtime status snapshot and the /debug + /debug.json
 * documents (design doc §8).
 *
 * Everything is inside the object (no top-level declarations) so this file cannot
 * collide with other files in the same package.
 *
 * Threading: all mutating methods are safe from any thread (ConcurrentHashMap + a
 * single monitor for the ring buffer). HTML/JSON builders take a stable copy first.
 */
package org.videolan.vlc.upnp

import android.content.Context
import android.os.Build
import android.util.Log
import org.videolan.vlc.BuildConfig
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object UpnpDebug {

    const val TAG = "VLC/UpnpRenderer"

    private const val HISTORY_SIZE = 500
    private const val MAX_LAYER = "upnp"

    /** One log line; seq is monotonic for stable ordering in /debug. */
    data class Event(val seq: Long, val timestamp: Long, val layer: String, val message: String)

    /** One network interface as reported by SsdpServer (used by /debug and the self-test). */
    data class InterfaceInfo(
        val name: String,
        val ip: String,
        val prefixLength: Int = 0,
        val up: Boolean = true,
        val ssdpSocket: Boolean = true,
        val aliveTx: Long = 0L,
        val location: String = ""
    ) {
        val label: String
            get() = if (prefixLength > 0) "$name/$ip/$prefixLength" else "$name/$ip"
    }

    /** Coarse renderer state published by the service/controller. */
    data class RuntimeStatus(
        val running: Boolean = false,
        val httpPort: Int = 0,
        val ssdpPort: Int = UpnpConstants.SSDP_PORT,
        val udn: String = "",
        val friendlyName: String = "",
        val bootId: Int = 0,
        val startedAt: Long = 0L,
        val lastError: String? = null,
        val wakeLockHeld: Boolean = false,
        val multicastLockHeld: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val portAttempts: List<Int> = emptyList()
    )

    private val seq = AtomicLong(0)
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val lock = Any()
    private val events = ArrayDeque<Event>(HISTORY_SIZE + 1)

    @Volatile
    private var runtimeStatus = RuntimeStatus()

    @Volatile
    private var interfaceList: List<InterfaceInfo> = emptyList()

    @Volatile
    private var subscriptionLines: List<String> = emptyList()

    private var appContext: Context? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ------------------------------------------------------------------ lifecycle

    /** Called once from the service; currently only keeps an application context for /debug. */
    fun install(context: Context) {
        appContext = context.applicationContext
        log(MAX_LAYER, "UpnpDebug installed")
    }

    fun applicationContext(): Context? = appContext

    // ------------------------------------------------------------------ logging

    fun log(message: String) = log(MAX_LAYER, message, null)

    fun log(message: String, tr: Throwable?) = log(MAX_LAYER, message, tr)

    /**
     * Records one event and writes it to logcat under "VLC/UpnpRenderer/<layer>".
     * Never throws: diagnostics must not break the renderer.
     */
    fun log(layer: String, message: String, tr: Throwable? = null) {
        val now = System.currentTimeMillis()
        val text = buildString {
            append(message)
            if (tr != null) {
                append(" [")
                append(tr.javaClass.simpleName)
                append(": ")
                append(tr.message ?: "")
                append(']')
            }
        }
        try {
            // Default layer lines use the exact tag requested by the task; per-layer
            // lines append the layer so `adb logcat` can filter without /debug.
            val logTag = if (layer == MAX_LAYER) TAG else "$TAG/$layer"
            Log.i(logTag, text)
        } catch (_: Throwable) {
            // Log is always available on Android; ignore defensively.
        }
        val event = Event(seq.incrementAndGet(), now, layer, text)
        synchronized(lock) {
            if (events.size >= HISTORY_SIZE) events.removeFirst()
            events.addLast(event)
        }
        inc("log.$layer")
    }

    fun ssdp(message: String, tr: Throwable? = null) = log("Ssdp", message, tr)
    fun http(message: String, tr: Throwable? = null) = log("Http", message, tr)
    fun soap(message: String, tr: Throwable? = null) = log("Soap", message, tr)
    fun gena(message: String, tr: Throwable? = null) = log("Gena", message, tr)
    fun playback(message: String, tr: Throwable? = null) = log("Playback", message, tr)

    fun recentLogs(max: Int = 200): List<String> = eventsCopy().takeLast(max.coerceAtLeast(0)).map {
        "${formatTime(it.timestamp)}  [${it.layer}] ${it.message}"
    }

    fun layerLogs(layer: String, max: Int = 100): List<String> = eventsCopy()
        .filter { it.layer.equals(layer, ignoreCase = true) }
        .takeLast(max.coerceAtLeast(0))
        .map { "${formatTime(it.timestamp)}  ${it.message}" }

    private fun eventsCopy(): List<Event> = synchronized(lock) { ArrayList(events) }

    // ------------------------------------------------------------------ counters

    fun inc(name: String, delta: Long = 1L): Long {
        val value = counters.getOrPut(name) { AtomicLong(0) }
        return value.addAndGet(delta)
    }

    fun counter(name: String): Long = counters[name]?.get() ?: 0L

    /** Stable, sorted copy for /debug and /debug.json. */
    fun counters(): Map<String, Long> = TreeMap<String, Long>().apply {
        counters.forEach { (k, v) -> put(k, v.get()) }
    }

    // ------------------------------------------------------------------ status publishing

    fun setRuntime(status: RuntimeStatus) {
        runtimeStatus = status
    }

    fun runtime(): RuntimeStatus = runtimeStatus

    fun setInterfaces(list: List<InterfaceInfo>) {
        interfaceList = ArrayList(list)
    }

    fun interfaces(): List<InterfaceInfo> = interfaceList

    fun setSubscriptions(lines: List<String>) {
        subscriptionLines = ArrayList(lines)
    }

    fun subscriptions(): List<String> = subscriptionLines

    fun setPortAttempts(ports: List<Int>) {
        runtimeStatus = runtimeStatus.copy(portAttempts = ArrayList(ports))
    }

    // ------------------------------------------------------------------ /debug documents

    fun html(condensed: Boolean = false): String {
        val sb = StringBuilder(8192)
        sb.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">")
        if (!condensed) sb.append("<meta http-equiv=\"refresh\" content=\"5\">")
        sb.append("<title>VLC DLNA renderer — debug</title><style>")
        sb.append("body{font-family:monospace;background:#101418;color:#d8e0e8;margin:16px;line-height:1.45}")
        sb.append("h1{font-size:18px;color:#7fd1ff}h2{font-size:14px;color:#9fe870;margin-top:20px;border-bottom:1px solid #2a3440;padding-bottom:2px}")
        sb.append("table{border-collapse:collapse;margin:6px 0}td,th{padding:2px 10px 2px 0;text-align:left;vertical-align:top}")
        sb.append("th{color:#8fa4b8;font-weight:normal}.ok{color:#9fe870}.fail{color:#ff7b72}.dim{color:#8fa4b8}")
        sb.append("pre{white-space:pre-wrap;margin:4px 0;background:#161c22;padding:6px;border-radius:4px}")
        sb.append("a{color:#7fd1ff}</style></head><body>")

        sb.append("<h1>VLC for Android — DLNA renderer debug</h1>")
        sb.append("<div class=\"dim\">GET ").append(esc(UpnpConstants.DEBUG_HTML))
            .append(" · <a href=\"").append(esc(UpnpConstants.DEBUG_JSON)).append("\">")
            .append(esc(UpnpConstants.DEBUG_JSON)).append("</a>")
            .append(" · <a href=\"/\">/</a></div>")

        val rt = runtimeStatus
        sb.append("<h2>BUILD</h2><table>")
        row(sb, "app", "VLC ${esc(BuildConfig.VLC_VERSION_NAME)} (code ${BuildConfig.VLC_VERSION_CODE})")
        row(sb, "android", "Android ${esc(Build.VERSION.RELEASE)} (API ${Build.VERSION.SDK_INT})")
        row(sb, "device", esc("${Build.MANUFACTURER} ${Build.MODEL}"))
        sb.append("</table>")

        sb.append("<h2>STATE</h2><table>")
        rowClass(sb, "renderer", if (rt.running) "running" else "stopped", if (rt.running) "ok" else "fail")
        row(sb, "http port", if (rt.httpPort > 0) rt.httpPort.toString() else "—")
        row(sb, "ssdp port", rt.ssdpPort.toString())
        row(sb, "udn", esc(rt.udn.ifEmpty { "—" }))
        row(sb, "friendlyName", esc(rt.friendlyName.ifEmpty { "—" }))
        row(sb, "bootid / configid", "${rt.bootId} / ${UpnpConstants.CONFIG_ID}")
        row(sb, "started", if (rt.startedAt > 0) esc("${formatDate(rt.startedAt)} (${uptimeText(rt.startedAt)})") else "—")
        if (rt.portAttempts.isNotEmpty()) row(sb, "ports tried", esc(rt.portAttempts.joinToString(", ")))
        rt.lastError?.let { rowClass(sb, "last error", esc(it), "fail") }
        rowClass(sb, "wake lock", if (rt.wakeLockHeld) "held" else "not held", if (rt.wakeLockHeld) "ok" else "dim")
        rowClass(sb, "multicast lock", if (rt.multicastLockHeld) "held" else "not held", if (rt.multicastLockHeld) "ok" else "dim")
        rowClass(sb, "notifications", if (rt.notificationsEnabled) "enabled" else "disabled", if (rt.notificationsEnabled) "ok" else "dim")
        sb.append("</table>")

        if (!condensed) {
            sb.append("<h2>INTERFACES (").append(interfaceList.size).append(")</h2>")
            if (interfaceList.isEmpty()) {
                sb.append("<div class=\"fail\">no IPv4 interface published</div>")
            } else {
                sb.append("<table><tr><th>iface</th><th>state</th><th>ssdp</th><th>alive tx</th><th>LOCATION</th></tr>")
                interfaceList.forEach { i ->
                    sb.append("<tr><td>").append(esc(i.label)).append("</td><td class=\"")
                        .append(if (i.up) "ok" else "fail").append("\">").append(if (i.up) "up" else "down")
                        .append("</td><td class=\"").append(if (i.ssdpSocket) "ok" else "fail").append("\">")
                        .append(if (i.ssdpSocket) "socket ok" else "socket failed")
                        .append("</td><td>").append(i.aliveTx).append("</td><td>")
                        .append(esc(i.location.ifEmpty { "—" })).append("</td></tr>")
                }
                sb.append("</table>")
            }
        }

        val c = counters()
        sb.append("<h2>COUNTERS</h2><table>")
        if (c.isEmpty()) {
            sb.append("<tr><td class=\"dim\">no counters yet</td></tr>")
        } else {
            c.forEach { (key, value) -> row(sb, esc(key), value.toString()) }
        }
        sb.append("</table>")

        if (!condensed) {
            sb.append("<h2>SUBSCRIPTIONS (").append(subscriptionLines.size).append(")</h2>")
            if (subscriptionLines.isEmpty()) {
                sb.append("<div class=\"dim\">none</div>")
            } else {
                subscriptionLines.forEach { sb.append("<pre>").append(esc(it)).append("</pre>") }
            }

            sb.append("<h2>RECENT EVENTS (last 60)</h2>")
            val recent = eventsCopy().takeLast(60)
            if (recent.isEmpty()) {
                sb.append("<div class=\"dim\">none</div>")
            } else {
                sb.append("<pre>")
                recent.forEach {
                    sb.append(esc(formatTime(it.timestamp))).append("  [").append(esc(it.layer))
                        .append("] ").append(esc(it.message)).append('\n')
                }
                sb.append("</pre>")
            }
        } else {
            sb.append("<div class=\"dim\">condensed view — full diagnostics at <a href=\"").append(esc(UpnpConstants.DEBUG_HTML))
                .append("\">").append(esc(UpnpConstants.DEBUG_HTML)).append("</a></div>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    fun json(): String {
        val sb = StringBuilder(4096)
        val rt = runtimeStatus
        sb.append('{')
        sb.append("\"build\":{")
        field(sb, "app", "VLC ${BuildConfig.VLC_VERSION_NAME}")
        sb.append(',')
        field(sb, "versionCode", BuildConfig.VLC_VERSION_CODE.toString(), quote = false)
        sb.append(',')
        field(sb, "android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.append(',')
        field(sb, "device", "${Build.MANUFACTURER} ${Build.MODEL}")
        sb.append("},")

        sb.append("\"runtime\":{")
        field(sb, "running", rt.running.toString(), quote = false)
        sb.append(',')
        field(sb, "httpPort", rt.httpPort.toString(), quote = false)
        sb.append(',')
        field(sb, "ssdpPort", rt.ssdpPort.toString(), quote = false)
        sb.append(',')
        field(sb, "udn", rt.udn)
        sb.append(',')
        field(sb, "friendlyName", rt.friendlyName)
        sb.append(',')
        field(sb, "bootId", rt.bootId.toString(), quote = false)
        sb.append(',')
        field(sb, "startedAt", rt.startedAt.toString(), quote = false)
        sb.append(',')
        field(sb, "lastError", rt.lastError ?: "")
        sb.append(',')
        field(sb, "wakeLockHeld", rt.wakeLockHeld.toString(), quote = false)
        sb.append(',')
        field(sb, "multicastLockHeld", rt.multicastLockHeld.toString(), quote = false)
        sb.append(',')
        field(sb, "notificationsEnabled", rt.notificationsEnabled.toString(), quote = false)
        sb.append("},")

        sb.append("\"interfaces\":[")
        interfaceList.forEachIndexed { index, i ->
            if (index > 0) sb.append(',')
            sb.append('{')
            field(sb, "name", i.name)
            sb.append(',')
            field(sb, "ip", i.ip)
            sb.append(',')
            field(sb, "prefixLength", i.prefixLength.toString(), quote = false)
            sb.append(',')
            field(sb, "up", i.up.toString(), quote = false)
            sb.append(',')
            field(sb, "ssdpSocket", i.ssdpSocket.toString(), quote = false)
            sb.append(',')
            field(sb, "aliveTx", i.aliveTx.toString(), quote = false)
            sb.append(',')
            field(sb, "location", i.location)
            sb.append('}')
        }
        sb.append("],")

        sb.append("\"counters\":{")
        var first = true
        counters().forEach { (key, value) ->
            if (!first) sb.append(',')
            first = false
            field(sb, key, value.toString(), quote = false)
        }
        sb.append("},")

        sb.append("\"subscriptions\":[")
        subscriptionLines.forEachIndexed { index, line ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(line))
        }
        sb.append("],")

        sb.append("\"events\":[")
        eventsCopy().takeLast(100).forEachIndexed { index, e ->
            if (index > 0) sb.append(',')
            sb.append('{')
            field(sb, "seq", e.seq.toString(), quote = false)
            sb.append(',')
            field(sb, "time", formatTime(e.timestamp))
            sb.append(',')
            field(sb, "layer", e.layer)
            sb.append(',')
            field(sb, "message", e.message)
            sb.append('}')
        }
        sb.append("],")

        field(sb, "now", System.currentTimeMillis().toString(), quote = false)
        sb.append('}')
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun row(sb: StringBuilder, name: String, value: String) {
        sb.append("<tr><td>").append(name).append("</td><td>").append(value).append("</td></tr>")
    }

    private fun rowClass(sb: StringBuilder, name: String, value: String, css: String) {
        sb.append("<tr><td>").append(name).append("</td><td class=\"").append(css).append("\">")
            .append(value).append("</td></tr>")
    }

    private fun field(sb: StringBuilder, name: String, value: String, quote: Boolean = true) {
        sb.append(jsonString(name)).append(':')
        if (quote) sb.append(jsonString(value)) else sb.append(value)
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun esc(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun formatTime(ts: Long): String = synchronized(timeFormat) { timeFormat.format(Date(ts)) }

    private fun formatDate(ts: Long): String = synchronized(dateFormat) { dateFormat.format(Date(ts)) }

    private fun uptimeText(startedAt: Long): String {
        if (startedAt <= 0) return "—"
        val total = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h}h ${m}m ${s}s ago"
    }
}
