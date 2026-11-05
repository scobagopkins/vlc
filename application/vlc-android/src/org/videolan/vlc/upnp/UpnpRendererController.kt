/*****************************************************************************
 * UpnpRendererController.kt
 *
 * Copyright В© 2026 VLC authors and VideoLAN
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
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import org.videolan.tools.UPNP_RENDERER_AUTOSTART
import org.videolan.tools.UPNP_RENDERER_ENABLED
import org.videolan.tools.UPNP_RENDERER_NAME
import org.videolan.tools.UPNP_RENDERER_UDN
import java.util.Locale
import java.util.UUID

/**
 * Immutable renderer state published to the UI and to the diagnostics page.
 */
data class UpnpRendererStatus(
        val running: Boolean = false,
        val httpPort: Int = 0,
        val ip: String? = null,
        val error: String? = null,
        val friendlyName: String = ""
)

/**
 * Process-wide public entry point of the DLNA MediaRenderer.
 *
 * Responsibilities:
 *  - start/stop [UpnpRendererService] (foreground service, `START_STICKY`),
 *  - keep the persisted UDN (identity must survive restarts and must never be hard-coded),
 *  - publish [status] / [running] `LiveData` used by the TV preferences screen,
 *  - listen to the `upnp_renderer_*` preferences so both UIs behave identically, with no
 *    duplicated per-fragment side effects.
 */
object UpnpRendererController {

    private const val TAG = "VLC/Upnp"

    /** Friendly name used until the user sets a custom one (`upnp_renderer_name`). */
    const val DEFAULT_FRIENDLY_NAME = "VLC Android TV"

    private const val MAX_FRIENDLY_NAME_LENGTH = 64

    private val _status = MutableLiveData(UpnpRendererStatus())
    val status: LiveData<UpnpRendererStatus> = _status

    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedPrefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ---------------------------------------------------------------- public API

    /**
     * Registers the preference listener once per process. Safe to call from every entry point
     * (Application.onCreate, boot receiver, TV activity, preferences screen).
     */
    @Synchronized
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (listener != null) return
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(app)
        cachedPrefs = sharedPreferences
        val newListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                UPNP_RENDERER_ENABLED -> {
                    val enabled = sp.getBoolean(UPNP_RENDERER_ENABLED, true)
                    mainHandler.post {
                        val ctx = appContext ?: return@post
                        if (enabled) ensureStarted(ctx) else stop(ctx)
                    }
                }
                UPNP_RENDERER_NAME -> {
                    // The friendly name is part of the description document: restart the service
                    // so SSDP re-announces the new name (BOOTID bumps on every start).
                    mainHandler.post {
                        val ctx = appContext ?: return@post
                        if (isEnabled(ctx) && _status.value?.running == true) restart(ctx)
                    }
                }
            }
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(newListener)
        listener = newListener
    }

    /** Starts the renderer if (and only if) it is enabled in the preferences. Idempotent. */
    fun ensureStarted(context: Context) {
        install(context)
        val app = context.applicationContext
        if (!isEnabled(app)) {
            if (_status.value?.running == true) stop(app)
            return
        }
        startService(app)
    }

    /** Starts the renderer unconditionally (used by the user toggle going ON). */
    fun start(context: Context) {
        install(context)
        startService(context.applicationContext)
    }

    /** Stops the renderer; the service sends `ssdp:byebye` and removes its notification. */
    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            app.stopService(Intent(app, UpnpRendererService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService failed: ${e.message}")
        }
        publish(UpnpRendererStatus(running = false, friendlyName = friendlyName(app)))
    }

    /** @return true when `upnp_renderer_enabled` is on (default: on). */
    fun isEnabled(context: Context): Boolean =
            sharedPrefs(context).getBoolean(UPNP_RENDERER_ENABLED, true)

    /** @return true when the renderer may be auto-started after boot (default: on). */
    fun autostart(context: Context): Boolean =
            sharedPrefs(context).getBoolean(UPNP_RENDERER_AUTOSTART, true)

    /** Persisted device identity: `uuid:<UUID>`, generated once and never hard-coded. */
    @Synchronized
    fun udn(context: Context): String {
        val sharedPreferences = sharedPrefs(context)
        var udn = sharedPreferences.getString(UPNP_RENDERER_UDN, null)
        if (udn.isNullOrBlank() || !udn.startsWith("uuid:", ignoreCase = true)) {
            udn = "uuid:" + UUID.randomUUID().toString().lowercase(Locale.US)
            sharedPreferences.edit().putString(UPNP_RENDERER_UDN, udn).apply()
        }
        return udn
    }

    /** Sanitized friendly name from `upnp_renderer_name` (default [DEFAULT_FRIENDLY_NAME]). */
    fun friendlyName(context: Context): String {
        val raw = sharedPrefs(context).getString(UPNP_RENDERER_NAME, null)
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FRIENDLY_NAME
        val sanitized = candidate.filter { it.code in 32..0x10FFFF && it != '\n' && it != '\r' }
                .trim()
                .take(MAX_FRIENDLY_NAME_LENGTH)
        return sanitized.ifEmpty { DEFAULT_FRIENDLY_NAME }
    }

    // ---------------------------------------------------------------- service -> controller

    /** Called by [UpnpRendererService] once HTTP + SSDP are up. */
    internal fun onServiceStarted(port: Int, ip: String?, friendlyName: String) {
        publish(UpnpRendererStatus(running = true, httpPort = port, ip = ip, error = null, friendlyName = friendlyName))
    }

    /** Called by [UpnpRendererService] on any fatal start failure. */
    internal fun onServiceError(port: Int, ip: String?, friendlyName: String, error: String?) {
        publish(UpnpRendererStatus(running = false, httpPort = port, ip = ip, error = error, friendlyName = friendlyName))
    }

    /** Called by [UpnpRendererService] in onDestroy. */
    internal fun onServiceStopped() {
        val ctx = appContext
        publish(UpnpRendererStatus(
                running = false,
                friendlyName = if (ctx != null) friendlyName(ctx) else DEFAULT_FRIENDLY_NAME
        ))
    }

    internal fun publish(status: UpnpRendererStatus) {
        val update = {
            _status.value = status
            _running.value = status.running
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post { update() }
    }

    // ---------------------------------------------------------------- internals

    private fun startService(context: Context) {
        val intent = Intent(context, UpnpRendererService::class.java)
                .setAction(UpnpRendererService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // API 26+ foreground start from the background can be refused on some OEM firmware;
            // fall back to a plain startService and surface the failure in the status row.
            Log.w(TAG, "startForegroundService failed: ${e.message}")
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startService failed: ${e2.message}")
                onServiceError(0, null, friendlyName(context), e2.message ?: "cannot start renderer")
            }
        }
    }

    private fun restart(context: Context) {
        context.stopService(Intent(context, UpnpRendererService::class.java))
        mainHandler.postDelayed({ startService(context) }, 400L)
    }

    private fun sharedPrefs(context: Context): SharedPreferences {
        val cached = cachedPrefs
        if (cached != null) return cached
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        cachedPrefs = sharedPreferences
        return sharedPreferences
    }
}
