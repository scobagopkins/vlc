/*
 * UpnpConstants.kt — single source of truth for the DLNA/UPnP MediaRenderer.
 *
 * Network/control half of the renderer rewrite (see research/upnp-fix-design.md §1.3).
 * Nothing here touches the UI or PlaybackService; these are pure values + preference
 * helpers shared by SsdpServer, UpnpHttpServer, UpnpSoapHandler, UpnpGena and the
 * service/controller layer.
 *
 * Assumptions (documented because the constants object is the contract):
 *  - The default SharedPreferences file is "<packageName>_preferences", which is exactly
 *    what androidx.preference.PreferenceManager.getDefaultSharedPreferences() uses in the
 *    rest of the app. We read it directly so this module does not need a compile-time
 *    dependency on androidx.preference.
 *  - "port" preference values are read defensively: the settings UI uses a ListPreference
 *    (String) while an older definition used an int, so both are accepted.
 *  - BuildConfig is generated for this module (namespace org.videolan.vlc) and carries
 *    VLC_VERSION_NAME / VLC_VERSION_CODE.
 */
package org.videolan.vlc.upnp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.videolan.vlc.BuildConfig
import java.util.Locale
import java.util.UUID

object UpnpConstants {

    // ------------------------------------------------------------------ SSDP

    const val SSDP_ADDR = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val SSDP_TTL = 4                    // IPv4 TTL; 4 survives one router hop
    const val MAX_AGE_SEC = 1800              // CACHE-CONTROL max-age
    const val ALIVE_INTERVAL_MS = 600_000L    // re-announce every ~1/3 of max-age
    const val ANNOUNCE_DUPLICATES = 3         // send each alive 3x at startup
    const val ANNOUNCE_SPACING_MS = 2_000L
    const val SSDP_RESPONSE_MAX_JITTER_MS = 1_000L
    const val SSDP_SEARCH_RATE_LIMIT = 50     // handled M-SEARCH per second
    const val SSDP_DEDUP_WINDOW_MS = 150L     // same (source, ST) on two sockets

    // ------------------------------------------------------------------ HTTP

    val HTTP_PORTS = 8188..8199               // never 8088, never 8080/8081
    val FORBIDDEN_PORTS = setOf(80, 443, 1900, 5000, 8080, 8081, 8088, 8090, 8200, 9000)
    const val HTTP_ACCEPT_BACKLOG = 32
    const val HTTP_HEADER_MAX_BYTES = 16 * 1024     // hard cap on the request head
    const val HTTP_BODY_MAX_BYTES = 512 * 1024      // hard cap on SOAP bodies
    const val HTTP_SOCKET_TIMEOUT_MS = 15_000
    const val HTTP_MAX_CONNECTIONS = 16
    const val HTTP_EPHEMERAL_FALLBACK = true

    // ------------------------------------------------------------------ services

    const val SERVICE_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SERVICE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    const val SERVICE_ID_AVT = "urn:upnp-org:serviceId:AVTransport"
    const val SERVICE_ID_RC = "urn:upnp-org:serviceId:RenderingControl"
    const val SERVICE_ID_CM = "urn:upnp-org:serviceId:ConnectionManager"

    // ------------------------------------------------------------------ URLs

    const val DESC = "/description.xml"
    const val DESC_ALIAS = "/device.xml"
    const val SCPD_AVT = "/avtransport.xml"
    const val SCPD_RC = "/renderingcontrol.xml"
    const val SCPD_CM = "/connectionmanager.xml"
    const val CTRL_AVT = "/control/avt"
    const val CTRL_RC = "/control/rc"
    const val CTRL_CM = "/control/cm"
    const val EVT_AVT = "/events/avt"
    const val EVT_RC = "/events/rc"
    const val EVT_CM = "/events/cm"
    const val ROOT = "/"
    const val INDEX_HTML = "/index.html"
    const val DEBUG_HTML = "/debug"
    const val DEBUG_JSON = "/debug.json"
    const val SELF_TEST = "/self-test"

