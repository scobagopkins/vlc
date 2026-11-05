/*****************************************************************************
 * UpnpPlaybackBridge.kt
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
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.AndroidDevices
import org.videolan.vlc.PlaybackService

/**
 * Serialized bridge between the UPnP control plane (HTTP threads) and VLC (`PlaybackService`,
 * main thread only).
 *
 * Every public function is cheap and non-blocking: it either reads a [UpnpPlaybackSnapshot]
 * `@Volatile` field or enqueues an operation on a `Channel` consumed by a single
 * `Dispatchers.Main.immediate` actor. No `PlaybackService` getter is ever touched from an HTTP
 * thread, and no `runBlocking` is used anywhere.
 *
 * Return values are "accepted by the bridge" flags (used by the SOAP layer to emit 701/501 faults
 * synchronously); the actual asynchronous result is published through
 * [UpnpPlaybackSnapshot] and GENA.
 */
object UpnpPlaybackBridge {

    private const val TAG = "VLC/UpnpPlayback"
    private const val ENSURE_SERVICE_TIMEOUT_MS = 10_000L
    private const val PLAY_START_TIMEOUT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 100L

    /** TV audio player activity, referenced by class name only (television module dependency). */
    private const val TV_AUDIO_ACTIVITY = "org.videolan.television.ui.audioplayer.AudioPlayerActivity"

    private sealed class Op {
        data class Prepare(val uri: String, val metadata: String?) : Op()
        object Play : Op()
        object Pause : Op()
        object Stop : Op()
        data class Seek(val positionMs: Long) : Op()
        data class Volume(val volume: Int) : Op()
        data class Mute(val mute: Boolean) : Op()
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var channel: Channel<Op>? = null
    private var loopJob: Job? = null
    private var actorScope: CoroutineScope? = null

    @Volatile
    private var currentItem: UpnpMediaItem? = null

    @Volatile
    private var currentMetadata: String? = null

    // ---------------------------------------------------------------- lifecycle

    /** Attaches the bridge to the application context and starts the main-thread actor. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        synchronized(this) {
            appContext = ctx
            if (loopJob?.isActive == true) return
            val ops = Channel<Op>(Channel.UNLIMITED)
            channel = ops
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            actorScope = scope
            loopJob = scope.launch {
                for (op in ops) {
                    try {
                        handle(op)
                    } catch (e: Exception) {
                        fail("${op.javaClass.simpleName} failed", e)
                    }
                }
            }
        }
    }

    /** Stops the actor. Does not stop VLC playback (the user may be watching). */
    fun stopBridge() {
        synchronized(this) {
            channel?.close()
            loopJob?.cancel()
            loopJob = null
            channel = null
            actorScope = null
            UpnpVideoSession.active = false
        }
    }

    // ---------------------------------------------------------------- public API

    /** AVTransport `SetAVTransportURI`: store + parse metadata, never starts playback. */
    fun setUri(uri: String, metadata: String?) {
        val item = UpnpDidlLite.parse(metadata, uri) ?: UpnpMediaItem(uri)
        currentItem = item.copy(uri = uri)
        currentMetadata = metadata
        // Publish the new URI synchronously: the next SOAP request (SetAVTransportURI is almost
        // always followed by GetTransportInfo/GetMediaInfo or Play on another connection) must not
        // observe the previous item while the actor is still queued.
        UpnpPlaybackSnapshot.uri = uri
        UpnpPlaybackSnapshot.metadata = metadata ?: ""
        UpnpPlaybackSnapshot.durationMs = item.durationMs
        UpnpPlaybackSnapshot.mime = item.mime ?: ""
        UpnpPlaybackSnapshot.isVideo = UpnpDidlLite.resolveIsVideo(item)
        UpnpPlaybackSnapshot.positionMs = 0L
        UpnpPlaybackSnapshot.pendingPlay = false
        UpnpPlaybackSnapshot.transportStatus = "OK"
        UpnpPlaybackSnapshot.transportState =
                if (uri.isEmpty()) UpnpPlaybackSnapshot.STATE_NO_MEDIA else UpnpPlaybackSnapshot.STATE_STOPPED
        UpnpPlaybackSnapshot.updatedAt = System.currentTimeMillis()
        enqueue(Op.Prepare(uri, metadata))
    }

