/*
 * UpnpActionsAvTransport.kt — AVTransport:1 action implementations (design doc §4.7.1).
 *
 * All state is read from UpnpPlaybackSnapshot (@Volatile) and all commands are enqueued
 * through UpnpPlaybackBridge (non-blocking). This class NEVER touches PlaybackService
 * or its @MainThread properties — that is what fixes the legacy defect D31/P1-7.
 *
 * Boolean bridge results are turned into honest UPnP faults instead of unconditional
 * HTTP 200 (audit P0-3):
 *   - Play/Pause/Seek/Next/Previous precondition failures -> 701 Transition not available
 *   - internal bridge failure                            -> 501 Action Failed
 *   - Speed != 1 -> 712, unknown Seek unit -> 710, bad target -> 711
 *   - image item -> 714, unsupported URI scheme -> 714
 *
 * Duration helpers (formatAvtDuration/parseAvtDuration) live here as object members;
 * UpnpGena reuses them so AVT responses and LastChange agree byte-for-byte.
 */
package org.videolan.vlc.upnp

import java.util.Locale

object UpnpActionsAvTransport {

    private const val SERVICE = UpnpConstants.SERVICE_AVT
    private const val FAST_COUNTER = 2147483647L

    /** Next-item staging for SetNextAVTransportURI / Next. */
    @Volatile
    private var nextUri: String? = null

    @Volatile
    private var nextMetadata: String? = null

    fun handlers(gena: UpnpGena): Map<String, (SoapArgs) -> SoapResult> = mapOf(
        "SetAVTransportURI" to { args -> setAvTransportUri(gena, args) },
        "SetNextAVTransportURI" to { args -> setNextAvTransportUri(args) },
        "GetMediaInfo" to { _ -> getMediaInfo() },
        "GetTransportInfo" to { _ -> getTransportInfo() },
        "GetPositionInfo" to { _ -> getPositionInfo() },
        "GetDeviceCapabilities" to { _ -> getDeviceCapabilities() },
        "GetTransportSettings" to { _ -> getTransportSettings() },
        "Stop" to { args -> stop(gena, args) },
        "Play" to { args -> play(gena, args) },
        "Pause" to { args -> pause(gena, args) },
        "Record" to { _ -> throw UpnpException(UpnpError.TransitionNotAvailable) },
        "Seek" to { args -> seek(gena, args) },
        "Next" to { args -> next(gena, args) },
        "Previous" to { args -> previous(gena, args) },
        "SetPlayMode" to { args -> setPlayMode(args) },
        "SetRecordQualityMode" to { _ -> throw UpnpException(UpnpError.TransitionNotAvailable) },
        "GetCurrentTransportActions" to { _ -> getCurrentTransportActions() }
    )

    // ------------------------------------------------------------------ setters

