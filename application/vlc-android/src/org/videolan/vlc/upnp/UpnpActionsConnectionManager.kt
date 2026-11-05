/*
 * UpnpActionsConnectionManager.kt — ConnectionManager:1 actions (design doc §4.7.3).
 *
 * The renderer has no source, so SourceProtocolInfo is empty; SinkProtocolInfo is the
 * realistic list from UpnpConstants.SINK_PROTOCOL_INFO. GetCurrentConnectionIDs always
 * reports the single implicit connection "0"; GetCurrentConnectionInfo(0) describes it
 * and any other ID is answered with 706 Invalid connection reference instead of a fake
 * 200 GetProtocolInfoResponse (legacy audit P1-2).
 */
package org.videolan.vlc.upnp

import java.util.Locale

object UpnpActionsConnectionManager {

    private const val SERVICE = UpnpConstants.SERVICE_CM
    private const val CONNECTION_ID = 0

    fun handlers(): Map<String, (SoapArgs) -> SoapResult> = mapOf(
        "GetProtocolInfo" to { _ -> getProtocolInfo() },
        "GetCurrentConnectionIDs" to { _ -> getCurrentConnectionIds() },
        "GetCurrentConnectionInfo" to { args -> getCurrentConnectionInfo(args) }
    )

    private fun getProtocolInfo(): SoapResult = SoapResult.success(
        SERVICE,
        "GetProtocolInfo",
        listOf(
            "Source" to "",
            "Sink" to UpnpConstants.SINK_PROTOCOL_INFO
        )
    )

    private fun getCurrentConnectionIds(): SoapResult = SoapResult.success(
        SERVICE,
        "GetCurrentConnectionIDs",
        listOf("ConnectionIDs" to CONNECTION_ID.toString())
    )

    private fun getCurrentConnectionInfo(args: SoapArgs): SoapResult {
        val requested = args.requireInt("ConnectionID")
        if (requested != CONNECTION_ID) throw UpnpException(UpnpError.InvalidConnectionReference)
        return SoapResult.success(
            SERVICE,
            "GetCurrentConnectionInfo",
            listOf(
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to currentProtocolInfo(),
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK"
            )
        )
    }

    /**
     * Best-effort protocolInfo of the current media. We do not have a MIME field in the
     * snapshot contract, so derive it from the URI extension; "http-get:*:*:*" is the
     * honest fallback (the control point already knows what it sent).
     */
    private fun currentProtocolInfo(): String {
        val uri = UpnpPlaybackSnapshot.uri.orEmpty().lowercase(Locale.US)
        if (uri.isEmpty()) return ""
        val mime = when {
            uri.endsWith(".mp4") || uri.endsWith(".m4v") -> "video/mp4"
            uri.endsWith(".mkv") -> "video/x-matroska"
            uri.endsWith(".mpeg") || uri.endsWith(".mpg") -> "video/mpeg"
            uri.endsWith(".ts") || uri.endsWith(".m2ts") -> "video/MP2T"
            uri.endsWith(".avi") -> "video/x-msvideo"
            uri.endsWith(".wmv") || uri.endsWith(".asf") -> "video/x-ms-wmv"
            uri.endsWith(".mov") -> "video/quicktime"
            uri.endsWith(".webm") -> "video/webm"
            uri.endsWith(".3gp") -> "video/3gpp"
            uri.endsWith(".mp3") -> "audio/mpeg"
            uri.endsWith(".m4a") || uri.endsWith(".aac") || uri.endsWith(".mp4a") -> "audio/mp4"
            uri.endsWith(".flac") -> "audio/x-flac"
            uri.endsWith(".wav") -> "audio/wav"
            uri.endsWith(".ogg") || uri.endsWith(".oga") -> "audio/ogg"
            uri.endsWith(".wma") -> "audio/x-ms-wma"
            uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> "image/jpeg"
            uri.endsWith(".png") -> "image/png"
            else -> return "http-get:*:*:*"
        }
        return "http-get:*:$mime:*"
    }
}