    val SERVICE_BY_CONTROL_PATH: Map<String, String> = mapOf(
        CTRL_AVT to SERVICE_AVT,
        CTRL_RC to SERVICE_RC,
        CTRL_CM to SERVICE_CM
    )

    val SERVICE_BY_EVENT_PATH: Map<String, String> = mapOf(
        EVT_AVT to SERVICE_AVT,
        EVT_RC to SERVICE_RC,
        EVT_CM to SERVICE_CM
    )

    // ------------------------------------------------------------------ GENA

    const val SUB_TIMEOUT_SEC = 1800
    const val SUB_TIMEOUT_MIN_SEC = 300
    const val MAX_SUBS_PER_SERVICE = 32
    const val MAX_SUBS_TOTAL = 64
    const val NOTIFY_RETRY = 3
    const val NOTIFY_CONNECT_TIMEOUT_MS = 3_000
    const val NOTIFY_READ_TIMEOUT_MS = 3_000
    const val NOTIFY_RETRY_BASE_MS = 1_000L
    const val NOTIFY_MAX_CONSECUTIVE_FAILURES = 5
    const val SUB_EXPIRY_SWEEP_MS = 30_000L
    const val INITIAL_NOTIFY_DELAY_MS = 50L
    const val LASTCHANGE_MIN_INTERVAL_MS = 250L
    const val NT_EVENT = "upnp:event"
    const val NTS_PROPCHANGE = "upnp:propchange"

    const val SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    const val SOAP_ENV_NS_OLD = "http://schemas.xmlsoap.org/soap/envelope"
    const val SOAP_ENC_STYLE = "http://schemas.xmlsoap.org/soap/encoding/"
    const val CONTROL_NS = "urn:schemas-upnp-org:control-1-0"
    const val EVENT_NS = "urn:schemas-upnp-org:event-1-0"

    const val NS_DIDL = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_UPNP = "urn:schemas-upnp-org:metadata-1-0/upnp/"
    const val NS_DLNA = "urn:schemas-dlna-org:metadata-1-0/"
    const val NS_AVT_LASTCHANGE = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val NS_RCS_LASTCHANGE = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    const val DEVICE_NS = "urn:schemas-upnp-org:device-1-0"
    const val DLNA_DEVICE_NS = "urn:schemas-dlna-org:device-1-0"

    // ------------------------------------------------------------------ renderer identity

    const val UPNP_VERSION_TOKEN = "UPnP/1.0"
    const val CONFIG_ID = 1

    const val DEFAULT_FRIENDLY_NAME = "VLC TV"
    const val MAX_FRIENDLY_NAME_LEN = 64

    /** "Android/9 UPnP/1.0 VLC/3.6.5" — the SERVER header used by HTTP and SSDP. */
    val SERVER_HEADER: String
        get() = "Android/${Build.VERSION.RELEASE} $UPNP_VERSION_TOKEN VLC/${BuildConfig.VLC_VERSION_NAME}"

    /** Product token for the notification text; not network protocol relevant. */
    const val APP_VERSION: String = "3.6.5"

    // ------------------------------------------------------------------ settings keys

    const val KEY_UPNP_RENDERER_ENABLED = "upnp_renderer_enabled"
    const val KEY_UPNP_RENDERER_NAME = "upnp_renderer_name"
    const val KEY_UPNP_RENDERER_PORT = "upnp_renderer_port"
    const val KEY_UPNP_RENDERER_AUTOSTART = "upnp_renderer_autostart"
    const val KEY_UPNP_RENDERER_AUDIO_UI = "upnp_renderer_show_audio_ui"
    const val KEY_UPNP_RENDERER_IFACE = "upnp_renderer_iface"
    const val KEY_UPNP_RENDERER_UDN = "upnp_renderer_udn"
    const val KEY_UPNP_RENDERER_BOOTID = "upnp_renderer_bootid"

    // ------------------------------------------------------------------ notification

