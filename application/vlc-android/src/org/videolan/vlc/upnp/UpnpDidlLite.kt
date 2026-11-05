/*****************************************************************************
 * UpnpDidlLite.kt
 *
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.upnp

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/**
 * Parsed subset of a DIDL-Lite `item` that a MediaRenderer needs.
 *
 * [uri] is always non-empty: when the metadata carries no `res` URL the caller-supplied
 * `CurrentURI` is used instead (the AVTransport URI is authoritative for playback).
 */
data class UpnpMediaItem(
        val uri: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
        val upnpClass: String? = null,
        val mime: String? = null,
        val durationMs: Long = 0L
)

/**
 * DIDL-Lite parser/builder built only on the platform [XmlPullParser] (`android.util.Xml`).
 *
 * Design rules:
 *  - namespace-prefix agnostic (`dc:`, `upnp:`, `dlna:`, default namespaces all work),
 *  - tolerant of CDATA, whitespace and nested elements (some control points send the DIDL as
 *    nested elements instead of escaped text - the SOAP layer re-serializes it before us),
 *  - multiple `<res>` elements: the one whose URL equals the `CurrentURI` wins, then the first
 *    `http-get` resource, then the first one,
 *  - never throws: malformed metadata degrades to a bare [UpnpMediaItem] (or `null` when no URI
 *    at all is known).
 *
 * All helpers are members of the object on purpose: the network side (UpnpActionsAvTransport,
 * UpnpSoapHandler, ...) keeps its own duration/escaping helpers and the package must not contain
 * duplicated top-level declarations.
 */
object UpnpDidlLite {

    const val NS_DIDL = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_UPNP = "urn:schemas-upnp-org:metadata-1-0/upnp/"
    const val NS_DLNA = "urn:schemas-dlna-org:metadata-1-0/"

    private const val MAX_METADATA_LENGTH = 256 * 1024