    private fun setAvTransportUri(gena: UpnpGena, args: SoapArgs): SoapResult {
        val uri = args["CurrentURI"].orEmpty().trim()
        val metadata = args["CurrentURIMetaData"]?.trim().orEmpty()
        if (uri.isNotEmpty()) {
            if (!isSupportedScheme(uri)) {
                UpnpDebug.playback("SetAVTransportURI rejected scheme: $uri")
                throw UpnpException(UpnpError.IllegalMimeType)
            }
            if (looksLikeImage(metadata, uri)) {
                UpnpDebug.playback("SetAVTransportURI rejected image item: $uri")
                throw UpnpException(UpnpError.FormatNotSupportedForPlayback)
            }
        }
        try {
            UpnpPlaybackBridge.setUri(uri, metadata.ifEmpty { null })
        } catch (e: Throwable) {
            UpnpDebug.playback("SetAVTransportURI bridge failure", e)
            throw UpnpException(UpnpError.ActionFailed)
        }
        val newState = if (uri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
        nextUri = null
        nextMetadata = null
        gena.notifyAvTransport(newState, uri, metadata)
        UpnpDebug.playback("SetAVTransportURI state=$newState uri=$uri meta=${metadata.length}b")
        return SoapResult.success(SERVICE, "SetAVTransportURI")
    }

    private fun setNextAvTransportUri(args: SoapArgs): SoapResult {
        val uri = args["NextURI"].orEmpty().trim()
        val metadata = args["NextURIMetaData"]?.trim().orEmpty()
        if (uri.isNotEmpty() && !isSupportedScheme(uri)) throw UpnpException(UpnpError.IllegalMimeType)
        nextUri = uri.ifEmpty { null }
        nextMetadata = metadata.ifEmpty { null }
        UpnpDebug.playback("SetNextAVTransportURI uri=$uri")
        return SoapResult.success(SERVICE, "SetNextAVTransportURI")
    }

    private fun stop(gena: UpnpGena, args: SoapArgs): SoapResult {
        val accepted = bridgeBoolean("Stop") { UpnpPlaybackBridge.stop() }
        gena.notifyAvTransport("STOPPED", currentUri(), currentMetadata())
        UpnpDebug.playback("Stop accepted=$accepted state=STOPPED")
        return SoapResult.success(SERVICE, "Stop")
    }

    private fun play(gena: UpnpGena, args: SoapArgs): SoapResult {
        val speed = args["Speed"]?.trim().orEmpty()
        if (speed.isNotEmpty()) {
            val speedValue = speed.toDoubleOrNull()
            if (speedValue == null || speedValue != 1.0) throw UpnpException(UpnpError.PlaySpeedNotSupported)
        }
        val uri = currentUri()
        if (uri.isEmpty()) throw UpnpException(UpnpError.TransitionNotAvailable)
        val accepted = bridgeBoolean("Play") { UpnpPlaybackBridge.play() }
        if (!accepted) {
            UpnpDebug.playback("Play rejected by bridge (no media / unavailable)")
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }
        val state = if (currentState() == "PLAYING") "PLAYING" else "TRANSITIONING"
        gena.notifyAvTransport(state, uri, currentMetadata())
        UpnpDebug.playback("Play accepted state=$state uri=$uri")
        return SoapResult.success(SERVICE, "Play")
    }

    private fun pause(gena: UpnpGena, args: SoapArgs): SoapResult {
        val state = currentState()
        if (state == "PAUSED_PLAYBACK") return SoapResult.success(SERVICE, "Pause")
        if (state != "PLAYING" && state != "TRANSITIONING") {
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }
        val accepted = bridgeBoolean("Pause") { UpnpPlaybackBridge.pause() }
        if (!accepted) throw UpnpException(UpnpError.TransitionNotAvailable)
        gena.notifyAvTransport("PAUSED_PLAYBACK", currentUri(), currentMetadata())
        UpnpDebug.playback("Pause accepted")
        return SoapResult.success(SERVICE, "Pause")
    }

    private fun seek(gena: UpnpGena, args: SoapArgs): SoapResult {
        val unit = args.requireNonEmpty("Unit").uppercase(Locale.US)
        val targetRaw = args.requireNonEmpty("Target")
        if (currentUri().isEmpty()) throw UpnpException(UpnpError.TransitionNotAvailable)
        val state = currentState()
        if (state == "NO_MEDIA_PRESENT") {
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }

        val targetMs: Long = when (unit) {
            "REL_TIME", "ABS_TIME" -> parseAvtDuration(targetRaw)
                ?: throw UpnpException(UpnpError.IllegalSeekTarget)
            "TRACK_NR" -> {
                val track = targetRaw.trim().toIntOrNull() ?: throw UpnpException(UpnpError.IllegalSeekTarget)
                if (track != 1) throw UpnpException(UpnpError.IllegalSeekTarget)
                0L
            }
            else -> throw UpnpException(UpnpError.SeekModeNotSupported)
        }
        val duration = UpnpPlaybackSnapshot.durationMs
        val clamped = if (duration > 0) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
        if (!bridgeBoolean("Seek") { UpnpPlaybackBridge.seek(clamped) }) {
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }
        // Position is not evented (design §5.7); a state refresh is still useful for CPs
        // that only listen to LastChange.
        gena.notifyAvTransport(currentState(), currentUri(), currentMetadata())
        UpnpDebug.playback("Seek unit=$unit target=${formatAvtDuration(clamped)}")
        return SoapResult.success(SERVICE, "Seek")
    }

    private fun next(gena: UpnpGena, args: SoapArgs): SoapResult {
        val stagedUri = nextUri
        if (stagedUri.isNullOrEmpty()) throw UpnpException(UpnpError.TransitionNotAvailable)
        val stagedMetadata = nextMetadata
        nextUri = null
        nextMetadata = null
        try {
            UpnpPlaybackBridge.setUri(stagedUri, stagedMetadata)
        } catch (e: Throwable) {
            UpnpDebug.playback("Next bridge failure", e)
            throw UpnpException(UpnpError.ActionFailed)
        }
        if (!bridgeBoolean("Next") { UpnpPlaybackBridge.play() }) {
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }
        gena.notifyAvTransport("TRANSITIONING", stagedUri, stagedMetadata.orEmpty())
        UpnpDebug.playback("Next -> $stagedUri")
        return SoapResult.success(SERVICE, "Next")
    }

    private fun previous(gena: UpnpGena, args: SoapArgs): SoapResult {
        val uri = currentUri()
        if (uri.isEmpty()) throw UpnpException(UpnpError.TransitionNotAvailable)
        if (!bridgeBoolean("Previous") { UpnpPlaybackBridge.seek(0L) }) {
            throw UpnpException(UpnpError.TransitionNotAvailable)
        }
        gena.notifyAvTransport(currentState(), uri, currentMetadata())
        UpnpDebug.playback("Previous -> restart $uri")
        return SoapResult.success(SERVICE, "Previous")
    }

    private fun setPlayMode(args: SoapArgs): SoapResult {
        val mode = args.requireNonEmpty("NewPlayMode").uppercase(Locale.US)
        if (mode != "NORMAL") throw UpnpException(UpnpError.TransitionNotAvailable)
        return SoapResult.success(SERVICE, "SetPlayMode")
    }

    // ------------------------------------------------------------------ getters

    private fun getMediaInfo(): SoapResult {
        val uri = currentUri()
        val metadata = currentMetadata()
        val duration = formatAvtDuration(UpnpPlaybackSnapshot.durationMs)
        val nrTracks = if (uri.isEmpty()) "0" else "1"
        val medium = if (uri.isEmpty()) "NONE" else "NETWORK"
        return SoapResult.success(
            SERVICE,
            "GetMediaInfo",
            listOf(
                "NrTracks" to nrTracks,
                "MediaDuration" to duration,
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata,
                "NextURI" to nextUri.orEmpty(),
                "NextURIMetaData" to nextMetadata.orEmpty(),
                "PlayMedium" to medium,
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED"
            )
        )
    }

    private fun getTransportInfo(): SoapResult = SoapResult.success(
        SERVICE,
        "GetTransportInfo",
        listOf(
            "CurrentTransportState" to currentState(),
            "CurrentTransportStatus" to "OK",
            "CurrentSpeed" to "1"
        )
    )

    private fun getPositionInfo(): SoapResult {
        val uri = currentUri()
        val metadata = currentMetadata()
        val duration = UpnpPlaybackSnapshot.durationMs
        var position = UpnpPlaybackSnapshot.positionMs.coerceAtLeast(0L)
        if (duration > 0) position = position.coerceAtMost(duration)
        val track = if (uri.isEmpty()) "0" else "1"
        return SoapResult.success(
            SERVICE,
            "GetPositionInfo",
            listOf(
                "Track" to track,
                "TrackDuration" to formatAvtDuration(duration),
                "TrackMetaData" to metadata,
                "TrackURI" to uri,
                "RelTime" to formatAvtDuration(position),
                "AbsTime" to formatAvtDuration(position),
                // 0x7FFFFFFF means "counter position not implemented" (UPnP AV spec).
                "RelCount" to FAST_COUNTER.toString(),
                "AbsCount" to FAST_COUNTER.toString()
            )
        )
    }

    private fun getDeviceCapabilities(): SoapResult = SoapResult.success(
        SERVICE,
        "GetDeviceCapabilities",
        listOf(
            "PlayMedia" to "NETWORK",
            "RecMedia" to "NOT_IMPLEMENTED",
            "RecQualityModes" to "NOT_IMPLEMENTED"
        )
    )

    private fun getTransportSettings(): SoapResult = SoapResult.success(
        SERVICE,
        "GetTransportSettings",
        listOf(
            "PlayMode" to "NORMAL",
            "RecQualityMode" to "NOT_IMPLEMENTED"
        )
    )

    private fun getCurrentTransportActions(): SoapResult = SoapResult.success(
        SERVICE,
        "GetCurrentTransportActions",
        listOf("Actions" to currentTransportActions(currentState()))
    )

    // ------------------------------------------------------------------ helpers

    fun currentTransportActions(state: String): String = when (state) {
        "PLAYING", "TRANSITIONING" -> "Play,Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> "Play,Seek"
        else -> ""
    }

    /** AVTransport duration: "H:MM:SS" (hours not zero padded; no fractional part). */
    fun formatAvtDuration(ms: Long): String {
        val totalSec = (if (ms > 0) ms else 0L) / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return "$hours:${pad2(minutes)}:${pad2(seconds)}"
    }

    /**
     * Parses "H:MM:SS[.mmm]" and tolerates "MM:SS[.mmm]" / "SS" as found in the wild.
     * Returns null for garbage so Seek can answer 711.
     */
    fun parseAvtDuration(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size !in 1..3) return null
        var hours = 0L
        var minutes = 0L
        var seconds = 0.0
        try {
            when (parts.size) {
                1 -> {
                    val sec = parts[0].toDoubleOrNull() ?: return null
                    seconds = sec
                }
                2 -> {
                    minutes = parts[0].toLongOrNull() ?: return null
                    seconds = parts[1].toDoubleOrNull() ?: return null
                }
                else -> {
                    hours = parts[0].toLongOrNull() ?: return null
                    minutes = parts[1].toLongOrNull() ?: return null
                    seconds = parts[2].toDoubleOrNull() ?: return null
                }
            }
        } catch (_: NumberFormatException) {
            return null
        }
        if (hours < 0 || minutes < 0 || seconds < 0) return null
        return (((hours * 3600L) + (minutes * 60L)) * 1000L) + (seconds * 1000.0).toLong()
    }

