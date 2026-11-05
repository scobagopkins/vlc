/*
 * SsdpServer.kt — SSDP discovery for the DLNA MediaRenderer (design doc §2).
 *
 * One MulticastSocket per IPv4 network interface, unbound constructor first so
 * SO_REUSEADDR is applied BEFORE bind (legacy defect D4). Every M-SEARCH response,
 * ssdp:alive and ssdp:byebye is sent from the per-interface socket with that
 * interface's own IPv4 in LOCATION (legacy defect D5/D11), with correct USN rules
 * (bare UDN for ST/NT == udn, "udn::target" otherwise — legacy defect D6), a real
 * DATE (D7), EXT:, BOOTID.UPNP.ORG / CONFIGID.UPNP.ORG and TTL 4.
 *
 * Public API used by the service:
 *   start()            blocking interface enumeration + bind; call from an IO thread.
 *   stop()             byebye (once) + close all sockets + cancel loops; idempotent.
 *   announce()         re-send ssdp:alive for every target/interface (async).
 *   rebuildSockets()   byebye on old sockets -> close -> re-enumerate -> alive x3 (async).
 *   byebye()           blocking ssdp:byebye x2 for every target/interface.
 *   interfaceLabels()  ["eth0/192.168.1.23", ...] for the status row/debug page.
 *   interfaceInfos()   structured interface state for /debug.
 *   multicastSelfTest() sends a real M-SEARCH and counts our own responses (blocking ~2 s).
 *
 * Assumption: multicast lock / wake lock are owned by the foreground service, not here
 * (per the task contract). All sockets share port 1900 and coexist with an OEM SSDP
 * responder thanks to SO_REUSEADDR.
 */
