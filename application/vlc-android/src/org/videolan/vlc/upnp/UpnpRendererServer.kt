/*****************************************************************************
 * UpnpRendererServer.kt
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
import org.videolan.resources.AppContextProvider

/**
 * Compatibility facade kept for [org.videolan.television.ui.MainTvActivity] (and any other legacy
 * caller) which invokes `UpnpRendererServer.start(this)`.
 *
 * The whole implementation now lives in [UpnpRendererController] / [UpnpRendererService]; this file
 * must stay tiny: it only forwards to the controller so the TV module compiles unchanged.
 */
object UpnpRendererServer {

    /**
     * Legacy entry point. Starts the renderer only when it is enabled in the preferences; it is
     * a no-op otherwise (feature-gated, see [UpnpRendererController.ensureStarted]).
     */
    @JvmStatic
    fun start(context: Context) = UpnpRendererController.ensureStarted(context)

    /**
     * Legacy stop. The context parameter is optional so the old `stop()` call site keeps
     * compiling; when null the process-wide application context is used.
     */
    @JvmStatic
    fun stop(context: Context? = null) {
        val ctx = context ?: AppContextProvider.appContext
        UpnpRendererController.stop(ctx)
    }
}