    private fun currentState(): String {
        val state = UpnpPlaybackSnapshot.transportState
        return if (state.isEmpty()) "NO_MEDIA_PRESENT" else state
    }

    private fun currentUri(): String = UpnpPlaybackSnapshot.uri.orEmpty()

    private fun currentMetadata(): String = UpnpPlaybackSnapshot.metadata.orEmpty()

    /** Bridge calls must never crash an HTTP thread (design: defensive bridging). */
    private inline fun bridgeBoolean(action: String, block: () -> Boolean): Boolean {
        return try {
            block()
        } catch (e: Throwable) {
            UpnpDebug.playback("$action bridge call failed", e)
            false
        }
    }

    private fun isSupportedScheme(uri: String): Boolean {
        val colon = uri.indexOf(':')
        if (colon <= 0) return false
        val scheme = uri.substring(0, colon).lowercase(Locale.US)
        return scheme in SUPPORTED_SCHEMES
    }

    private fun looksLikeImage(metadata: String, uri: String): Boolean {
        if (metadata.contains("object.item.imageItem", ignoreCase = true)) return true
        if (metadata.contains(":image/", ignoreCase = true)) return true
        val lower = uri.lowercase(Locale.US)
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    private fun pad2(value: Long): String = if (value < 10L) "0$value" else value.toString()

    /**
     * Schemes VLC can open. HTTP(S)/RTSP/RTP/UDP/file/content are the task contract;
     * smb/ftp/sftp are included because VLC plays them and strict rejection would break
     * existing control points (design §6.3.3). javascript:/data:/unknown are rejected 714.
     */
    private val SUPPORTED_SCHEMES = setOf(
        "http", "https", "rtsp", "rtp", "udp", "file", "content", "smb", "ftp", "sftp"
    )

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp")
}
