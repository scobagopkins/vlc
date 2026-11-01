package org.videolan.vlc.upnp

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.AndroidDevices
import org.videolan.tools.AppScope
import org.videolan.vlc.PlaybackService
import java.io.*
import java.net.*

object UpnpRendererServer {
    private const val TAG = "VLC/UpnpRenderer"
    private const val SSDP_PORT = 1900
    private const val SSDP_MULTICAST = "239.255.255.250"
    private const val HTTP_PORT = 8188

    private var isRunning = false
    private var scope: CoroutineScope? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: ServerSocket? = null
    private val ssdpSockets = mutableListOf<MulticastSocket>()
    private var udn = "uuid:e9b1d283-7c48-4a92-b430-8d5f3a741c9a"
    private var currentUri: String? = null
    private val debugLogs = java.util.LinkedList<String>()

    private fun log(msg: String) {
        val entry = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg"
        Log.i(TAG, entry)
        synchronized(debugLogs) {
            if (debugLogs.size > 200) debugLogs.removeFirst()
            debugLogs.add(entry)
        }
    }

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("VLCUpnpMulticast")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        val appContext = context.applicationContext ?: context
        scope?.launch { startHttpServer(appContext) }
        scope?.launch { startSsdpListener(appContext) }
        Log.i(TAG, "UPnP MediaRenderer service started on port $HTTP_PORT")
    }

    fun stop() {
        isRunning = false
        scope?.cancel()
        scope = null
        try { httpServer?.close() } catch (_: Exception) {}
        synchronized(ssdpSockets) {
            ssdpSockets.forEach { try { it.close() } catch (_: Exception) {} }
            ssdpSockets.clear()
        }
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun getActiveInterfaces(): List<Pair<NetworkInterface, InetAddress>> {
        val list = mutableListOf<Pair<NetworkInterface, InetAddress>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        list.add(Pair(intf, addr))
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun getLocalIpAddress(): String? {
        return getActiveInterfaces().firstOrNull()?.second?.hostAddress
    }

    private suspend fun startHttpServer(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                httpServer = ServerSocket(HTTP_PORT)
                while (isRunning) {
                    val client = httpServer?.accept() ?: break
                    launch { handleHttpClient(client, context) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "HTTP Server error: ${e.message}")
            }
        }
    }

    private fun handleHttpClient(socket: Socket, context: Context) {
        try {
            socket.soTimeout = 10000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            var prev3 = -1
            var prev2 = -1
            var prev1 = -1
            while (isRunning) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }

            if (headerBytes.size() == 0) return
            val headerText = headerBytes.toString("UTF-8")
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colon = line.indexOf(":")
                if (colon != -1) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }
            val soapAction = headers["soapaction"]?.replace("\"", "") ?: ""
            log("INCOMING: $method $path | soapaction=$soapAction")

            var body = ""
            if (contentLength > 0) {
                val bodyBytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                body = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
            }

            when {
                method == "SUBSCRIBE" -> {
                    val subId = "uuid:${java.util.UUID.randomUUID()}"
                    val respHeader = "HTTP/1.1 200 OK\r\n" +
                            "Server: Linux/1.0 UPnP/1.0 VLC-Android-TV/1.0\r\n" +
                            "SID: $subId\r\n" +
                            "TIMEOUT: Second-1800\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(respHeader.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                method == "UNSUBSCRIBE" -> {
                    val respHeader = "HTTP/1.1 200 OK\r\n" +
                            "Server: Linux/1.0 UPnP/1.0 VLC-Android-TV/1.0\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n"
                    out.write(respHeader.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                path == "/description.xml" || path == "/device.xml" -> {
                    val ip = getLocalIpAddress() ?: "127.0.0.1"
                    val xml = getDeviceXml(ip)
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", xml)
                }
                path == "/avtransport.xml" -> {
                    val xml = getAVTransportScpd()
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", xml)
                }
                path == "/connectionmanager.xml" -> {
                    val xml = getConnectionManagerScpd()
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", xml)
                }
                path == "/renderingcontrol.xml" -> {
                    val xml = getRenderingControlScpd()
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", xml)
                }
                path.startsWith("/control/rc") -> {
                    val responseXml = handleRenderingControl(soapAction, body, context)
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", responseXml)
                }
                path.startsWith("/control/avt") -> {
                    val responseXml = handleAvTransportControl(soapAction, body, context)
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", responseXml)
                }
                path.startsWith("/control/cm") -> {
                    val responseXml = handleConnectionManagerControl()
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", responseXml)
                }
                path == "/debug" || path == "/logs" -> {
                    val logs = synchronized(debugLogs) { debugLogs.joinToString("\n") }
                    sendResponse(out, "200 OK", "text/plain; charset=\"utf-8\"", logs.ifEmpty { "No logs recorded yet" })
                }
                else -> {
                    sendResponse(out, "404 Not Found", "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleHttpClient error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendResponse(out: OutputStream, status: String, contentType: String, content: String) {
        log("OUTGOING: $status ($contentType, ${content.length}b)")
        val bytes = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Server: Linux/1.0 UPnP/1.0 VLC-Android-TV/1.0\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        val regex = Regex("<(?:[a-zA-Z0-9_]+:)?$tagName(?:\\s+[^>]*)?>([\\s\\S]*?)</(?:[a-zA-Z0-9_]+:)?$tagName>", RegexOption.IGNORE_CASE)
        val match = regex.find(xml) ?: return null
        var content = match.groupValues[1].trim()
        if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
            content = content.substring(9, content.length - 3).trim()
        }
        return content.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()
    }

    private fun handleAvTransportControl(soapAction: String, body: String, context: Context): String {
        return when {
            soapAction.contains("SetAVTransportURI") || body.contains("SetAVTransportURI") -> {
                val uri = extractTagValue(body, "CurrentURI")
                if (!uri.isNullOrEmpty()) {
                    currentUri = uri
                    Log.i(TAG, "UPnP SetAVTransportURI: $uri")
                    log("ACTION: SetAVTransportURI received $uri")
                } else {
                    log("ACTION WARN: SetAVTransportURI received but CurrentURI was empty/not found in body (${body.length}b)")
                }
                soapEnvelope("<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Play") || body.contains(":Play") || body.contains("<Play>") -> {
                var uri = currentUri
                if (uri.isNullOrEmpty()) {
                    uri = extractTagValue(body, "CurrentURI")
                    if (!uri.isNullOrEmpty()) {
                        currentUri = uri
                    }
                }
                Log.i(TAG, "UPnP Play requested for URI: $uri")
                log("ACTION: UPnP Play requested (uri=$uri)")
                if (!uri.isNullOrEmpty()) {
                    val finalUri = uri
                    val appContext = context.applicationContext ?: context
                    AppScope.launch(Dispatchers.Main) {
                        try {
                            log("ACTION: Starting playback for $finalUri")
                            val parsedUri = Uri.parse(finalUri)
                            val mw = MLServiceLocator.getAbstractMediaWrapper(parsedUri).apply {
                                val path = parsedUri.path?.lowercase() ?: ""
                                val isVideo = path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") ||
                                              path.endsWith(".mov") || path.endsWith(".webm") || path.endsWith(".ts")
                                if (!isVideo) {
                                    type = MediaWrapper.TYPE_AUDIO
                                    addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
                                } else {
                                    type = MediaWrapper.TYPE_VIDEO
                                }
                            }

                            PlaybackService.start(appContext)
                            val service = PlaybackService.instance ?: PlaybackService.serviceFlow.filterNotNull().first()
                            service.load(listOf(mw), 0)
                            log("ACTION: Successfully loaded & started in PlaybackService")

                            if (AndroidDevices.isAndroidTv && mw.type == MediaWrapper.TYPE_AUDIO) {
                                try {
                                    val intent = Intent().setClassName(appContext, "org.videolan.television.ui.audioplayer.AudioPlayerActivity").apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                    appContext.startActivity(intent)
                                    log("ACTION: AudioPlayerActivity displayed successfully")
                                } catch (e: Exception) {
                                    log("ACTION: Activity notice: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            log("ACTION ERROR Play: ${e.message}")
                        }
                    }
                } else {
                    log("ACTION ERROR: Play requested but currentUri is null/empty")
                }
                soapEnvelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Pause") || body.contains(":Pause") || body.contains("<Pause>") -> {
                Log.i(TAG, "UPnP Pause requested")
                log("ACTION: UPnP Pause requested")
                AppScope.launch(Dispatchers.Main) {
                    PlaybackService.serviceFlow.value?.pause()
                }
                soapEnvelope("<u:PauseResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Stop") || body.contains(":Stop") || body.contains("<Stop>") -> {
                Log.i(TAG, "UPnP Stop requested")
                log("ACTION: UPnP Stop requested")
                AppScope.launch(Dispatchers.Main) {
                    PlaybackService.serviceFlow.value?.stop()
                }
                soapEnvelope("<u:StopResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Seek") || body.contains(":Seek") || body.contains("<Seek>") -> {
                val target = extractTagValue(body, "Target")
                if (target != null) {
                    val parts = target.split(":")
                    if (parts.size == 3) {
                        val h = parts[0].toLongOrNull() ?: 0L
                        val m = parts[1].toLongOrNull() ?: 0L
                        val s = parts[2].toDoubleOrNull() ?: 0.0
                        val millis = (h * 3600 + m * 60 + s).toLong() * 1000L
                        AppScope.launch(Dispatchers.Main) {
                            PlaybackService.serviceFlow.value?.seek(millis)
                        }
                    }
                }
                soapEnvelope("<u:SeekResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("GetTransportInfo") || body.contains("GetTransportInfo") -> {
                val service = PlaybackService.serviceFlow.value
                val state = when {
                    service?.isPlaying == true -> "PLAYING"
                    service?.isPaused == true -> "PAUSED_PLAYBACK"
                    currentUri != null -> "STOPPED"
                    else -> "NO_MEDIA_PRESENT"
                }
                soapEnvelope("""
                    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <CurrentTransportState>$state</CurrentTransportState>
                        <CurrentTransportStatus>OK</CurrentTransportStatus>
                        <CurrentSpeed>1</CurrentSpeed>
                    </u:GetTransportInfoResponse>
                """.trimIndent())
            }
            soapAction.contains("GetMediaInfo") || body.contains("GetMediaInfo") -> {
                val lengthMs = PlaybackService.serviceFlow.value?.length ?: 0L
                val lenStr = formatTrackDuration(lengthMs)
                val curUri = currentUri ?: ""
                soapEnvelope("""
                    <u:GetMediaInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <NrTracks>1</NrTracks>
                        <MediaDuration>$lenStr</MediaDuration>
                        <CurrentURI>$curUri</CurrentURI>
                        <CurrentURIMetaData></CurrentURIMetaData>
                        <NextURI></NextURI>
                        <NextURIMetaData></NextURIMetaData>
                        <PlayMedium>NETWORK</PlayMedium>
                        <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                        <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
                    </u:GetMediaInfoResponse>
                """.trimIndent())
            }
            soapAction.contains("GetDeviceCapabilities") -> {
                soapEnvelope("""
                    <u:GetDeviceCapabilitiesResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <PlayMedia>NETWORK,NONE</PlayMedia>
                        <RecMedia>NOT_IMPLEMENTED</RecMedia>
                        <RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>
                    </u:GetDeviceCapabilitiesResponse>
                """.trimIndent())
            }
            soapAction.contains("GetTransportSettings") -> {
                soapEnvelope("""
                    <u:GetTransportSettingsResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <PlayMode>NORMAL</PlayMode>
                        <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
                    </u:GetTransportSettingsResponse>
                """.trimIndent())
            }
            soapAction.contains("GetPositionInfo") || body.contains("GetPositionInfo") -> {
                val service = PlaybackService.serviceFlow.value
                val timeMs = service?.getTime() ?: 0L
                val lengthMs = service?.length ?: 0L
                val timeStr = formatTrackDuration(timeMs)
                val lenStr = formatTrackDuration(lengthMs)
                val curUri = currentUri ?: ""
                soapEnvelope("""
                    <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <Track>1</Track>
                        <TrackDuration>$lenStr</TrackDuration>
                        <TrackMetaData></TrackMetaData>
                        <TrackURI>$curUri</TrackURI>
                        <RelTime>$timeStr</RelTime>
                        <AbsTime>$timeStr</AbsTime>
                        <RelCount>0</RelCount>
                        <AbsCount>0</AbsCount>
                    </u:GetPositionInfoResponse>
                """.trimIndent())
            }
            else -> {
                soapEnvelope("<u:GenericResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
        }
    }

    private fun handleConnectionManagerControl(): String {
        return soapEnvelope("""
            <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
                <Source></Source>
                <Sink>http-get:*:audio/*:*,http-get:*:video/*:*</Sink>
            </u:GetProtocolInfoResponse>
        """.trimIndent())
    }

    private fun handleRenderingControl(soapAction: String, body: String, context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return when {
            soapAction.contains("GetVolume") || body.contains("GetVolume") -> {
                val currentVol = am?.let {
                    val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (max > 0) (it.getStreamVolume(AudioManager.STREAM_MUSIC) * 100) / max else 100
                } ?: 100
                soapEnvelope("""
                    <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                        <CurrentVolume>$currentVol</CurrentVolume>
                    </u:GetVolumeResponse>
                """.trimIndent())
            }
            soapAction.contains("GetMute") || body.contains("GetMute") -> {
                val isMuted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am?.isStreamMute(AudioManager.STREAM_MUSIC) == true
                } else {
                    am?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                }
                val muteVal = if (isMuted) "1" else "0"
                soapEnvelope("""
                    <u:GetMuteResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                        <CurrentMute>$muteVal</CurrentMute>
                    </u:GetMuteResponse>
                """.trimIndent())
            }
            soapAction.contains("SetVolume") || body.contains("SetVolume") -> {
                val desiredVol = extractTagValue(body, "DesiredVolume")?.toIntOrNull()
                if (desiredVol != null && am != null) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (desiredVol.coerceIn(0, 100) * max) / 100
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                }
                soapEnvelope("<u:SetVolumeResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"/>")
            }
            soapAction.contains("SetMute") || body.contains("SetMute") -> {
                val desiredMute = extractTagValue(body, "DesiredMute")
                if (desiredMute != null && am != null) {
                    val mute = desiredMute == "1" || desiredMute.equals("true", ignoreCase = true)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(AudioManager.STREAM_MUSIC, mute)
                    }
                }
                soapEnvelope("<u:SetMuteResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"/>")
            }
            else -> soapEnvelope("<u:GenericResponse xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\"/>")
        }
    }

    private fun soapEnvelope(bodyContent: String): String {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
                "  <s:Body>\n" +
                "    $bodyContent\n" +
                "  </s:Body>\n" +
                "</s:Envelope>"
    }

    private fun formatTrackDuration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private suspend fun startSsdpListener(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val group = InetAddress.getByName(SSDP_MULTICAST)
                val activeList = getActiveInterfaces()

                for ((netIf, addr) in activeList) {
                    try {
                        val sock = MulticastSocket(SSDP_PORT).apply {
                            reuseAddress = true
                            networkInterface = netIf
                            try {
                                joinGroup(InetSocketAddress(group, SSDP_PORT), netIf)
                            } catch (_: Exception) {
                                joinGroup(group)
                            }
                        }
                        synchronized(ssdpSockets) { ssdpSockets.add(sock) }

                        launch {
                            val buf = ByteArray(4096)
                            while (isRunning) {
                                val packet = DatagramPacket(buf, buf.size)
                                try {
                                    sock.receive(packet)
                                } catch (_: Exception) { break }
                                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                                if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                                    handleSsdpSearch(sock, msg, packet.address, packet.port)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not bind SSDP socket on interface ${netIf.name}: ${e.message}")
                    }
                }

                // Send SSDP alive announcements periodically
                launch {
                    while (isRunning) {
                        sendAliveBroadcast()
                        delay(15_000L)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "SSDP Listener root error: ${e.message}")
            }
        }
    }

    private fun sendAliveBroadcast() {
        val ip = getLocalIpAddress() ?: return
        val targets = listOf(
            "upnp:rootdevice",
            udn,
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )
        for (st in targets) {
            val notifyMsg = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: http://$ip:$HTTP_PORT/device.xml\r\n" +
                    "NT: $st\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Linux/1.0 UPnP/1.0 VLC-Android-TV/1.0\r\n" +
                    "USN: $udn::$st\r\n\r\n"
            val bytes = notifyMsg.toByteArray(Charsets.UTF_8)
            synchronized(ssdpSockets) {
                for (sock in ssdpSockets) {
                    try {
                        val p = DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_MULTICAST), SSDP_PORT)
                        sock.send(p)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleSsdpSearch(sock: DatagramSocket, request: String, senderAddr: InetAddress, senderPort: Int) {
        val ip = getLocalIpAddress() ?: return
        val stLine = request.lines().find { it.startsWith("ST:", ignoreCase = true) } ?: return
        val searchTarget = stLine.substring(3).trim()

        val matchingTargets = mutableListOf<String>()
        when {
            searchTarget == "ssdp:all" -> {
                matchingTargets.add("upnp:rootdevice")
                matchingTargets.add(udn)
                matchingTargets.add("urn:schemas-upnp-org:device:MediaRenderer:1")
                matchingTargets.add("urn:schemas-upnp-org:service:RenderingControl:1")
                matchingTargets.add("urn:schemas-upnp-org:service:ConnectionManager:1")
                matchingTargets.add("urn:schemas-upnp-org:service:AVTransport:1")
            }
            searchTarget == "upnp:rootdevice" -> matchingTargets.add("upnp:rootdevice")
            searchTarget == udn -> matchingTargets.add(udn)
            searchTarget.contains("MediaRenderer") -> matchingTargets.add("urn:schemas-upnp-org:device:MediaRenderer:1")
            searchTarget.contains("RenderingControl") -> matchingTargets.add("urn:schemas-upnp-org:service:RenderingControl:1")
            searchTarget.contains("AVTransport") -> matchingTargets.add("urn:schemas-upnp-org:service:AVTransport:1")
            searchTarget.contains("ConnectionManager") -> matchingTargets.add("urn:schemas-upnp-org:service:ConnectionManager:1")
        }

        for (target in matchingTargets) {
            val response = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "DATE: \r\n" +
                    "EXT:\r\n" +
                    "LOCATION: http://$ip:$HTTP_PORT/device.xml\r\n" +
                    "SERVER: Linux/1.0 UPnP/1.0 VLC-Android-TV/1.0\r\n" +
                    "ST: $target\r\n" +
                    "USN: $udn::$target\r\n\r\n"
            val bytes = response.toByteArray(Charsets.UTF_8)
            try {
                val responsePacket = DatagramPacket(bytes, bytes.size, senderAddr, senderPort)
                sock.send(responsePacket)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send SSDP response: ${e.message}")
            }
        }
    }

    private fun getDeviceXml(ip: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>VLC Android TV</friendlyName>
    <manufacturer>VideoLAN / Custom</manufacturer>
    <manufacturerURL>https://videolan.org</manufacturerURL>
    <modelDescription>VLC Media Player DLNA MediaRenderer</modelDescription>
    <modelName>VLC MediaRenderer</modelName>
    <modelNumber>3.6.5</modelNumber>
    <UDN>$udn</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/renderingcontrol.xml</SCPDURL>
        <controlURL>/control/rc</controlURL>
        <eventSubURL>/events/rc</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/connectionmanager.xml</SCPDURL>
        <controlURL>/control/cm</controlURL>
        <eventSubURL>/events/cm</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/avtransport.xml</SCPDURL>
        <controlURL>/control/avt</controlURL>
        <eventSubURL>/events/avt</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>""".trimIndent()
    }

    private fun getAVTransportScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name></action>
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
    <action><name>Seek</name></action>
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
    <action><name>GetMediaInfo</name></action>
    <action><name>GetDeviceCapabilities</name></action>
    <action><name>GetTransportSettings</name></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>""".trimIndent()
    }

    private fun getConnectionManagerScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>""".trimIndent()
    }

    private fun getRenderingControlScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name></action>
    <action><name>SetVolume</name></action>
    <action><name>GetMute</name></action>
    <action><name>SetMute</name></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>Mute</name><dataType>boolean</dataType></stateVariable>
  </serviceStateTable>
</scpd>""".trimIndent()
    }
}