    private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
            "wmv", "flv", "3gp", "3g2", "ogv", "vob", "divx", "asf"
    )

    private data class ResInfo(val url: String, val protocolInfo: String?, val duration: String?)

    /**
     * Parses [metadata] (DIDL-Lite text, possibly escaped already by the SOAP layer).
     *
     * @param currentUri authoritative AVTransport URI; used when the DIDL has no usable `res`.
     * @return the parsed item, or `null` when there is neither a `res` URL nor a `currentUri`.
     */
    fun parse(metadata: String?, currentUri: String? = null): UpnpMediaItem? {
        val fallbackUri = currentUri?.trim()?.takeIf { it.isNotEmpty() }
        if (metadata.isNullOrBlank()) return fallbackUri?.let { UpnpMediaItem(it) }
        val xml = metadata.trim()
        if (xml.length > MAX_METADATA_LENGTH) return fallbackUri?.let { UpnpMediaItem(it) }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artworkUrl: String? = null
        var upnpClass: String? = null
        val resources = ArrayList<ResInfo>(2)

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(xml))

            var currentRes: ResInfo? = null
            val resBuffer = StringBuilder()
            val textBuffer = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val local = localName(parser)
                        textBuffer.setLength(0)
                        if (local == "res") {
                            currentRes = ResInfo(
                                    "",
                                    parser.getAttributeValue(null, "protocolInfo"),
                                    parser.getAttributeValue(null, "duration")
                            )
                            resBuffer.setLength(0)
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val text = parser.text ?: ""
                        if (currentRes != null) resBuffer.append(text) else textBuffer.append(text)
                    }
                    XmlPullParser.END_TAG -> {
                        val local = localName(parser)
                        if (local == "res") {
                            currentRes?.let { resources.add(it.copy(url = resBuffer.toString().trim())) }
                            currentRes = null
                            resBuffer.setLength(0)
                        } else {
                            val value = textBuffer.toString().trim()
                            if (value.isNotEmpty()) {
                                when (local) {
                                    "title" -> if (title == null) { title = value }
                                    "artist" -> if (artist == null) { artist = value }
                                    "album" -> if (album == null) { album = value }
                                    "albumArtURI" -> if (artworkUrl == null) { artworkUrl = value }
                                    "class" -> if (upnpClass == null) { upnpClass = value }
                                }
                            }
                            textBuffer.setLength(0)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Malformed DIDL must never break the control plane: fall through with what we have.
        }

        val chosen = chooseRes(resources, fallbackUri)
        val uri = chosen?.url?.takeIf { it.isNotEmpty() } ?: fallbackUri ?: return null
        val mime = chosen?.protocolInfo?.let { mimeFromProtocolInfo(it) }
        val duration = chosen?.duration?.let { parseAvtDuration(it) } ?: resources
                .firstOrNull { !it.duration.isNullOrBlank() }
                ?.duration
                ?.let { parseAvtDuration(it) }
                ?: 0L

        return UpnpMediaItem(
                uri = uri,
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) },
                upnpClass = upnpClass,
                mime = mime ?: protocolInfoMime(chosen?.protocolInfo),
                durationMs = duration
        )
    }

    /**
     * Builds a minimal DIDL-Lite document used when the control point sent no metadata (so
     * `GetMediaInfo` / `GetPositionInfo` still return something useful) and for generated items.
     */
    fun build(
            uri: String,
            title: String?,
            mime: String?,
            upnpClass: String?,
            durationMs: Long = 0L,
            artist: String? = null,
            album: String? = null,
            artworkUrl: String? = null
    ): String {
        val cls = upnpClass ?: if (artist != null || album != null) "object.item.audioItem.musicTrack" else "object.item.videoItem"
        val resolvedMime = mime ?: if (cls.contains("audioItem", true)) "audio/mpeg" else "video/mp4"
        val sb = StringBuilder(512)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<DIDL-Lite xmlns=\"").append(NS_DIDL)
                .append("\" xmlns:dc=\"").append(NS_DC)
                .append("\" xmlns:upnp=\"").append(NS_UPNP).append("\">")
        sb.append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        if (!title.isNullOrBlank()) sb.append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
        if (!artist.isNullOrBlank()) sb.append("<upnp:artist>").append(xmlEscape(artist)).append("</upnp:artist>")
        if (!album.isNullOrBlank()) sb.append("<upnp:album>").append(xmlEscape(album)).append("</upnp:album>")
        if (!artworkUrl.isNullOrBlank()) sb.append("<upnp:albumArtURI>").append(xmlEscape(artworkUrl)).append("</upnp:albumArtURI>")
        sb.append("<upnp:class>").append(xmlEscape(cls)).append("</upnp:class>")
        sb.append("<res protocolInfo=\"http-get:*:").append(xmlEscape(resolvedMime)).append(":*\"")
        if (durationMs > 0L) sb.append(" duration=\"").append(formatAvtDuration(durationMs)).append("\"")
        sb.append(">").append(xmlEscape(uri)).append("</res>")
        sb.append("</item></DIDL-Lite>")
        return sb.toString()
    }

    /**
     * Type resolution order mandated by the design: DIDL `upnp:class` first, `res@protocolInfo`
     * MIME second, URL extension last. Contradictions (audio class + video mime) trust the MIME.
     */
    fun resolveIsVideo(item: UpnpMediaItem?): Boolean {
        if (item == null) return false
        val cls = item.upnpClass?.lowercase(Locale.US)
        val mime = item.mime?.lowercase(Locale.US)
        val classVideo = cls != null && cls.contains("videoitem")
        val classAudio = cls != null && cls.contains("audioitem")
        val mimeVideo = mime != null && (mime.startsWith("video/") || mime == "application/mp4" || mime.startsWith("application/x-matroska"))
        val mimeAudio = mime != null && mime.startsWith("audio/")
        return when {
            classVideo -> true
            classAudio -> if (mimeVideo) true else false
            mimeVideo -> true
            mimeAudio -> false
            else -> extensionOf(item.uri) in VIDEO_EXTENSIONS
        }
    }

    /** `H+:MM:SS` (AVTransport form, hours not zero padded, no fraction). */
    fun formatAvtDuration(ms: Long): String {
        val totalSec = if (ms < 0L) 0L else ms / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    /** Accepts `H:MM:SS`, `H:MM:SS.mmm`, `MM:SS` and a raw milliseconds value. */
    fun parseAvtDuration(value: String?): Long {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        if (!raw.contains(':')) return raw.toLongOrNull() ?: 0L
        val parts = raw.split(':')
        if (parts.size !in 2..3) return 0L
        return try {
            when (parts.size) {
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    ((m * 60L) + s.toLong()) * 1000L + fractionalMillis(parts[1])
                }
                else -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    ((h * 3600L) + (m * 60L) + s.toLong()) * 1000L + fractionalMillis(parts[2])
                }
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }

    fun xmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun fractionalMillis(seconds: String): Long {
        val dot = seconds.indexOf('.')
        if (dot < 0 || dot == seconds.lastIndex) return 0L
        val fraction = seconds.substring(dot + 1).take(3).padEnd(3, '0')
        return fraction.toLongOrNull() ?: 0L
    }

    private fun localName(parser: XmlPullParser): String =
            (parser.name ?: "").substringAfter(':').lowercase(Locale.US)

    private fun chooseRes(resources: List<ResInfo>, fallbackUri: String?): ResInfo? {
        if (resources.isEmpty()) return null
        fallbackUri?.let { uri ->
            resources.firstOrNull { it.url == uri }?.let { return it }
        }
        return resources.firstOrNull { it.protocolInfo?.contains("http-get", true) == true && mimeFromProtocolInfo(it.protocolInfo) != null }
                ?: resources.firstOrNull { it.url.isNotEmpty() }
    }

    /** `http-get:*:video/mp4:*` -> `video/mp4`. */
    private fun mimeFromProtocolInfo(protocolInfo: String?): String? {
        val parts = protocolInfo?.split(':') ?: return null
        if (parts.size < 3) return null
        val mime = parts[2].trim().lowercase(Locale.US)
        return mime.takeIf { it.isNotEmpty() && it != "*" && it.contains('/') }
    }

    private fun protocolInfoMime(protocolInfo: String?): String? = mimeFromProtocolInfo(protocolInfo)

    private fun extensionOf(uri: String): String {
        val path = uri.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot == path.lastIndex) return ""
        val ext = path.substring(dot + 1).lowercase(Locale.US)
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext else ""
    }
}