    fun play(): Boolean {
        val item = currentItem ?: UpnpPlaybackSnapshot.uri?.let { UpnpMediaItem(it) }
        if (item == null || item.uri.isEmpty()) return false
        return enqueue(Op.Play)
    }

    fun pause(): Boolean {
        val state = UpnpPlaybackSnapshot.transportState
        if (state != UpnpPlaybackSnapshot.STATE_PLAYING && state != UpnpPlaybackSnapshot.STATE_TRANSITIONING &&
                state != UpnpPlaybackSnapshot.STATE_PAUSED) return false
        return enqueue(Op.Pause)
    }

    fun stop(): Boolean {
        if (currentItem == null && UpnpPlaybackSnapshot.uri.isNullOrEmpty()) return false
        return enqueue(Op.Stop)
    }

    fun seek(ms: Long): Boolean {
        if (currentItem == null && UpnpPlaybackSnapshot.uri.isNullOrEmpty()) return false
        return enqueue(Op.Seek(if (ms < 0L) 0L else ms))
    }

    fun getVolume(): Int = readVolume()

    fun setVolume(vol: Int): Boolean = enqueue(Op.Volume(vol.coerceIn(0, 100)))

    fun isMuted(): Boolean = readMuted()

    fun setMuted(mute: Boolean): Boolean = enqueue(Op.Mute(mute))

    fun currentUri(): String? = UpnpPlaybackSnapshot.uri ?: currentItem?.uri

    fun currentMetadata(): String? = UpnpPlaybackSnapshot.metadata ?: currentMetadata

    // ---------------------------------------------------------------- actor

    private fun enqueue(op: Op): Boolean {
        val ops = channel ?: run {
            // Bridge not attached yet: accept and drop (the SOAP layer reports success; the
            // action is idempotent and the next request will be queued normally).
            Log.w(TAG, "bridge not started, dropping $op")
            return true
        }
        return ops.trySend(op).isSuccess
    }

    private suspend fun handle(op: Op) {
        when (op) {
            is Op.Prepare -> handlePrepare(op)
            is Op.Play -> handlePlay()
            is Op.Pause -> handlePause()
            is Op.Stop -> handleStop()
            is Op.Seek -> handleSeek(op.positionMs)
            is Op.Volume -> handleVolume(op.volume)
            is Op.Mute -> handleMute(op.mute)
        }
    }

    private fun handlePrepare(op: Op.Prepare) {
        val parsed = UpnpDidlLite.parse(op.metadata, op.uri)
        val item = (parsed ?: UpnpMediaItem(op.uri)).copy(uri = op.uri)
        currentItem = item
        currentMetadata = op.metadata
        UpnpVideoSession.active = false
        UpnpPlaybackSnapshot.apply {
            uri = item.uri
            metadata = op.metadata ?: ""
            durationMs = item.durationMs
            mime = item.mime ?: ""
            isVideo = UpnpDidlLite.resolveIsVideo(item)
            positionMs = 0L
            pendingPlay = false
            transportStatus = "OK"
            transportState = if (item.uri.isEmpty()) STATE_NO_MEDIA else STATE_STOPPED
            updatedAt = System.currentTimeMillis()
        }
        Log.i(TAG, "prepared ${item.uri} video=${UpnpPlaybackSnapshot.isVideo} duration=${item.durationMs}")
    }

