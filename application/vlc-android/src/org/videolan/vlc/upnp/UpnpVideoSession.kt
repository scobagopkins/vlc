/*****************************************************************************
 * UpnpVideoSession.kt
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

/**
 * Tiny process-wide flag shared between [UpnpPlaybackBridge] and `PlaylistManager`.
 *
 * `PlaylistManager.playIndex()` demotes every video started while the VLC UI is not in the
 * foreground to *background audio* (`videoBackground = true` + `MEDIA_FORCE_AUDIO`) and therefore
 * never starts `VideoPlayerActivity`. That is the right behaviour for a phone that keeps playing
 * video in the background, but it makes a DLNA video cast look like "audio only, no picture".
 *
 * The bridge raises this flag just before `PlaybackService.load(...)` for a video item, so
 * `PlaylistManager` skips the background demotion and uses its normal video path (which starts
 * `VideoPlayerActivity`). It is reset on stop and whenever a new audio item is prepared.
 *
 * Default [active] = false, so stock VLC behaviour is byte-for-byte unchanged unless a UPnP
 * renderer video session is being started.
 */
object UpnpVideoSession {
    @Volatile
    var active: Boolean = false
}