    const val NOTIF_ID = 4                       // PlaybackService uses 3
    const val NOTIF_CHANNEL_ID = "vlc_dlna_renderer"
    const val NOTIF_CHANNEL_NAME = "DLNA media renderer"

    // ------------------------------------------------------------------ action names

    val AVT_ACTIONS: List<String> = listOf(
        "SetAVTransportURI",
        "SetNextAVTransportURI",
        "GetMediaInfo",
        "GetTransportInfo",
        "GetPositionInfo",
        "GetDeviceCapabilities",
        "GetTransportSettings",
        "Stop",
        "Play",
        "Pause",
        "Record",
        "Seek",
        "Next",
        "Previous",
        "SetPlayMode",
        "SetRecordQualityMode",
        "GetCurrentTransportActions"
    )

    val RC_ACTIONS: List<String> = listOf(
        "ListPresets",
        "SelectPreset",
        "GetVolume",
        "SetVolume",
        "GetMute",
        "SetMute",
        "GetVolumeDB",
        "SetVolumeDB",
        "GetVolumeDBRange"
    )

    val CM_ACTIONS: List<String> = listOf(
        "GetProtocolInfo",
        "GetCurrentConnectionIDs",
        "GetCurrentConnectionInfo"
    )

    /**
     * Sink protocolInfo advertised by ConnectionManager:GetProtocolInfo.
     * Concrete MIME types first so strict control points can decide remux/transcode,
     * wildcard last so BubbleUPnP sends anything unchanged (VLC decodes almost everything).
     * No spaces after the commas; XML-escaped when placed into a SOAP response.
     */
    const val SINK_PROTOCOL_INFO: String =
        "http-get:*:audio/mpeg:*," +
            "http-get:*:audio/mp4:*," +
            "http-get:*:audio/aac:*," +
            "http-get:*:audio/x-flac:*," +
            "http-get:*:audio/flac:*," +
            "http-get:*:audio/wav:*," +
            "http-get:*:audio/x-wav:*," +
            "http-get:*:audio/ogg:*," +
            "http-get:*:audio/x-ms-wma:*," +
            "http-get:*:audio/L16:*," +
            "http-get:*:video/mp4:*," +
            "http-get:*:video/x-matroska:*," +
            "http-get:*:video/mpeg:*," +
            "http-get:*:video/MP2T:*," +
            "http-get:*:video/x-msvideo:*," +
            "http-get:*:video/x-ms-wmv:*," +
            "http-get:*:video/quicktime:*," +
            "http-get:*:video/webm:*," +
            "http-get:*:video/3gpp:*," +
            "http-get:*:image/jpeg:*," +
            "http-get:*:*:*"

    // ------------------------------------------------------------------ preferences

    private const val PREFS_SUFFIX = "_preferences"
    private val UDN_LOCK = Any()

    /**
     * Default SharedPreferences, same file the app's preference XML and Settings object use.
     * Kept as a function (not cached) so a context from a different user/profile still works.
     */
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("${context.packageName}$PREFS_SUFFIX", Context.MODE_PRIVATE)

    /** Renderer enabled preference. Product/controller/XML default is ON. */
    fun isEnabled(context: Context): Boolean = readBoolean(context, KEY_UPNP_RENDERER_ENABLED, true)

    fun autostart(context: Context): Boolean = readBoolean(context, KEY_UPNP_RENDERER_AUTOSTART, true)

    fun showAudioUi(context: Context): Boolean = readBoolean(context, KEY_UPNP_RENDERER_AUDIO_UI, true)

    /** Port preference: 0 (or absent/garbage) means "auto from [HTTP_PORTS]". */
    fun preferredPort(context: Context): Int {
        val raw = prefs(context).all[KEY_UPNP_RENDERER_PORT]?.toString()?.trim()
        return raw?.toIntOrNull() ?: 0
    }

