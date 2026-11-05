/*****************************************************************************
 * UpnpRendererService.kt
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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.vlc.R
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that gives the DLNA MediaRenderer a lifetime independent of the TV UI.
 *
 * Responsibilities:
 *  - persistent low-importance notification with the description URL and a `Stop` action,
 *  - `PARTIAL_WAKE_LOCK` + `WifiManager.MulticastLock` (multicast filtering is *the* classic
 *    "SSDP works for 30 s then the device disappears" cause on Wi-Fi),
 *  - owns the renderer coroutine scope and the network sub-servers
 *    ([UpnpHttpServer] first, then [SsdpServer] announcements, per the UPnP start-order invariant),
 *  - `ConnectivityManager.NetworkCallback` -> rebuild SSDP sockets + re-announce on any change,
 *  - `START_STICKY`: if the OEM firmware kills the process, the renderer comes back on its own.
 *
 * The service is started only through [UpnpRendererController] (which gates it on the
 * `upnp_renderer_enabled` preference) and is `android:exported="false"`.
 */
class UpnpRendererService : Service() {

    companion object {
        private const val TAG = "VLC/Upnp"
        private const val CHANNEL_ID = "upnp_renderer"
        private const val NOTIFICATION_ID = 4 // PlaybackService uses id 3
        private const val WAKE_LOCK_TAG = "VLC:UpnpRenderer"
        private const val MULTICAST_LOCK_TAG = "VLC:UpnpRenderer"
        private const val MAX_BOOT_RETRIES = 3
        private const val RESCAN_MIN_INTERVAL_MS = 1_000L

        const val ACTION_START = "org.videolan.vlc.upnp.action.START"
        const val ACTION_STOP = "org.videolan.vlc.upnp.action.STOP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ssdp: SsdpServer? = null
    private var http: UpnpHttpServer? = null
    private var gena: UpnpGena? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val lastRescanAt = AtomicLong(0L)

    @Volatile
    private var httpPort: Int = 0

    @Volatile
    private var advertisedIp: String? = null

    private var bootRetries = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 8 requires startForeground() within 5 s of startForegroundService().
        startForegroundCompat(buildNotification(getString(R.string.upnp_renderer_stopped)))
        acquireLocks()
        try {
            UpnpDebug.install(this)
        } catch (e: Exception) {
            Log.w(TAG, "debug install failed: ${e.message}")
        }
        UpnpPlaybackBridge.start(this)
        UpnpPlaybackSnapshot.startSampler(scope)
        UpnpPlaybackSnapshot.setNotifyListener(object : UpnpPlaybackSnapshot.Listener {
            override fun onAvTransportChanged(state: String, uri: String?, metadata: String?) {
                gena?.notifyAvTransport(state, uri, metadata)
            }

            override fun onRenderingControlChanged(volume: Int, mute: Boolean) {
                gena?.notifyRenderingControl(volume, mute)
            }
        })
        // A sticky restart must not resurrect a renderer the user disabled in the meantime.
        if (!UpnpRendererController.isEnabled(this)) {
            Log.i(TAG, "renderer disabled, stopping service")
            stopSelf()
            return
        }
        scope.launch { boot() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!UpnpRendererController.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Renderer outlives the task on purpose: never stop here. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        UpnpPlaybackSnapshot.setNotifyListener(null)
        UpnpPlaybackSnapshot.stopSampler()
        unregisterNetworkCallback()
        // SsdpServer.stop() sends ssdp:byebye (twice per target/interface) before closing sockets.
        try {
            ssdp?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "ssdp stop failed: ${e.message}")
        }
        try {
            http?.close()
        } catch (e: Exception) {
            Log.w(TAG, "http close failed: ${e.message}")
        }
        try {
            gena?.close()
        } catch (e: Exception) {
            Log.w(TAG, "gena close failed: ${e.message}")
        }
        ssdp = null
        http = null
        gena = null
        UpnpPlaybackBridge.stopBridge()
        releaseLocks()
        UpnpRendererController.onServiceStopped()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            stopForeground(true)
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- boot

    private suspend fun boot() {
        try {
            val udn = UpnpRendererController.udn(this)
            val friendlyName = UpnpRendererController.friendlyName(this)
            val description = UpnpDeviceDescription(this, udn, friendlyName)

            // GENA + SOAP handler first: the HTTP server needs them installed before it starts.
            val genaInstance = UpnpGena(scope, this)
            gena = genaInstance
            val soapHandler = UpnpSoapHandler(this, genaInstance, UpnpPlaybackBridge)

            // 1) Bind HTTP first: LOCATION must never be advertised before the description can be
            //    served (otherwise the device is "discovered but cannot connect").
            val httpServer = UpnpHttpServer(scope, this, description)
            http = httpServer
            val port = httpServer.bind()
            if (port <= 0) throw IllegalStateException("HTTP server returned an invalid port")
            httpPort = port
            httpServer.install(genaInstance, soapHandler)

            // 2) Start accepting, so the description URL is already answerable.
            httpServer.start()

            // 3) Open SSDP sockets + announce (alive x3 per interface).
            val ssdpServer = SsdpServer(scope, this, udn, port, description)
            ssdp = ssdpServer
            ssdpServer.start()
            httpServer.attachSsdp(ssdpServer)

            advertisedIp = firstIp(ssdpServer.interfaceLabels())
            bootRetries = 0
            refreshNotification()
            UpnpRendererController.onServiceStarted(port, advertisedIp, friendlyName)
            registerNetworkCallback()
            Log.i(TAG, "renderer running on port $port, ip=${advertisedIp ?: "?"}")
        } catch (e: Exception) {
            onBootFailed(e)
        }
    }

    private fun onBootFailed(e: Exception) {
        Log.e(TAG, "renderer failed to start: ${e.message}", e)
        val friendlyName = UpnpRendererController.friendlyName(this)
        val message = e.message ?: e.javaClass.simpleName
        UpnpRendererController.onServiceError(httpPort, advertisedIp, friendlyName, message)
        refreshNotification(error = message)
        // Partial cleanup so a retry starts from a clean slate. The next boot() creates fresh
        // HTTP/GENA/SOAP instances (UpnpHttpServer cannot be restarted after close()).
        try {
            http?.close()
        } catch (_: Exception) {
        }
        try {
            ssdp?.stop()
        } catch (_: Exception) {
        }
        try {
            gena?.close()
        } catch (_: Exception) {
        }
        http = null
        ssdp = null
        gena = null
        if (bootRetries < MAX_BOOT_RETRIES) {
            bootRetries++
            val delayMs = 30_000L * bootRetries
            scope.launch {
                delay(delayMs)
                if (UpnpRendererController.isEnabled(this@UpnpRendererService)) boot()
            }
        } else {
            Log.e(TAG, "giving up after $MAX_BOOT_RETRIES boot attempts")
        }
    }

    // ---------------------------------------------------------------- network changes

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (networkCallback != null) return
        val connectivityManager = ContextCompat.getSystemService(this, ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = rescan()
            override fun onLost(network: Network) = rescan()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = rescan()
        }
        try {
            val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            ContextCompat.getSystemService(this, ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterNetworkCallback failed: ${e.message}")
        }
    }

    /** Rate-limited SSDP rebuild + re-announce after any network change. */
    private fun rescan() {
        val now = SystemClock.uptimeMillis()
        val previous = lastRescanAt.get()
        if (now - previous < RESCAN_MIN_INTERVAL_MS) return
        if (!lastRescanAt.compareAndSet(previous, now)) return
        scope.launch {
            try {
                // rebuildSockets() = byebye on old sockets, close, re-enumerate, bind, join, alive x3
                ssdp?.rebuildSockets()
                // It runs asynchronously: give it a moment before reading the new interface list.
                delay(750L)
                advertisedIp = firstIp(ssdp?.interfaceLabels())
                refreshNotification()
                UpnpRendererController.onServiceStarted(
                        httpPort,
                        advertisedIp,
                        UpnpRendererController.friendlyName(this@UpnpRendererService)
                )
                Log.i(TAG, "network changed, renderer re-announced (ip=${advertisedIp ?: "?"})")
            } catch (e: Exception) {
                Log.w(TAG, "network rescan failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.upnp_renderer_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            channel.enableLights(false)
            channel.enableVibration(false)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.w(TAG, "createNotificationChannel failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let { PendingIntent.getActivity(this, 0, it, flags) }
        val stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, UpnpRendererService::class.java).setAction(ACTION_STOP),
                flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.upnp_renderer_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .apply { contentIntent?.let { setContentIntent(it) } }
                .addAction(0, getString(R.string.upnp_renderer_notification_stop), stopIntent)
                .build()
    }

    private fun refreshNotification(error: String? = null) {
        val text = when {
            error != null -> getString(R.string.upnp_renderer_error, error)
            httpPort > 0 -> getString(R.string.upnp_renderer_notification_text, "${advertisedIp ?: "0.0.0.0"}:$httpPort")
            else -> getString(R.string.upnp_renderer_stopped)
        }
        try {
            val notification = buildNotification(text)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "refresh notification failed: ${e.message}")
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "typed startForeground failed, retrying untyped: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground failed: ${e2.message}")
            }
        }
    }

    // ---------------------------------------------------------------- locks

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock unavailable: ${e.message}")
        }
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // WifiManager may be null on Ethernet-only hardware; harmless there.
            Log.w(TAG, "multicast lock unavailable: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock release failed: ${e.message}")
        }
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock release failed: ${e.message}")
        }
        wakeLock = null
        multicastLock = null
    }

    // ---------------------------------------------------------------- helpers

    /** Extracts `192.168.1.23` from an interface label such as `eth0/192.168.1.23/24`. */
    private fun firstIp(labels: List<String>?): String? {
        val label = labels?.firstOrNull() ?: return null
        return label.split('/').firstOrNull { part ->
            part.count { it == '.' } == 3 && part.firstOrNull()?.isDigit() == true
        }
    }
}
