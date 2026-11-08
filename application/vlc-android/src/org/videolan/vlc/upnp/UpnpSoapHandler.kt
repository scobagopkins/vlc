/*
 * UpnpSoapHandler.kt — SOAP envelope parsing, exact-name dispatch, argument validation
 * and UPnP fault construction (design doc §4.3–§4.6).
 *
 * Replaces the legacy substring dispatch (audit P1-1) with:
 *   - SOAPACTION header parsed as "<serviceType>#<actionName>" (quotes stripped, case
 *     preserved, service must match the /control/ endpoint);
 *   - a body-root fallback ONLY when SOAPACTION is absent;
 *   - a Map<String, (SoapArgs) -> SoapResult> per service, keyed by exact action name;
 *   - a real XML parser (android.util.Xml / XmlPullParser), never regex or contains();
 *   - UPnP faults over HTTP 500: 401 unknown action, 402 missing/bad args,
 *     501 internal failure, 701 transition not available, 706 invalid connection,
 *     710/711/712 seek/speed, 714 illegal MIME, 718 invalid InstanceID.
 *
 * Public types (same package):
 *   UpnpSoapHandler(context, gena, bridge?) + legacy-order secondary constructor
 *   SoapResult, UpnpError, UpnpException, SoapArgs
 */
package org.videolan.vlc.upnp

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** UPnP control error codes emitted by this renderer (canonical descriptions). */
enum class UpnpError(val code: Int, val description: String) {
    InvalidAction(401, "Invalid Action"),
    InvalidArgs(402, "Invalid Args"),
    ActionFailed(501, "Action Failed"),
    ArgumentValueInvalid(600, "Argument Value Invalid"),
    TransitionNotAvailable(701, "Transition not available"),
    NoContents(702, "No contents"),
    FormatNotSupportedForPlayback(704, "Format not supported for playback"),
    InvalidConnectionReference(706, "Invalid connection reference"),
    SeekModeNotSupported(710, "Seek mode not supported"),
    IllegalSeekTarget(711, "Illegal seek target"),
    PlaySpeedNotSupported(712, "Play speed not supported"),
    IllegalMimeType(714, "Illegal MIME-type"),
    ContentBusy(715, "Content busy"),
    ResourceNotFound(716, "Resource not found"),
    InvalidInstanceId(718, "Invalid InstanceID")
}

/** Thrown by action implementations to return a specific UPnP fault. */
class UpnpException(val error: UpnpError) : Exception(error.description)

/**
 * Result of one SOAP request: either a success envelope (HTTP 200) or a fault envelope
 * (HTTP 500). GENA/HTTP layers write [httpStatus] + [body] + [contentType] verbatim.
 */