    /** Interface preference: "auto" (default), "eth" or "wlan". */
    fun ifacePreference(context: Context): String {
        val raw = try {
            prefs(context).getString(KEY_UPNP_RENDERER_IFACE, null)
        } catch (_: ClassCastException) {
            null
        }
        return when (raw?.lowercase(Locale.US)) {
            "eth", "ethernet" -> "eth"
            "wlan", "wifi", "wi-fi" -> "wlan"
            else -> "auto"
        }
    }

    private fun readBoolean(context: Context, key: String, def: Boolean): Boolean {
        return try {
            prefs(context).getBoolean(key, def)
        } catch (_: ClassCastException) {
            val raw = prefs(context).all[key]?.toString()
            raw == "true" || raw == "1"
        }
    }

    /**
     * Persisted device UDN ("uuid:<uuid4>"). Generated once on first use and never
     * hard-coded; two TVs must never advertise the same UDN (legacy defect D12).
     */
    fun udn(context: Context): String = synchronized(UDN_LOCK) {
        val p = prefs(context)
        val existing = try {
            p.getString(KEY_UPNP_RENDERER_UDN, null)
        } catch (_: ClassCastException) {
            null
        }
        if (existing != null && isValidUdn(existing)) {
            existing
        } else {
            val generated = "uuid:" + UUID.randomUUID().toString()
            p.edit().putString(KEY_UPNP_RENDERER_UDN, generated).apply()
            generated
        }
    }

    private fun isValidUdn(value: String): Boolean {
        if (!value.startsWith("uuid:")) return false
        return try {
            UUID.fromString(value.substring(5))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Debug/UI action: forget the identity, control points will re-add the device. */
    fun resetUdn(context: Context) {
        synchronized(UDN_LOCK) {
            prefs(context).edit().remove(KEY_UPNP_RENDERER_UDN).apply()
        }
    }

    /** Reads the persisted BOOTID without touching it. */
    fun bootId(context: Context): Int = try {
        prefs(context).getInt(KEY_UPNP_RENDERER_BOOTID, 0)
    } catch (_: ClassCastException) {
        0
    }

    /** Increments and persists BOOTID; called once per service start. */
    fun bumpBootId(context: Context): Int {
        val p = prefs(context)
        val next = bootId(context) + 1
        p.edit().putInt(KEY_UPNP_RENDERER_BOOTID, next).apply()
        return next
    }

    /** friendlyName from settings, sanitized; default "VLC TV (MODEL)" when unset. */
    fun friendlyName(context: Context): String {
        val raw = try {
            prefs(context).getString(KEY_UPNP_RENDERER_NAME, null)
        } catch (_: ClassCastException) {
            null
        }
        val trimmed = raw?.trim().orEmpty()
        return sanitizeFriendlyName(if (trimmed.isEmpty()) defaultFriendlyName() else trimmed)
    }

    fun defaultFriendlyName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isEmpty() || model.equals("unknown", ignoreCase = true)) {
            DEFAULT_FRIENDLY_NAME
        } else {
            "$DEFAULT_FRIENDLY_NAME ($model)"
        }
    }

    /**
     * XML-invalid control chars removed, whitespace collapsed, length capped.
     * Escaping happens in UpnpDeviceDescription (XML is generated there).
     */
    fun sanitizeFriendlyName(raw: String): String {
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw) {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                if (sb.isNotEmpty() && !lastWasSpace) sb.append(' ')
                lastWasSpace = true
                continue
            }
            val isPrintable = ch.code in 0x20..0x7E || ch.code in 0xA0..0xD7FF || ch.code in 0xE000..0xFFFD
            if (!isPrintable) continue
            sb.append(ch)
            lastWasSpace = false
        }
        var result = sb.toString().trim()
        if (result.length > MAX_FRIENDLY_NAME_LEN) result = result.substring(0, MAX_FRIENDLY_NAME_LEN).trim()
        return result.ifEmpty { DEFAULT_FRIENDLY_NAME }
    }

    /** Location URL for a given local IPv4 and the bound HTTP port. */
    fun locationUrl(ip: String, httpPort: Int): String = "http://$ip:$httpPort$DESC"
}