package org.videolan.vlc.upnp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class SsdpServer(
    private val scope: CoroutineScope,
    private val context: Context,
    private val udn: String,
    private val httpPort: Int,
    private val description: UpnpDeviceDescription? = null
) {

    /**
     * One bound SSDP socket and the IPv4 address advertised for it.
     * Deliberately a regular class: instances are keys of a ConcurrentHashMap and the
     * mutable counters must not participate in equals/hashCode.
     */
    class SsdpIface(
        val nif: NetworkInterface,
        val address: Inet4Address,
        val prefixLength: Int,
        val socket: MulticastSocket
    ) {
        @Volatile
        var lastSearchAt: Long = 0L

        @Volatile
        var aliveTx: Long = 0L

        val label: String
            get() = if (prefixLength > 0) "${nif.name}/${address.hostAddress}/$prefixLength" else "${nif.name}/${address.hostAddress}"
    }

    private val running = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<SsdpIface>()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val receiveJobs = java.util.concurrent.ConcurrentHashMap<SsdpIface, Job>()

    /** Exactly six targets: rootdevice, UDN, device, AVT, RC, CM (oldest-cheap-first). */
    private val allTargets: List<String> by lazy {
        listOf(
            ROOT_DEVICE,
            udn,
            UpnpConstants.DEVICE_TYPE,
            UpnpConstants.SERVICE_AVT,
            UpnpConstants.SERVICE_RC,
            UpnpConstants.SERVICE_CM
        )
    }
    private val announceMutex = Mutex()
    private val group: InetAddress by lazy { InetAddress.getByName(UpnpConstants.SSDP_ADDR) }

    /** Incremented once per service start; used by control points to drop stale caches. */
    private val bootId: Int by lazy { UpnpConstants.bumpBootId(context) }

    @Volatile
    private var byebyeSent = false

    private val random = Random()

    private val searchWindowStart = AtomicInteger(0)
    private val searchWindowCount = AtomicInteger(0)

    private val dedupLock = Any()
    private val dedupMap = HashMap<String, Long>(32)

    // ------------------------------------------------------------------ lifecycle

    /**
     * Enumerates interfaces, binds one socket per usable IPv4 interface and starts the
     * receive/alive loops. Blocking network setup; call from an IO thread.
     * Safe to call after a failed earlier start (sockets are re-enumerated by
     * [rebuildSockets] on network changes).
     */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            UpnpDebug.log("Ssdp", "start(): already running")
            return
        }
        byebyeSent = false
        UpnpDebug.log("Ssdp", "start(): udn=$udn httpPort=$httpPort")
        // openSockets() binds synchronously; the initial alive x3 is sent by announceLoop
        // so the ordering (bound -> announced) is guaranteed.
        openSockets(announceAfterward = false)
        if (sockets.isEmpty()) {
            UpnpDebug.log("Ssdp", "start(): no usable IPv4 interface — discovery degraded")
        }
        startLoops()
    }

    /** Sends byebye (if not already sent), closes sockets and cancels all loops. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        UpnpDebug.log("Ssdp", "stop(): sending byebye and closing ${sockets.size} socket(s)")
        if (!byebyeSent) {
            byebyeSent = true
            byebye()
        }
        closeSockets()
        cancelReceiveLoops()
        jobs.forEach { it.cancel() }
        jobs.clear()
        UpnpDebug.setInterfaces(emptyList())
        UpnpDebug.log("Ssdp", "stopped")
    }

    fun isRunning(): Boolean = running.get()

    /** Re-sends ssdp:alive for all targets/interfaces, asynchronously. */
    fun announce() {
        if (!running.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                sendAliveAll()
            } catch (e: Throwable) {
                UpnpDebug.log("Ssdp", "announce failed", e)
            }
        }
    }

    /** Byebye on the old sockets, close, re-enumerate, bind, join, alive x3. */
    fun rebuildSockets() {
        if (!running.get()) return
        scope.launch(Dispatchers.IO) {
            try {
                UpnpDebug.log("Ssdp", "rebuildSockets(): network change")
                UpnpDebug.inc("net.change")
                byebye()
                closeSockets()
                cancelReceiveLoops()
                openSockets(announceAfterward = true)
                startReceiveLoops()
            } catch (e: Throwable) {
                UpnpDebug.log("Ssdp", "rebuildSockets failed", e)
            }
        }
    }

    /** Blocking ssdp:byebye, twice per target per interface (no LOCATION/SERVER). */
    fun byebye() {
        if (sockets.isEmpty()) return
        for (round in 0 until 2) {
            sockets.forEach { iface ->
                allTargets.forEach { nt ->
                    val usn = usnFor(nt)
                    val msg = buildString(256) {
                        append("NOTIFY * HTTP/1.1\r\n")
                        append("HOST: ").append(UpnpConstants.SSDP_ADDR).append(':').append(UpnpConstants.SSDP_PORT).append("\r\n")
                        append("NT: ").append(nt).append("\r\n")
                        append("NTS: ssdp:byebye\r\n")
                        append("USN: ").append(usn).append("\r\n")
                        append("BOOTID.UPNP.ORG: ").append(bootId).append("\r\n")
                        append("\r\n")
                    }
                    sendRaw(iface, group, UpnpConstants.SSDP_PORT, msg, "byebye")
                }
            }
            if (round == 0) sleepQuietly(60L)
        }
    }

    // ------------------------------------------------------------------ interfaces

    /** ["eth0/192.168.1.23", ...] for the notification/status row. */
    fun interfaceLabels(): List<String> = sockets.map { it.label }

    /** Structured interface state for /debug. */
    fun interfaceInfos(): List<UpnpDebug.InterfaceInfo> = sockets.map { iface ->
        UpnpDebug.InterfaceInfo(
            name = iface.nif.name,
            ip = iface.address.hostAddress ?: "",
            prefixLength = iface.prefixLength,
            up = safeIsUp(iface.nif),
            ssdpSocket = !iface.socket.isClosed,
            aliveTx = iface.aliveTx,
            location = UpnpConstants.locationUrl(iface.address.hostAddress ?: "", httpPort)
        )
    }

    private fun openSockets(announceAfterward: Boolean) {
        val candidates = candidateInterfaces()
        val candidateLabels = candidates.joinToString(", ") { "${it.first.name}/${it.second.hostAddress}" }
        UpnpDebug.log("Ssdp", "candidate interfaces: $candidateLabels")
        for ((nif, addr) in candidates) {
            try {
                val sock = openSocket(nif, addr)
                sockets.add(SsdpIface(nif, addr, prefixLengthOf(nif, addr), sock))
                UpnpDebug.inc("ssdp.socket.up")
                UpnpDebug.log("Ssdp", "socket bound on ${nif.name}/${addr.hostAddress} joined ${UpnpConstants.SSDP_ADDR}")
            } catch (e: Throwable) {
                UpnpDebug.inc("ssdp.socket.fail")
                UpnpDebug.log("Ssdp", "bind failed on ${nif.name}/${addr.hostAddress}: ${e.javaClass.simpleName}: ${e.message}")
                if (running.get()) scheduleSocketRetry(nif, addr)
            }
        }
        publishInterfaces()
        if (announceAfterward && sockets.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                try {
                    sendAliveAll()
                } catch (e: Throwable) {
                    UpnpDebug.log("Ssdp", "initial alive failed", e)
                }
            }
        }
    }

    private fun scheduleSocketRetry(nif: NetworkInterface, addr: Inet4Address) {
        jobs.add(scope.launch(Dispatchers.IO) {
            delay(2_000L)
            if (!running.get()) return@launch
            if (sockets.any { it.nif.name == nif.name && it.address == addr }) return@launch
            try {
                val sock = openSocket(nif, addr)
                sockets.add(SsdpIface(nif, addr, prefixLengthOf(nif, addr), sock))
                UpnpDebug.inc("ssdp.socket.retry.ok")
                UpnpDebug.log("Ssdp", "socket retry ok on ${nif.name}/${addr.hostAddress}")
                publishInterfaces()
                startReceiveLoops()
                sendAliveAll()
            } catch (e: Throwable) {
                UpnpDebug.inc("ssdp.socket.retry.fail")
                UpnpDebug.log("Ssdp", "socket retry failed on ${nif.name}/${addr.hostAddress}", e)
            }
        })
    }

    /**
     * One socket per usable IPv4 interface, SO_REUSEADDR applied before bind.
     * Multiple Android interfaces can share port 1900 with the OEM SSDP stack.
     */
    private fun openSocket(nif: NetworkInterface, addr: Inet4Address): MulticastSocket {
        val socket = MulticastSocket(null as SocketAddress?)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(UpnpConstants.SSDP_PORT))
            socket.networkInterface = nif
            socket.timeToLive = UpnpConstants.SSDP_TTL
            // setLoopbackMode(true) disables IP_MULTICAST_LOOP for our own sends; a
            // control point running on the TV uses its own socket, and our /self-test
            // probe is sent from a temporary socket (loopback stays enabled there).
            socket.setLoopbackMode(true)
            socket.joinGroup(InetSocketAddress(group, UpnpConstants.SSDP_PORT), nif)
            socket.soTimeout = 0
            return socket
        } catch (e: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            throw e
        }
    }

    private fun closeSockets() {
        sockets.forEach { iface ->
            try {
                iface.socket.close()
            } catch (_: Throwable) {
            }
        }
        sockets.clear()
    }

    private fun publishInterfaces() {
        UpnpDebug.setInterfaces(sockets.map { iface ->
            UpnpDebug.InterfaceInfo(
                name = iface.nif.name,
                ip = iface.address.hostAddress ?: "",
                prefixLength = iface.prefixLength,
                up = safeIsUp(iface.nif),
                ssdpSocket = !iface.socket.isClosed,
                aliveTx = iface.aliveTx,
                location = UpnpConstants.locationUrl(iface.address.hostAddress ?: "", httpPort)
            )
        })
        // Keep the debug runtime snapshot truthful even when the service/controller layer
        // does not publish its own (e.g. during early boot or a unit test harness).
        val current = UpnpDebug.runtime()
        UpnpDebug.setRuntime(
            current.copy(
                ssdpPort = UpnpConstants.SSDP_PORT,
                bootId = bootId,
                udn = if (current.udn.isEmpty()) udn else current.udn,
                friendlyName = if (current.friendlyName.isEmpty()) {
                    description?.friendlyName ?: ""
                } else {
                    current.friendlyName
                }
            )
        )
    }

    /**
     * IPv4 candidates, deterministic order (Ethernet before Wi-Fi), one address per
     * interface, cellular/VPN/Wi-Fi-Direct/dummy/point-to-point interfaces excluded.
     */
    private fun candidateInterfaces(): List<Pair<NetworkInterface, Inet4Address>> {
        val preference = UpnpConstants.ifacePreference(context)
        val interfaces = ArrayList<NetworkInterface>()
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (enumeration.hasMoreElements()) interfaces.add(enumeration.nextElement())
        } catch (e: Throwable) {
            UpnpDebug.log("Ssdp", "interface enumeration failed", e)
            return emptyList()
        }

        val result = ArrayList<Pair<NetworkInterface, Inet4Address>>()
        for (nif in interfaces) {
            if (!isUsableInterface(nif)) continue
            if (preference == "eth" && !nif.name.startsWith("eth")) continue
            if (preference == "wlan" && !nif.name.startsWith("wlan")) continue
            val address = firstIpv4(nif) ?: continue
            result.add(nif to address)
        }
        result.sortBy { (nif, _) ->
            when {
                nif.name.startsWith("eth") -> 0
                nif.name.startsWith("wlan") -> 1
                else -> 2
            }
        }
        return result
    }

    private fun isUsableInterface(nif: NetworkInterface): Boolean {
        return try {
            val name = nif.name ?: return false
            if (!nif.isUp || nif.isLoopback || nif.isVirtual || nif.isPointToPoint) return false
            val blocked = listOf("rmnet", "tun", "p2p", "dummy", "ipsec", "ppp", "clat", "v4-")
            if (blocked.any { name.startsWith(it) }) return false
            true
        } catch (_: SocketException) {
            false
        }
    }

    private fun firstIpv4(nif: NetworkInterface): Inet4Address? {
        return try {
            val addresses = nif.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress && !addr.isAnyLocalAddress) {
                    return addr
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun prefixLengthOf(nif: NetworkInterface, address: Inet4Address): Int {
        return try {
            nif.interfaceAddresses.firstOrNull { it.address == address }?.networkPrefixLength?.toInt() ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun safeIsUp(nif: NetworkInterface): Boolean = try {
        nif.isUp
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------------ receive loop

    private fun startReceiveLoops() {
        sockets.forEach { iface ->
            val existing = receiveJobs[iface]
            if (existing != null && existing.isActive) return@forEach
            val job = scope.launch(Dispatchers.IO) { receiveLoop(iface) }
            receiveJobs[iface] = job
        }
    }

    private fun cancelReceiveLoops() {
        receiveJobs.values.forEach { it.cancel() }
        receiveJobs.clear()
    }

    private fun startLoops() {
        startReceiveLoops()
        jobs.add(scope.launch(Dispatchers.IO) {
            try {
                announceLoop()
            } catch (e: Throwable) {
                if (running.get()) UpnpDebug.log("Ssdp", "alive loop failed", e)
            }
        })
    }

    private suspend fun receiveLoop(iface: SsdpIface) {
        val buffer = ByteArray(4096)
        UpnpDebug.log("Ssdp", "receive loop started on ${iface.label}")
        while (running.get() && !iface.socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                iface.socket.receive(packet)
                handleDatagram(iface, packet)
            } catch (e: SocketTimeoutException) {
                // soTimeout is 0 (infinite); tolerate anyway and keep the loop alive.
            } catch (e: SocketException) {
                if (!running.get() || iface.socket.isClosed) break
                UpnpDebug.log("Ssdp", "receive error on ${iface.label}: ${e.message}")
                delay(200L)
            } catch (e: IOException) {
                if (!running.get() || iface.socket.isClosed) break
                UpnpDebug.inc("ssdp.receive.fail")
                UpnpDebug.log("Ssdp", "receive failed on ${iface.label}: ${e.message}")
                delay(200L)
            } catch (e: Throwable) {
                UpnpDebug.log("Ssdp", "unexpected receive failure on ${iface.label}", e)
                delay(200L)
            }
        }
        UpnpDebug.log("Ssdp", "receive loop ended on ${iface.label}")
    }

    private fun handleDatagram(iface: SsdpIface, packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        if (!text.startsWith("M-SEARCH", ignoreCase = true)) return
        UpnpDebug.inc("ssdp.search.rx")
        if (!allowSearch()) {
            UpnpDebug.inc("ssdp.search.ratelimited")
            return
        }
        val request = ParsedSearch.parse(text) ?: run {
            UpnpDebug.inc("ssdp.search.ignored")
            UpnpDebug.log("Ssdp", "ignored malformed M-SEARCH from ${packet.address?.hostAddress}")
            return
        }
        val source = packet.address ?: return
        val sourcePort = packet.port
        if (sourcePort <= 0) {
            UpnpDebug.inc("ssdp.search.ignored")
            return
        }

        val targets = matchingTargets(request.searchTarget)
        if (targets.isEmpty()) {
            UpnpDebug.inc("ssdp.search.ignored")
            UpnpDebug.log("Ssdp", "M-SEARCH <- ${source.hostAddress} ST=${request.searchTarget} -> no match")
            return
        }
        if (isDuplicate(iface, source, request.searchTarget)) {
            UpnpDebug.inc("ssdp.search.dup")
            return
        }
        iface.lastSearchAt = System.currentTimeMillis()
        UpnpDebug.log(
            "Ssdp",
            "M-SEARCH <- ${source.hostAddress}:$sourcePort ST=${request.searchTarget} MX=${request.mx} " +
                "iface=${iface.label} -> ${targets.size} response(s)"
        )

        // Spread response storms: jitter 0..min(MX*1000, 1000) ms, then send each target
        // as its own datagram from the receiving socket/interface.
        val jitter = if (request.mx >= 1) {
            random.nextInt(min((request.mx * 1000L).toInt(), UpnpConstants.SSDP_RESPONSE_MAX_JITTER_MS.toInt()) + 1)
        } else {
            0
        }
        scope.launch(Dispatchers.IO) {
            if (jitter > 0) delay(jitter.toLong())
            targets.forEach { target ->
                if (running.get() && !iface.socket.isClosed) {
                    sendSearchResponse(iface, source, sourcePort, target)
                }
            }
        }
    }

    private fun allowSearch(): Boolean {
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val window = searchWindowStart.get()
        if (window != nowSec) {
            searchWindowStart.set(nowSec)
            searchWindowCount.set(0)
        }
        return searchWindowCount.incrementAndGet() <= UpnpConstants.SSDP_SEARCH_RATE_LIMIT
    }

    /** Same source + ST seen on two joined sockets within 150 ms is answered once. */
    private fun isDuplicate(iface: SsdpIface, source: InetAddress, st: String): Boolean {
        val key = "${source.hostAddress}|$st"
        val now = System.currentTimeMillis()
        return synchronized(dedupLock) {
            val previous = dedupMap[key]
            dedupMap[key] = now
            if (dedupMap.size > 256) {
                val iterator = dedupMap.entries.iterator()
                while (iterator.hasNext()) {
                    if (now - iterator.next().value > 5_000L) iterator.remove()
                }
            }
            if (previous == null || now - previous > UpnpConstants.SSDP_DEDUP_WINDOW_MS) {
                false
            } else {
                UpnpDebug.log("Ssdp", "dedup ${source.hostAddress} ST=$st (seen on another socket)")
                true
            }
        }
    }

    // ------------------------------------------------------------------ packet builders

    private fun matchingTargets(st: String): List<String> {
        val value = st.trim()
        return when {
            value.equals("ssdp:all", ignoreCase = true) -> allTargets
            value.equals("upnp:rootdevice", ignoreCase = true) -> listOf(ROOT_DEVICE)
            value.equals(udn, ignoreCase = true) -> listOf(udn)
            value == UpnpConstants.DEVICE_TYPE -> listOf(UpnpConstants.DEVICE_TYPE)
            value == UpnpConstants.SERVICE_AVT -> listOf(UpnpConstants.SERVICE_AVT)
            value == UpnpConstants.SERVICE_RC -> listOf(UpnpConstants.SERVICE_RC)
            value == UpnpConstants.SERVICE_CM -> listOf(UpnpConstants.SERVICE_CM)
            else -> emptyList()
        }
    }

    /** Bare UDN for the uuid target, "udn::target" otherwise (UPnP DA 1.0 tables). */
    private fun usnFor(target: String): String = if (target == udn) udn else "$udn::$target"

    private fun sendSearchResponse(iface: SsdpIface, source: InetAddress, sourcePort: Int, target: String) {
        val message = buildString(512) {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=").append(UpnpConstants.MAX_AGE_SEC).append("\r\n")
            append("DATE: ").append(httpDate()).append("\r\n")
            append("EXT:\r\n")
            append("LOCATION: ").append(UpnpConstants.locationUrl(iface.address.hostAddress ?: "", httpPort)).append("\r\n")
            append("SERVER: ").append(UpnpConstants.SERVER_HEADER).append("\r\n")
            append("ST: ").append(target).append("\r\n")
            append("USN: ").append(usnFor(target)).append("\r\n")
            append("BOOTID.UPNP.ORG: ").append(bootId).append("\r\n")
            append("CONFIGID.UPNP.ORG: ").append(UpnpConstants.CONFIG_ID).append("\r\n")
            append("\r\n")
        }
        if (sendRaw(iface, source, sourcePort, message, "response")) {
            UpnpDebug.inc("ssdp.search.matched")
        }
    }

    private suspend fun announceLoop() {
        // Rapid initial announcements (packet-loss hardening), then ~1/3 of max-age.
        try {
            sendAliveAll()
            delay(UpnpConstants.ANNOUNCE_SPACING_MS)
            sendAliveAll()
            delay(UpnpConstants.ANNOUNCE_SPACING_MS)
            sendAliveAll()
        } catch (e: Throwable) {
            UpnpDebug.log("Ssdp", "initial announcement failed", e)
        }
        while (running.get()) {
            delay(UpnpConstants.ALIVE_INTERVAL_MS)
            if (!running.get()) break
            try {
                sendAliveAll()
            } catch (e: Throwable) {
                UpnpDebug.log("Ssdp", "periodic alive failed", e)
            }
        }
    }

    private suspend fun sendAliveAll() {
        announceMutex.withLock {
            val snapshot = sockets.toList()
            for (iface in snapshot) {
                if (!running.get()) return@withLock
                val location = UpnpConstants.locationUrl(iface.address.hostAddress ?: "", httpPort)
                for (target in allTargets) {
                    if (iface.socket.isClosed) break
                    val message = buildString(400) {
                        append("NOTIFY * HTTP/1.1\r\n")
                        append("HOST: ").append(UpnpConstants.SSDP_ADDR).append(':').append(UpnpConstants.SSDP_PORT).append("\r\n")
                        append("CACHE-CONTROL: max-age=").append(UpnpConstants.MAX_AGE_SEC).append("\r\n")
                        append("LOCATION: ").append(location).append("\r\n")
                        append("NT: ").append(target).append("\r\n")
                        append("NTS: ssdp:alive\r\n")
                        append("SERVER: ").append(UpnpConstants.SERVER_HEADER).append("\r\n")
                        append("USN: ").append(usnFor(target)).append("\r\n")
                        append("BOOTID.UPNP.ORG: ").append(bootId).append("\r\n")
                        append("CONFIGID.UPNP.ORG: ").append(UpnpConstants.CONFIG_ID).append("\r\n")
                        append("\r\n")
                    }
                    if (sendRaw(iface, group, UpnpConstants.SSDP_PORT, message, "alive")) {
                        iface.aliveTx += 1
                    }
                    // Serialize per interface with a small gap so Wi-Fi power save
                    // clients do not drop a 12-packet burst.
                    delay(5L + random.nextInt(11))
                }
            }
            publishInterfaces()
        }
    }

    private fun sendRaw(iface: SsdpIface, destination: InetAddress, port: Int, message: String, kind: String): Boolean {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return try {
            iface.socket.send(DatagramPacket(bytes, bytes.size, destination, port))
            UpnpDebug.inc("ssdp.$kind.tx")
            true
        } catch (e: Throwable) {
            UpnpDebug.inc("ssdp.$kind.fail")
            UpnpDebug.log("Ssdp", "$kind send failed on ${iface.label}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun httpDate(): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------ self-test

    /**
     * SSDP loopback probe: sends a real M-SEARCH (ST=ssdp:all, MX=1) from a temporary
     * socket, waits up to ~2 s and counts response datagrams carrying our UDN.
     * Blocking; only called from the /self-test HTTP handler.
     */
    fun multicastSelfTest(): String {
        if (sockets.isEmpty()) return "SSDP: FAIL — no SSDP socket bound\n"
        val builder = StringBuilder(512)
        val temp = DatagramSocket()
        try {
            temp.soTimeout = 250
            val request = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: ${UpnpConstants.SSDP_ADDR}:${UpnpConstants.SSDP_PORT}\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: ssdp:all\r\n" +
                "\r\n"
            val bytes = request.toByteArray(Charsets.UTF_8)
            temp.send(DatagramPacket(bytes, bytes.size, group, UpnpConstants.SSDP_PORT))
            val deadline = System.currentTimeMillis() + 2_000L
            var responses = 0
            var ours = 0
            val buffer = ByteArray(1500)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    temp.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                responses++
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                if (text.startsWith("HTTP/1.1 200", ignoreCase = true) && text.contains(udn)) ours++
            }
            UpnpDebug.inc("ssdp.selftest.run")
            if (ours > 0) {
                UpnpDebug.inc("ssdp.selftest.pass")
                builder.append("SSDP multicast loopback: PASS — $ours response(s) carrying our UDN ")
                    .append("(${responses} datagram(s) total)\n")
            } else {
                UpnpDebug.inc("ssdp.selftest.fail")
                builder.append("SSDP multicast loopback: FAIL — no response carrying our UDN ")
                    .append("(${responses} datagram(s) total). Multicast join/socket/AP isolation problem on the TV side.\n")
            }
            UpnpDebug.log("Ssdp", "multicastSelfTest: ours=$ours total=$responses")
        } catch (e: Throwable) {
            UpnpDebug.inc("ssdp.selftest.fail")
            builder.append("SSDP multicast loopback: FAIL — ${e.javaClass.simpleName}: ${e.message}\n")
            UpnpDebug.log("Ssdp", "multicastSelfTest failed", e)
        } finally {
            try {
                temp.close()
            } catch (_: Throwable) {
            }
        }
        return builder.toString()
    }

    // ------------------------------------------------------------------ internal types

    private class ParsedSearch(val searchTarget: String, val mx: Int) {
        companion object {
            fun parse(text: String): ParsedSearch? {
                val normalized = text.replace("\r\n", "\n")
                val lines = normalized.split('\n')
                if (lines.isEmpty()) return null
                val requestLine = lines[0].trim()
                val parts = requestLine.split(Regex("\\s+"))
                if (parts.size < 3) return null
                if (!parts[0].equals("M-SEARCH", ignoreCase = true)) return null
                if (parts[1] != "*") return null
                if (!parts[2].startsWith("HTTP/1.", ignoreCase = true)) return null

                var man: String? = null
                var st: String? = null
                var host: String? = null
                var mx = 3
                for (index in 1 until lines.size) {
                    val line = lines[index]
                    if (line.isBlank()) break
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val name = line.substring(0, colon).trim().uppercase(Locale.US)
                    val value = line.substring(colon + 1).trim()
                    when (name) {
                        "MAN" -> if (man == null) man = value
                        "ST" -> if (st == null) st = value
                        "HOST" -> if (host == null) host = value
                        "MX" -> mx = value.toIntOrNull() ?: mx
                    }
                }
                val manValue = man?.replace("\"", "")?.trim()
                if (manValue == null || !manValue.contains("ssdp:discover", ignoreCase = true)) return null
                val searchTarget = st?.trim() ?: return null
                if (searchTarget.isEmpty()) return null
                // HOST is required by the spec; if present it must address the SSDP group.
                val hostValue = host
                if (hostValue != null && !hostValue.startsWith(UpnpConstants.SSDP_ADDR)) return null
                return ParsedSearch(searchTarget, mx.coerceIn(0, 5))
            }
        }
    }

    private companion object {
        const val ROOT_DEVICE = "upnp:rootdevice"
    }
}