class SoapResult private constructor(
    val httpStatus: Int,
    val body: String,
    val contentType: String
) {
    val isSuccess: Boolean
        get() = httpStatus == 200

    companion object {
        const val CONTENT_TYPE = "text/xml; charset=\"utf-8\""

        fun success(serviceType: String, action: String, outArgs: List<Pair<String, String>> = emptyList()): SoapResult {
            val args = StringBuilder(128)
            outArgs.forEach { (name, value) ->
                args.append('<').append(name).append('>')
                args.append(escapeXml(value))
                args.append("</").append(name).append('>')
            }
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"${UpnpConstants.SOAP_ENV_NS}\" " +
                "s:encodingStyle=\"${UpnpConstants.SOAP_ENC_STYLE}\">\n" +
                "  <s:Body>\n" +
                "    <u:$action" + "Response xmlns:u=\"$serviceType\">$args</u:$action" + "Response>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>"
            return SoapResult(200, body, CONTENT_TYPE)
        }

        fun fault(error: UpnpError): SoapResult {
            val faultCode = if (error.code in 500..599) "s:Server" else "s:Client"
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"${UpnpConstants.SOAP_ENV_NS}\" " +
                "s:encodingStyle=\"${UpnpConstants.SOAP_ENC_STYLE}\">\n" +
                "  <s:Body>\n" +
                "    <s:Fault>\n" +
                "      <faultcode>$faultCode</faultcode>\n" +
                "      <faultstring>UPnPError</faultstring>\n" +
                "      <detail>\n" +
                "        <UPnPError xmlns=\"${UpnpConstants.CONTROL_NS}\">\n" +
                "          <errorCode>${error.code}</errorCode>\n" +
                "          <errorDescription>${escapeXml(error.description)}</errorDescription>\n" +
                "        </UPnPError>\n" +
                "      </detail>\n" +
                "    </s:Fault>\n" +
                "  </s:Body>\n" +
                "</s:Envelope>"
            return SoapResult(500, body, CONTENT_TYPE)
        }

        private fun escapeXml(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (ch in value) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\'' -> sb.append("&apos;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}

/**
 * Parsed action arguments. Argument names are matched case-insensitively because a few
 * control points lowercase them even though the SCPD says otherwise.
 */
class SoapArgs internal constructor(private val values: Map<String, String>) {

    operator fun get(name: String): String? {
        values[name]?.let { return it }
        return values.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    fun has(name: String): Boolean = get(name) != null

    fun require(name: String): String = get(name) ?: throw UpnpException(UpnpError.InvalidArgs)

    fun requireNonEmpty(name: String): String {
        val value = require(name).trim()
        if (value.isEmpty()) throw UpnpException(UpnpError.InvalidArgs)
        return value
    }

    fun requireInt(name: String): Int = requireNonEmpty(name).toIntOrNull() ?: throw UpnpException(UpnpError.InvalidArgs)

    fun requireLong(name: String): Long = requireNonEmpty(name).toLongOrNull() ?: throw UpnpException(UpnpError.InvalidArgs)

    fun asMap(): Map<String, String> = values

    override fun toString(): String = values.entries.joinToString(", ", "{", "}") { (k, v) ->
        val value = if (v.length > 48) v.substring(0, 48) + "…" else v
        "$k=$value"
    }
}

class UpnpSoapHandler(
    private val context: Context,
    private val gena: UpnpGena,
    @Suppress("UNUSED_PARAMETER") bridge: UpnpPlaybackBridge? = null
) {

    /** Legacy/design-doc call order: UpnpSoapHandler(context, bridge, gena). */
    constructor(context: Context, bridge: UpnpPlaybackBridge?, gena: UpnpGena) : this(context, gena, bridge)

    private val avtHandlers: Map<String, (SoapArgs) -> SoapResult> by lazy { UpnpActionsAvTransport.handlers(gena) }
    private val rcHandlers: Map<String, (SoapArgs) -> SoapResult> by lazy { UpnpActionsRenderingControl.handlers(gena) }
    private val cmHandlers: Map<String, (SoapArgs) -> SoapResult> by lazy { UpnpActionsConnectionManager.handlers() }

    /**
     * Entry point used by UpnpHttpServer for POST /control/avt|rc|cm.
     * Never touches PlaybackService directly; the action classes use the snapshot and
     * the (non-blocking) playback bridge.
     */
    fun handle(path: String, headers: Map<String, String>, body: String): SoapResult {
        val startedAt = System.currentTimeMillis()
        val expectedService = UpnpConstants.SERVICE_BY_CONTROL_PATH[path]
            ?: return SoapResult.fault(UpnpError.InvalidAction)

        val header = parseSoapAction(headers["soapaction"])
        val envelope = try {
            parseEnvelope(body)
        } catch (e: Throwable) {
            UpnpDebug.soap("envelope parse failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        val serviceType = header?.first ?: envelope?.service
        val actionName = header?.second ?: envelope?.action
        if (serviceType == null || actionName == null) {
            UpnpDebug.inc("soap.unknown")
            return fail(UpnpError.InvalidAction, path, "missing SOAPACTION and unparsable body", startedAt)
        }
        if (serviceType != expectedService) {
            UpnpDebug.inc("soap.unknown")
            return fail(UpnpError.InvalidAction, path, "service $serviceType does not match endpoint", startedAt)
        }
        if (envelope != null && envelope.service != null && envelope.service != expectedService) {
            UpnpDebug.inc("soap.unknown")
            return fail(UpnpError.InvalidAction, path, "body namespace ${envelope.service} does not match endpoint", startedAt)
        }

        val handlers = when (serviceType) {
            UpnpConstants.SERVICE_AVT -> avtHandlers
            UpnpConstants.SERVICE_RC -> rcHandlers
            UpnpConstants.SERVICE_CM -> cmHandlers
            else -> return fail(UpnpError.InvalidAction, path, "unsupported service $serviceType", startedAt)
        }
        val handler = handlers[actionName] ?: handlers.entries
            .firstOrNull { it.key.equals(actionName, ignoreCase = true) }?.value
            ?: run {
                UpnpDebug.inc("soap.unknown")
                return fail(UpnpError.InvalidAction, path, "unknown action $actionName", startedAt)
            }

        if (envelope == null) {
            // Header gave us the action but the body is not parseable XML.
            UpnpDebug.inc("soap.fault.402")
            UpnpDebug.inc("soap.fault")
            return fail(UpnpError.InvalidArgs, path, "unparsable SOAP body", startedAt)
        }

        val args = SoapArgs(envelope.args)

        // Shared validation: every AVTransport/RenderingControl action takes InstanceID=0.
        if (serviceType != UpnpConstants.SERVICE_CM) {
            val raw = args["InstanceID"] ?: return fail(UpnpError.InvalidArgs, path, "missing InstanceID", startedAt, actionName)
            val instanceId = raw.trim().toLongOrNull()
                ?: return fail(UpnpError.InvalidArgs, path, "non-numeric InstanceID '$raw'", startedAt, actionName)
            if (instanceId != 0L) {
                return fail(UpnpError.InvalidInstanceId, path, "InstanceID=$instanceId", startedAt, actionName)
            }
        }

        UpnpDebug.inc("soap.rx")
        UpnpDebug.inc("soap.rx.$actionName")
        val result = try {
            handler.invoke(args)
        } catch (e: UpnpException) {
            SoapResult.fault(e.error)
        } catch (e: Throwable) {
            UpnpDebug.soap("$actionName failed", e)
            SoapResult.fault(UpnpError.ActionFailed)
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val status = if (result.isSuccess) "200" else "500/${errorCodeOf(result)}"
        UpnpDebug.soap("${shortService(serviceType)} $actionName in=$args -> $status (${elapsed}ms)")
        if (!result.isSuccess) {
            UpnpDebug.inc("soap.fault")
            UpnpDebug.inc("soap.fault.${errorCodeOf(result)}")
        }
        return result
    }

    private fun fail(
        error: UpnpError,
        path: String,
        reason: String,
        startedAt: Long,
        action: String = "-"
    ): SoapResult {
        UpnpDebug.soap("$path $action -> FAULT ${error.code} ($reason, ${System.currentTimeMillis() - startedAt} ms)")
        UpnpDebug.inc("soap.fault")
        UpnpDebug.inc("soap.fault.${error.code}")
        return SoapResult.fault(error)
    }

    /** Extracts the errorCode from a fault body (for counters/logs only). */
    private fun errorCodeOf(result: SoapResult): String {
        val match = Regex("<errorCode>(\\d+)</errorCode>").find(result.body)
        return match?.groupValues?.get(1) ?: "unknown"
    }

    private fun shortService(serviceType: String): String = when (serviceType) {
        UpnpConstants.SERVICE_AVT -> "AVT"
        UpnpConstants.SERVICE_RC -> "RC"
        UpnpConstants.SERVICE_CM -> "CM"
        else -> serviceType
    }

    // ------------------------------------------------------------------ SOAP parsing

    private class ParsedEnvelope(
        val service: String?,
        val action: String,
        val args: Map<String, String>
    )

    /**
     * "urn:schemas-upnp-org:service:AVTransport:1#Play" -> (serviceType, "Play").
     * Quotes are optional; whitespace tolerated.
     */
    private fun parseSoapAction(raw: String?): Pair<String, String>? {
        val value = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        val hash = value.indexOf('#')
        if (hash <= 0 || hash == value.lastIndex) return null
        val service = value.substring(0, hash).trim()
        val action = value.substring(hash + 1).trim()
        if (service.isEmpty() || action.isEmpty()) return null
        return service to action
    }

    /**
     * Walks s:Envelope -> s:Body -> <u:Action> and collects the action's direct child
     * elements (local names). Nested elements are re-serialized so a DIDL payload sent
     * as an element (instead of escaped text) is visible to the handler.
     */
    private fun parseEnvelope(body: String): ParsedEnvelope? {
        if (body.isBlank()) return null
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(body))

        var foundEnvelope = false
        var foundBody = false
        var actionName: String? = null
        var actionService: String? = null
        val args = LinkedHashMap<String, String>()

        var event = parser.eventType
        var depthGuard = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            if (++depthGuard > 4096) return null
            if (event == XmlPullParser.START_TAG) {
                val name = localName(parser)
                when {
                    !foundEnvelope -> {
                        if (name.equals("Envelope", ignoreCase = true)) {
                            val ns = parser.namespace
                            if (ns != null && ns != UpnpConstants.SOAP_ENV_NS && ns != UpnpConstants.SOAP_ENV_NS_OLD) {
                                return null
                            }
                            foundEnvelope = true
                        }
                    }
                    !foundBody -> {
                        if (name.equals("Body", ignoreCase = true)) foundBody = true
                    }
                    actionName == null -> {
                        actionName = name
                        actionService = parser.namespace
                    }
                    else -> {
                        if (args.size >= MAX_ARGS) return null
                        val argName = name
                        val argValue = readElementContent(parser)
                        if (argValue.length > MAX_ARG_LENGTH) return null
                        args[argName] = argValue
                    }
                }
            }
            event = parser.next()
        }

        val action = actionName ?: return null
        return ParsedEnvelope(actionService, action, args)
    }

    /** Reads the content of the element the parser is currently on; re-serializes nested XML. */
    private fun readElementContent(parser: XmlPullParser): String {
        val startDepth = parser.depth
        val sb = StringBuilder(64)
        var sawNested = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    sawNested = true
                    appendStartTag(parser, sb)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == startDepth) {
                        return if (sawNested) sb.toString() else sb.toString().trim()
                    }
                    sb.append("</").append(localName(parser)).append('>')
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = parser.text ?: ""
                    if (sawNested) sb.append(escapeXmlText(text)) else sb.append(text)
                }
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
    }

    private fun appendStartTag(parser: XmlPullParser, sb: StringBuilder) {
        sb.append('<').append(localName(parser))
        for (index in 0 until parser.attributeCount) {
            val attributeName = parser.getAttributeName(index)
            val attributeValue = parser.getAttributeValue(index) ?: ""
            sb.append(' ').append(attributeName).append("=\"")
                .append(escapeXmlAttribute(attributeValue)).append('"')
        }
        sb.append('>')
    }

    private fun localName(parser: XmlPullParser): String = parser.name.substringAfter(':')

    private fun escapeXmlText(value: String): String {
        val sb = StringBuilder(value.length + 8)
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

    private fun escapeXmlAttribute(value: String): String {
        val sb = StringBuilder(value.length + 8)
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

    private companion object {
        const val MAX_ARGS = 64
        const val MAX_ARG_LENGTH = 256 * 1024
    }
}