    private suspend fun handlePlay() {
        val item = currentItem ?: return
        if (item.uri.isEmpty()) {
            fail("Play without URI", null)
            return
        }
        val isVideo = UpnpDidlLite.resolveIsVideo(item)
        UpnpPlaybackSnapshot.isVideo = isVideo
        UpnpVideoSession.active = isVideo
        UpnpPlaybackSnapshot.transportState = UpnpPlaybackSnapshot.STATE_TRANSITIONING
        UpnpPlaybackSnapshot.pendingPlay = true

        if (!ensurePlaybackService()) {
            UpnpPlaybackSnapshot.pendingPlay = false
            UpnpVideoSession.active = false
            fail("PlaybackService did not start within ${ENSURE_SERVICE_TIMEOUT_MS / 1000} s", null)
            return
        }
        val service = PlaybackService.instance ?: run {
            UpnpPlaybackSnapshot.pendingPlay = false
            UpnpVideoSession.active = false
            fail("PlaybackService unavailable", null)
            return
        }

        val sameMedia = service.playlistManager.hasCurrentMedia() &&
                service.currentMediaWrapper?.uri?.toString() == item.uri
        val alreadyActive = service.isPlaying || service.isPaused
        if (sameMedia && alreadyActive) {
            // Resume after Pause: do not reload, or playback would restart at 0.
            if (service.isPaused) service.play()
        } else {
            val wrapper = buildWrapper(item, isVideo)
            service.load(listOf(wrapper), 0)
        }

        val started = awaitPlaybackStarted(service)
        UpnpPlaybackSnapshot.pendingPlay = false
        if (started) {
            UpnpVideoSession.active = isVideo
            UpnpPlaybackSnapshot.transportStatus = "OK"
        } else {
            UpnpVideoSession.active = false
        }
        UpnpPlaybackSnapshot.refreshNow()
        presentAudioUiIfNeeded(isVideo)
    }

    private fun handlePause() {
        val service = PlaybackService.instance ?: return
        UpnpPlaybackSnapshot.pendingPlay = false
        try {
            if (service.isPlaying) service.pause()
            UpnpPlaybackSnapshot.transportState = UpnpPlaybackSnapshot.STATE_PAUSED
        } catch (e: Exception) {
            fail("Pause failed", e)
        }
        UpnpPlaybackSnapshot.refreshNow()
    }

    private fun handleStop() {
        UpnpVideoSession.active = false
        UpnpPlaybackSnapshot.pendingPlay = false
        try {
            PlaybackService.instance?.stop()
        } catch (e: Exception) {
            fail("Stop failed", e)
        }
        UpnpPlaybackSnapshot.apply {
            transportState = if (uri.isNullOrEmpty()) STATE_NO_MEDIA else STATE_STOPPED
            transportStatus = "OK"
            positionMs = 0L
            updatedAt = System.currentTimeMillis()
        }
        UpnpPlaybackSnapshot.refreshNow()
    }

    private fun handleSeek(positionMs: Long) {
        val service = PlaybackService.instance ?: return
        try {
            val duration = UpnpPlaybackSnapshot.durationMs
            val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
            service.seek(target, fromUser = false)
            UpnpPlaybackSnapshot.positionMs = target
            UpnpPlaybackSnapshot.refreshNow()
        } catch (e: Exception) {
            fail("Seek failed", e)
        }
    }

