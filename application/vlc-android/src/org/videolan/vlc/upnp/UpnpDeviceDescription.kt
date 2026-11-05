/*
 * UpnpDeviceDescription.kt — the UPnP device description served at
 * /description.xml (alias /device.xml), design doc §3.1.
 *
 * The document is byte-stable for a given (udn, friendlyName): control points fetch it
 * repeatedly. It is rebuilt only when the service starts (friendly name or port change).
 *
 * Deliberate choices:
 *  - URLBase is omitted: all URLs are absolute-path references resolved against the
 *    per-interface LOCATION, which keeps the same document valid on a dual-NIC TV.
 *  - serviceList order is AVTransport, RenderingControl, ConnectionManager (the order
 *    real DMRs use; the legacy code put RenderingControl first and is unusual).
 *  - iconList is omitted because this iteration ships no renderer icon resource; a
 *    <icon> element pointing at a missing URL makes strict control points log errors.
 *  - dlna:X_DLNADOC DMR-1.50 is mandatory for Windows "Cast to device"/BubbleUPnP to
 *    classify the device as a DLNA renderer.
 */
package org.videolan.vlc.upnp

import android.content.Context
import org.videolan.vlc.BuildConfig

class UpnpDeviceDescription(
    private val context: Context,
    val udn: String,
    explicitFriendlyName: String? = null
) {

    /** Sanitized, XML-ready friendly name; safe to display/log. */
    val friendlyName: String = explicitFriendlyName
        ?.let { UpnpConstants.sanitizeFriendlyName(it) }
        ?: UpnpConstants.friendlyName(context)

    /** Cached XML so repeated GET /description.xml responses are byte-identical. */
    @Volatile
    private var cached: ByteArray? = null

    fun buildXml(): String = build(udn, friendlyName)

    fun buildBytes(): ByteArray {
        cached?.let { return it }
        val bytes = buildXml().toByteArray(Charsets.UTF_8)
        cached = bytes
        return bytes
    }

    companion object {

        private const val DEVICE_TYPE = UpnpConstants.DEVICE_TYPE

        /** Pure builder, usable without a Context (tests, self-test, controller). */
        fun build(udn: String, friendlyName: String): String {
            val name = escapeXml(UpnpConstants.sanitizeFriendlyName(friendlyName))
            return buildString(4096) {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<root xmlns=\"").append(UpnpConstants.DEVICE_NS)
                    .append("\" xmlns:dlna=\"").append(UpnpConstants.DLNA_DEVICE_NS).append("\">\n")
                append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
                append("  <device>\n")
                append("    <deviceType>").append(DEVICE_TYPE).append("</deviceType>\n")
                append("    <friendlyName>").append(name).append("</friendlyName>\n")
                append("    <manufacturer>VideoLAN</manufacturer>\n")
                append("    <manufacturerURL>https://www.videolan.org/</manufacturerURL>\n")
                append("    <modelDescription>VLC for Android TV - DLNA Media Renderer</modelDescription>\n")
                append("    <modelName>VLC for Android TV</modelName>\n")
                append("    <modelNumber>").append(escapeXml(BuildConfig.VLC_VERSION_NAME)).append("</modelNumber>\n")
                append("    <modelURL>https://www.videolan.org/vlc/download-android.html</modelURL>\n")
                append("    <UDN>").append(escapeXml(udn)).append("</UDN>\n")
                append("    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>\n")
                append("    <presentationURL>/</presentationURL>\n")
                append("    <serviceList>\n")
                appendService(
                    this,
                    UpnpConstants.SERVICE_AVT,
                    UpnpConstants.SERVICE_ID_AVT,
                    UpnpConstants.SCPD_AVT,
                    UpnpConstants.CTRL_AVT,
                    UpnpConstants.EVT_AVT
                )
                appendService(
                    this,
                    UpnpConstants.SERVICE_RC,
                    UpnpConstants.SERVICE_ID_RC,
                    UpnpConstants.SCPD_RC,
                    UpnpConstants.CTRL_RC,
                    UpnpConstants.EVT_RC
                )
                appendService(
                    this,
                    UpnpConstants.SERVICE_CM,
                    UpnpConstants.SERVICE_ID_CM,
                    UpnpConstants.SCPD_CM,
                    UpnpConstants.CTRL_CM,
                    UpnpConstants.EVT_CM
                )
                append("    </serviceList>\n")
                append("  </device>\n")
                append("</root>")
            }
        }

        private fun appendService(
            sb: StringBuilder,
            serviceType: String,
            serviceId: String,
            scpdUrl: String,
            controlUrl: String,
            eventUrl: String
        ) {
            sb.append("      <service>\n")
            sb.append("        <serviceType>").append(serviceType).append("</serviceType>\n")
            sb.append("        <serviceId>").append(serviceId).append("</serviceId>\n")
            sb.append("        <SCPDURL>").append(scpdUrl).append("</SCPDURL>\n")
            sb.append("        <controlURL>").append(controlUrl).append("</controlURL>\n")
            sb.append("        <eventSubURL>").append(eventUrl).append("</eventSubURL>\n")
            sb.append("      </service>\n")
        }

        /** XML text escaping for & < > " ' (attributes and element text). */
        fun escapeXml(value: String): String {
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
