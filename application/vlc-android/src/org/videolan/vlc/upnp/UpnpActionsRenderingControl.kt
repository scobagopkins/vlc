/*
 * UpnpActionsRenderingControl.kt — RenderingControl:1 actions (design doc §4.7.2).
 *
 * Volume/mute go through the bridge's AudioManager helpers (STREAM_MUSIC), never
 * through PlaybackService. Channel is validated: Master is the only real channel,
 * LF/RF are accepted but mapped to Master (with a debug log), anything else -> 402.
 * Volume is 0..100 (ui2); VolumeDB is the linear map -10240..0 used by DLNA control
 * points. Successful changes publish a RenderingControl LastChange via GENA.
 */
package org.videolan.vlc.upnp

import java.util.Locale

object UpnpActionsRenderingControl {

    private const val SERVICE = UpnpConstants.SERVICE_RC
    private const val DB_MIN = -10240
    private const val DB_MAX = 0

    fun handlers(gena: UpnpGena): Map<String, (SoapArgs) -> SoapResult> = mapOf(
        "ListPresets" to { _ -> listPresets() },
        "SelectPreset" to { args -> selectPreset(gena, args) },
        "GetVolume" to { args -> getVolume(args) },
        "SetVolume" to { args -> setVolume(gena, args) },
        "GetMute" to { args -> getMute(args) },
        "SetMute" to { args -> setMute(gena, args) },
        "GetVolumeDB" to { args -> getVolumeDb(args) },
        "SetVolumeDB" to { args -> setVolumeDb(gena, args) },
        "GetVolumeDBRange" to { args -> getVolumeDbRange(args) }
    )

    // ------------------------------------------------------------------ actions

    private fun listPresets(): SoapResult = SoapResult.success(
        SERVICE,
        "ListPresets",
        listOf("CurrentPresetNameList" to "FactoryDefaults")
    )

    private fun selectPreset(gena: UpnpGena, args: SoapArgs): SoapResult {
        validateChannel(args)
        val preset = args.requireNonEmpty("PresetName")
        if (!preset.equals("FactoryDefaults", ignoreCase = true)) throw UpnpException(UpnpError.InvalidArgs)
        if (!bridgeBoolean("SelectPreset setVolume") { UpnpPlaybackBridge.setVolume(50) }) {
            throw UpnpException(UpnpError.ActionFailed)
        }
        bridgeBoolean("SelectPreset setMuted") { UpnpPlaybackBridge.setMuted(false) }
        publish(gena)
        return SoapResult.success(SERVICE, "SelectPreset")
    }

    private fun getVolume(args: SoapArgs): SoapResult {
        validateChannel(args)
        val volume = readVolume()
        return SoapResult.success(SERVICE, "GetVolume", listOf("CurrentVolume" to volume.toString()))
    }

    private fun setVolume(gena: UpnpGena, args: SoapArgs): SoapResult {
        validateChannel(args)
        val desired = args.requireInt("DesiredVolume")
        val clamped = desired.coerceIn(0, 100)
        if (!bridgeBoolean("SetVolume") { UpnpPlaybackBridge.setVolume(clamped) }) {
            throw UpnpException(UpnpError.ActionFailed)
        }
        publish(gena)
        UpnpDebug.playback("SetVolume $desired -> $clamped")
        return SoapResult.success(SERVICE, "SetVolume")
    }

    private fun getMute(args: SoapArgs): SoapResult {
        validateChannel(args)
        val muted = readMute()
        return SoapResult.success(SERVICE, "GetMute", listOf("CurrentMute" to if (muted) "1" else "0"))
    }

    private fun setMute(gena: UpnpGena, args: SoapArgs): SoapResult {
        validateChannel(args)
        val desired = parseBooleanArg(args.requireNonEmpty("DesiredMute"))
            ?: throw UpnpException(UpnpError.InvalidArgs)
        if (!bridgeBoolean("SetMute") { UpnpPlaybackBridge.setMuted(desired) }) {
            throw UpnpException(UpnpError.ActionFailed)
        }
        publish(gena)
        UpnpDebug.playback("SetMute -> $desired")
        return SoapResult.success(SERVICE, "SetMute")
    }

    private fun getVolumeDb(args: SoapArgs): SoapResult {
        validateChannel(args)
        val db = volumeToDb(readVolume())
        return SoapResult.success(SERVICE, "GetVolumeDB", listOf("CurrentVolume" to db.toString()))
    }

    private fun setVolumeDb(gena: UpnpGena, args: SoapArgs): SoapResult {
        validateChannel(args)
        val desiredDb = args.requireInt("DesiredVolume").coerceIn(DB_MIN, DB_MAX)
        val volume = dbToVolume(desiredDb)
        if (!bridgeBoolean("SetVolumeDB") { UpnpPlaybackBridge.setVolume(volume) }) {
            throw UpnpException(UpnpError.ActionFailed)
        }
        publish(gena)
        UpnpDebug.playback("SetVolumeDB $desiredDb -> volume $volume")
        return SoapResult.success(SERVICE, "SetVolumeDB")
    }

    private fun getVolumeDbRange(args: SoapArgs): SoapResult {
        validateChannel(args)
        return SoapResult.success(
            SERVICE,
            "GetVolumeDBRange",
            listOf("MinValue" to DB_MIN.toString(), "MaxValue" to DB_MAX.toString())
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Master is the only "real" channel. LF/RF are accepted but mapped to Master
     * (the TV has a single stereo stream); anything else is 402 Invalid Args.
     */
    private fun validateChannel(args: SoapArgs) {
        val channel = args["Channel"]?.trim().orEmpty()
        if (channel.isEmpty()) return // tolerant: spec default is Master
        val normalized = when (channel.lowercase(Locale.US)) {
            "master" -> "Master"
            "lf", "rf" -> {
                UpnpDebug.soap("Channel=$channel mapped to Master")
                "Master"
            }
            else -> throw UpnpException(UpnpError.InvalidArgs)
        }
        if (normalized != "Master") throw UpnpException(UpnpError.InvalidArgs)
    }

    private fun readVolume(): Int {
        return try {
            UpnpPlaybackBridge.getVolume().coerceIn(0, 100)
        } catch (e: Throwable) {
            UpnpDebug.playback("getVolume bridge failure", e)
            UpnpPlaybackSnapshot.volume.coerceIn(0, 100)
        }
    }

    private fun readMute(): Boolean {
        return try {
            UpnpPlaybackBridge.isMuted()
        } catch (e: Throwable) {
            UpnpDebug.playback("isMuted bridge failure", e)
            UpnpPlaybackSnapshot.mute
        }
    }

    private fun publish(gena: UpnpGena) {
        gena.notifyRenderingControl(readVolume(), readMute())
    }

    /** Linear mapping used by the design: 0 -> -10240, 100 -> 0. */
    fun volumeToDb(volume: Int): Int {
        val clamped = volume.coerceIn(0, 100)
        return DB_MIN + (clamped * (-DB_MIN)) / 100
    }

    /** Inverse mapping, clamped to the advertised range. */
    fun dbToVolume(db: Int): Int {
        val clamped = db.coerceIn(DB_MIN, DB_MAX)
        return (((clamped - DB_MIN) * 100) / (-DB_MIN)).coerceIn(0, 100)
    }

    private fun parseBooleanArg(raw: String): Boolean? {
        return when (raw.trim().lowercase(Locale.US)) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }

    private inline fun bridgeBoolean(action: String, block: () -> Boolean): Boolean {
        return try {
            block()
        } catch (e: Throwable) {
            UpnpDebug.playback("$action bridge call failed", e)
            false
        }
    }
}
