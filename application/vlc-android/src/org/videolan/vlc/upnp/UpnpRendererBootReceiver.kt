/*****************************************************************************
 * UpnpRendererBootReceiver.kt
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the DLNA renderer after boot, but only when the user enabled it
 * (`upnp_renderer_enabled`) **and** allowed auto-start (`upnp_renderer_autostart`).
 *
 * Everything is wrapped in `try/catch`: a crash inside a boot receiver shows a system dialog on
 * some TV firmwares, and OEMs are allowed to refuse background foreground-service starts.
 */
class UpnpRendererBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != "android.intent.action.QUICKBOOT_POWERON" &&
                action != "com.htc.intent.action.QUICKBOOT_POWERON") {
            return
        }
        try {
            if (!UpnpRendererController.isEnabled(context)) {
                Log.i(TAG, "boot: renderer disabled, not starting")
                return
            }
            if (!UpnpRendererController.autostart(context)) {
                Log.i(TAG, "boot: autostart disabled, not starting")
                return
            }
            UpnpRendererController.install(context)
            UpnpRendererController.ensureStarted(context)
            Log.i(TAG, "boot: renderer start requested")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on API 31+, OEM background-start bans, etc.
            Log.w(TAG, "boot: renderer could not be started: ${e.message}")
            UpnpRendererController.publish(
                    UpnpRendererStatus(
                            running = false,
                            error = e.message ?: "boot start failed",
                            friendlyName = UpnpRendererController.friendlyName(context)
                    )
            )
        }
    }

    private companion object {
        private const val TAG = "VLC/Upnp"
    }
}
