/*****************************************************************************
 * UpnpPlaybackSnapshot.kt
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

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.resources.AppContextProvider
import org.videolan.vlc.PlaybackService
import java.util.Locale

/**
 * Thread-safe, always-readable view of the renderer playback state.
 *
 * This is the **only** thing the HTTP/SOAP/GENA threads may read: every `PlaybackService` getter
 * (`isPlaying`, `isPaused`, `length`, `getTime()`) is `@MainThread` and is therefore sampled by a
 * 1 Hz coroutine that runs on `Dispatchers.Main` ([startSampler]).
 *
 * The network side (UpnpActionsAvTransport / UpnpActionsRenderingControl / UpnpGena) reads the
 * public `@Volatile` fields directly; writes are performed by [UpnpPlaybackBridge] and by this
 * sampler only. The optional [Listener] is used to push GENA events without any polling.
 */
object UpnpPlaybackSnapshot {

    private const val TAG = "VLC/UpnpPlayback"
    private const val SAMPLE_INTERVAL_MS = 1_000L

    const val STATE_NO_MEDIA = "NO_MEDIA_PRESENT"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_PLAYING = "PLAYING"
    const val STATE_PAUSED = "PAUSED_PLAYBACK"
    const val STATE_TRANSITIONING = "TRANSITIONING"

    @Volatile
    var transportState: String = STATE_NO_MEDIA

    @Volatile
    var transportStatus: String = "OK"

    @Volatile
    var uri: String? = null

    @Volatile
    var metadata: String? = null

    @Volatile
    var positionMs: Long = 0L

    @Volatile
    var durationMs: Long = 0L

    @Volatile
    var volume: Int = 50

    @Volatile
    var mute: Boolean = false

    @Volatile
    var isVideo: Boolean = false

    @Volatile
    var mime: String = ""

    @Volatile
    var lastError: String? = null

    @Volatile
    var updatedAt: Long = 0L

    /** Set by the bridge when a `Play` has been dispatched but VLC has not confirmed yet. */
    @Volatile
    var pendingPlay: Boolean = false

    /** GENA push callback; installed by [UpnpRendererService], never by the HTTP layer. */
    interface Listener {
        fun onAvTransportChanged(state: String, uri: String?, metadata: String?)
        fun onRenderingControlChanged(volume: Int, mute: Boolean)
    }

    @Volatile
    private var listener: Listener? = null

    private var samplerJob: Job? = null
    private var lastState: String = STATE_NO_MEDIA
    private var lastUri: String? = null
    private var lastVolume: Int = 50
    private var lastMute: Boolean = false

    fun setNotifyListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Starts the 1 Hz main-thread sampler. Safe to call more than once (idempotent).
     *
     * @param scope a process/service scope; the sampler itself always dispatches on Main.
     */
    fun startSampler(scope: CoroutineScope) {
        synchronized(this) {
            if (samplerJob?.isActive == true) return
            lastState = transportState
            lastUri = uri
            lastVolume = volume
            lastMute = mute
            samplerJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    try {
                        tick()
                    } catch (e: Exception) {
                        Log.w(TAG, "sampler tick failed: ${e.message}")
                    }
                    delay(SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Convenience overload used by the service when only a context is available; the sampler
     * still runs on Main.
     */
    fun startSampler(scope: CoroutineScope, unusedContext: Context?) = startSampler(scope)

    fun stopSampler() {
        synchronized(this) {
            samplerJob?.cancel()
            samplerJob = null
        }
    }

    /** Re-reads the source of truth immediately (used by the bridge after Play/Pause/Seek). */
    fun refreshNow() {
        // Called from the main-thread bridge actor; sample inline so the next SOAP
        // GetPositionInfo agrees with the action that has just been acknowledged.
        try {
            tick()
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    fun reset(error: String? = null) {
        transportState = STATE_NO_MEDIA
        transportStatus = if (error == null) "OK" else "ERROR_OCCURRED"
        uri = null
        metadata = null
        positionMs = 0L
        durationMs = 0L
        isVideo = false
        mime = ""
        pendingPlay = false
        lastError = error
        updatedAt = System.currentTimeMillis()
        val l = listener
        l?.onAvTransportChanged(transportState, null, null)
    }

    /**
     * Samples VLC + AudioManager **on the main thread** and publishes diffs to GENA.
     * Must only be called from the main dispatcher.
     */
    private fun tick() {
        val service = PlaybackService.instance
        val previousState = transportState
        val previousUri = uri
        val previousVolume = volume
        val previousMute = mute

        val newState = when {
            uri.isNullOrEmpty() -> STATE_NO_MEDIA
            pendingPlay && service?.isPlaying != true && service?.isPaused != true -> STATE_TRANSITIONING
            service?.isPlaying == true -> STATE_PLAYING
            service?.isPaused == true -> STATE_PAUSED
            else -> STATE_STOPPED
        }

        if (service != null) {
            val length = service.length
            if (length > 0L) durationMs = length
            val position = service.getTime()
            positionMs = if (durationMs > 0L) position.coerceIn(0L, durationMs) else position.coerceAtLeast(0L)
            if (newState == STATE_PLAYING || newState == STATE_PAUSED) pendingPlay = false
        } else {
            positionMs = 0L
        }

        val audioManager = audioManager()
        if (audioManager != null) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            volume = if (max > 0) ((current * 100f) / max).toInt().coerceIn(0, 100) else 100
            mute = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                else
                    current == 0
            } catch (_: Exception) {
                current == 0
            }
        }

        transportState = newState
        if (newState != STATE_TRANSITIONING && newState != STATE_NO_MEDIA) transportStatus = "OK"
        updatedAt = System.currentTimeMillis()

        val l = listener
        if (l != null) {
            if (newState != previousState || uri != previousUri) {
                l.onAvTransportChanged(newState, uri, metadata)
            }
            if (volume != previousVolume || mute != previousMute) {
                l.onRenderingControlChanged(volume, mute)
            }
        }
        lastState = newState
        lastUri = uri
        lastVolume = volume
        lastMute = mute
    }

    private fun audioManager(): AudioManager? = try {
        AppContextProvider.appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Exception) {
        null
    }

    /** Debug helper for `/debug` and the settings row. */
    fun dump(): String = String.format(
            Locale.US,
            "state=%s status=%s uri=%s pos=%d dur=%d vol=%d mute=%s video=%s err=%s",
            transportState, transportStatus, uri ?: "", positionMs, durationMs, volume, mute, isVideo, lastError ?: "none"
    )
}
