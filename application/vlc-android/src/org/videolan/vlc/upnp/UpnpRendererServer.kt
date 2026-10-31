package org.videolan.vlc.upnp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.PlaybackService
import java.io.*
import java.net.*
import java.util.UUID

object UpnpRendererServer {
    private const val TAG = "VLC/UpnpRenderer"
    private const val SSDP_PORT = 1900
    private const val SSDP_MULTICAST = "239.255.255.250"
    private const val HTTP_PORT = 8088

    private var isRunning = false
    private var scope: CoroutineScope? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: ServerSocket? = null
    private var ssdpSocket: MulticastSocket? = null
    private var udn = "uuid:e9b1d283-7c48-4a92-b430-8d5f3a741c9a"
    private var currentUri: String? = null

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("VLCUpnpMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        scope?.launch { startHttpServer(context) }
        scope?.launch { startSsdpListener(context) }
        Log.i(TAG, "UPnP MediaRenderer service started on port $HTTP_PORT")
    }

    fun stop() {
        isRunning = false
        scope?.cancel()
        scope = null
        try { httpServer?.close() } catch (_: Exception) {}
        try { ssdpSocket?.close() } catch (_: Exception) {}
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
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
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val colon = line!!.indexOf(":")
                if (colon != -1) {
                    val k = line!!.substring(0, colon).trim().lowercase()
                    val v = line!!.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }

            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                body = String(buf, 0, readTotal)
            }

            when {
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
                path.startsWith("/control/avt") -> {
                    val soapAction = headers["soapaction"] ?: ""
                    val responseXml = handleAvTransportControl(soapAction, body, context)
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", responseXml)
                }
                path.startsWith("/control/cm") -> {
                    val responseXml = handleConnectionManagerControl()
                    sendResponse(out, "200 OK", "text/xml; charset=\"utf-8\"", responseXml)
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

    private fun handleAvTransportControl(soapAction: String, body: String, context: Context): String {
        return when {
            soapAction.contains("SetAVTransportURI") -> {
                val start = body.indexOf("<CurrentURI>")
                val end = body.indexOf("</CurrentURI>")
                if (start != -1 && end != -1) {
                    val rawUri = body.substring(start + "<CurrentURI>".length, end)
                    val uri = rawUri.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    currentUri = uri
                    Log.i(TAG, "UPnP SetAVTransportURI: $uri")
                }
                soapEnvelope("<u:SetAVTransportURIResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Play") -> {
                Log.i(TAG, "UPnP Play requested for URI: $currentUri")
                currentUri?.let { uri ->
                    CoroutineScope(Dispatchers.Main).launch {
                        MediaUtils.openMediaNoUi(context, android.net.Uri.parse(uri))
                    }
                }
                soapEnvelope("<u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Pause") -> {
                Log.i(TAG, "UPnP Pause requested")
                CoroutineScope(Dispatchers.Main).launch {
                    PlaybackService.serviceFlow.value?.pause()
                }
                soapEnvelope("<u:PauseResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("Stop") -> {
                Log.i(TAG, "UPnP Stop requested")
                CoroutineScope(Dispatchers.Main).launch {
                    PlaybackService.serviceFlow.value?.stop()
                }
                soapEnvelope("<u:StopResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/>")
            }
            soapAction.contains("GetTransportInfo") -> {
                val isPlaying = PlaybackService.serviceFlow.value?.isPlaying == true
                val state = if (isPlaying) "PLAYING" else "STOPPED"
                soapEnvelope("""
                    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <CurrentTransportState>$state</CurrentTransportState>
                        <CurrentTransportStatus>OK</CurrentTransportStatus>
                        <CurrentSpeed>1</CurrentSpeed>
                    </u:GetTransportInfoResponse>
                """.trimIndent())
            }
            soapAction.contains("GetPositionInfo") -> {
                val timeMs = PlaybackService.serviceFlow.value?.getTime() ?: 0L
                val lengthMs = PlaybackService.serviceFlow.value?.length ?: 0L
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
                ssdpSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    joinGroup(group)
                }

                // Send initial alive announcements
                sendAliveBroadcast()

                val buf = ByteArray(2048)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    ssdpSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.startsWith("M-SEARCH", ignoreCase = true)) {
                        handleSsdpSearch(msg, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "SSDP Listener error: ${e.message}")
            }
        }
    }

    private fun sendAliveBroadcast() {
        val ip = getLocalIpAddress() ?: return
        val targets = listOf(
            "upnp:rootdevice",
            udn,
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1"
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
            try {
                val p = DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_MULTICAST), SSDP_PORT)
                ssdpSocket?.send(p)
            } catch (_: Exception) {}
        }
    }

    private fun handleSsdpSearch(request: String, senderAddr: InetAddress, senderPort: Int) {
        val ip = getLocalIpAddress() ?: return
        val stLine = request.lines().find { it.startsWith("ST:", ignoreCase = true) } ?: return
        val searchTarget = stLine.substring(3).trim()

        val matchingTargets = mutableListOf<String>()
        when {
            searchTarget == "ssdp:all" -> {
                matchingTargets.add("upnp:rootdevice")
                matchingTargets.add(udn)
                matchingTargets.add("urn:schemas-upnp-org:device:MediaRenderer:1")
                matchingTargets.add("urn:schemas-upnp-org:service:AVTransport:1")
                matchingTargets.add("urn:schemas-upnp-org:service:ConnectionManager:1")
            }
            searchTarget == "upnp:rootdevice" -> matchingTargets.add("upnp:rootdevice")
            searchTarget == udn -> matchingTargets.add(udn)
            searchTarget.contains("MediaRenderer") -> matchingTargets.add("urn:schemas-upnp-org:device:MediaRenderer:1")
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
                ssdpSocket?.send(responsePacket)
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
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/avtransport.xml</SCPDURL>
        <controlURL>/control/avt</controlURL>
        <eventSubURL>/events/avt</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/connectionmanager.xml</SCPDURL>
        <controlURL>/control/cm</controlURL>
        <eventSubURL>/events/cm</eventSubURL>
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
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
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
}