    private fun handleVolume(volume: Int) {
        val am = audioManager() ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volume * max / 100f), 0)
            UpnpPlaybackSnapshot.volume = volume
            UpnpPlaybackSnapshot.updatedAt = System.currentTimeMillis()
            UpnpPlaybackSnapshot.refreshNow()
        } catch (e: Exception) {
            fail("setVolume failed", e)
        }
    }

    private fun handleMute(mute: Boolean) {
        val am = audioManager() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            } else {
                am.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
            UpnpPlaybackSnapshot.mute = mute
            UpnpPlaybackSnapshot.updatedAt = System.currentTimeMillis()
            UpnpPlaybackSnapshot.refreshNow()
        } catch (e: Exception) {
            fail("setMute failed", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Starts `PlaybackService` through the project helper (5 s foreground rule safe) + timeout. */
    private suspend fun ensurePlaybackService(): Boolean {
        if (PlaybackService.instance != null) return true
        val ctx = appContext ?: return false
        return try {
            PlaybackService.start(ctx)
            val service = withTimeoutOrNull(ENSURE_SERVICE_TIMEOUT_MS) {
                PlaybackService.serviceFlow.filterNotNull().first()
            }
            service != null
        } catch (e: Exception) {
            Log.w(TAG, "PlaybackService start failed: ${e.message}")
            false
        }
    }

    /** Polls the main thread until VLC reports playing/paused, bounded by [PLAY_START_TIMEOUT_MS]. */
    private suspend fun awaitPlaybackStarted(service: PlaybackService): Boolean {
        val deadline = SystemClock.uptimeMillis() + PLAY_START_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (service.isPlaying || service.isPaused) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun buildWrapper(item: UpnpMediaItem, isVideo: Boolean): MediaWrapper {
        val uri = Uri.parse(item.uri)
        val type = if (isVideo) MediaWrapper.TYPE_VIDEO else MediaWrapper.TYPE_AUDIO
        val title = item.title?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
        val withMetadata: MediaWrapper? = try {
            MLServiceLocator.getAbstractMediaWrapper(
                    uri,
                    -1L,
                    -1f,
                    item.durationMs,
                    type,
                    null,
                    title,
                    -1L,
                    -1L,
                    item.artist,
                    null,
                    -1L,
                    item.album,
                    null,
                    0,
                    0,
                    item.artworkUrl,
                    -1,
                    -1,
                    -1,
                    -1,
                    0L,
                    0L,
                    0L
            )
        } catch (e: Throwable) {
            Log.w(TAG, "metadata wrapper failed, falling back to a bare wrapper: ${e.message}")
            null
        }
        // MediaWrapper is abstract: the locator is the only supported factory.
        val wrapper: MediaWrapper = withMetadata ?: try {
            MLServiceLocator.getAbstractMediaWrapper(uri)
        } catch (e: Throwable) {
            throw IllegalStateException("cannot build MediaWrapper for ${item.uri}", e)
        }
        try {
            wrapper.setType(type)
            if (!title.isNullOrBlank() && wrapper.title.isNullOrEmpty()) wrapper.setTitle(title)
            if (item.durationMs > 0L) wrapper.setLength(item.durationMs)
            item.artist?.takeIf { it.isNotBlank() }?.let { wrapper.setArtist(it) }
            item.artworkUrl?.takeIf { it.isNotBlank() }?.let { wrapper.setArtworkURL(it) }
            if (!isVideo) wrapper.addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "wrapper metadata assignment failed: ${e.message}")
        }
        return wrapper
    }

    /**
     * TV presentation policy.
     *
     * Video is presented by `PlaylistManager.playIndex()` itself (which starts
     * `VideoPlayerActivity`) because [UpnpVideoSession.active] suppresses the background-audio
     * demotion. For audio we may open the TV audio player, but only on API < 29 (background
     * activity starts are restricted later) and only on Android TV; failures are non-fatal.
     */
    private fun presentAudioUiIfNeeded(isVideo: Boolean) {
        if (isVideo) return
        if (!AndroidDevices.isAndroidTv) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val ctx = appContext ?: return
        try {
            val intent = Intent().setClassName(ctx, TV_AUDIO_ACTIVITY)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "TV audio player could not start: ${e.message}")
        }
    }

    private fun readVolume(): Int {
        val am = audioManager() ?: return UpnpPlaybackSnapshot.volume
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) Math.round(current * 100f / max).coerceIn(0, 100) else 100
        } catch (e: Exception) {
            UpnpPlaybackSnapshot.volume
        }
    }

    private fun readMuted(): Boolean {
        val am = audioManager() ?: return UpnpPlaybackSnapshot.mute
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.isStreamMute(AudioManager.STREAM_MUSIC)
            else
                am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        } catch (e: Exception) {
            UpnpPlaybackSnapshot.mute
        }
    }

    private fun audioManager(): AudioManager? = try {
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (_: Exception) {
        null
    }

    private fun fail(message: String, exception: Exception?) {
        val detail = if (exception == null) message else "$message: ${exception.message}"
        Log.w(TAG, detail, exception)
        UpnpPlaybackSnapshot.lastError = detail
        UpnpPlaybackSnapshot.transportStatus = "ERROR_OCCURRED"
        UpnpPlaybackSnapshot.updatedAt = System.currentTimeMillis()
    }
}
