package com.trackpad.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val os: String,
    var lastSeen: Long = System.currentTimeMillis()
)

class DeviceDiscoveryManager(
    private val context: Context,
    private val onDevicesUpdated: (List<DiscoveredDevice>) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isListening = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val devicesMap = ConcurrentHashMap<String, DiscoveredDevice>()
    private val candidateIps = CopyOnWriteArraySet<String>()

    fun addCandidateIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isNotEmpty() && !trimmed.contains("255")) {
            candidateIps.add(trimmed)
        }
    }

    fun start() {
        if (isListening) return
        isListening = true

        scope.launch {
            try {
                // Acquire MulticastLock if Wi-Fi manager is available
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                try {
                    multicastLock = wifi?.createMulticastLock("RemoteTrackpadDiscovery")?.apply {
                        setReferenceCounted(true)
                        acquire()
                    }
                } catch (_: Exception) {}

                val newSocket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = 1500
                        bind(java.net.InetSocketAddress(8766))
                    }
                } catch (e: Exception) {
                    DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 1500
                    }
                }
                socket = newSocket

                // Periodic discovery probe broadcast & unicast
                launch {
                    while (isListening && !newSocket.isClosed) {
                        sendProbes(newSocket)
                        delay(1800)
                    }
                }

                // Cleanup stale devices loop
                launch {
                    while (isListening) {
                        val now = System.currentTimeMillis()
                        var changed = false
                        val iterator = devicesMap.entries.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            if (now - entry.value.lastSeen > 10000) {
                                iterator.remove()
                                changed = true
                            }
                        }
                        if (changed) {
                            notifyList()
                        }
                        delay(2000)
                    }
                }

                val buffer = ByteArray(1024)
                while (isListening && !newSocket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        newSocket.receive(packet)
                        val text = String(packet.data, 0, packet.length)

                        val json = JSONObject(text)
                        if (json.optString("service") == "remote_trackpad") {
                            val name = json.optString("name", "Unknown Computer")
                            val ip = json.optString("ip", packet.address?.hostAddress ?: "")
                            val port = json.optInt("port", 8765)
                            val os = json.optString("os", "Desktop")

                            if (ip.isNotEmpty()) {
                                val key = "$ip:$port"
                                val existing = devicesMap[key]
                                if (existing == null) {
                                    devicesMap[key] = DiscoveredDevice(name, ip, port, os, System.currentTimeMillis())
                                    notifyList()
                                } else {
                                    existing.lastSeen = System.currentTimeMillis()
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // socket timeout is expected, continue loop
                    } catch (e: Exception) {
                        if (!isListening) break
                    }
                }
            } catch (e: Exception) {
                Log.d("DeviceDiscovery", "Discovery stopped: ${e.message}")
            }
        }
    }

    fun triggerImmediateProbe(extraIps: List<String> = emptyList()) {
        extraIps.forEach { addCandidateIp(it) }
        scope.launch {
            val sock = socket ?: return@launch
            sendProbes(sock)
        }
    }

    private fun sendProbes(sock: DatagramSocket) {
        val targets = getBroadcastTargets()
        val probeData = "DISCOVER_REMOTE_TRACKPAD".toByteArray()
        for (target in targets) {
            try {
                val probePacket = DatagramPacket(probeData, probeData.size, target, 8766)
                sock.send(probePacket)
            } catch (_: Exception) {}
        }
    }

    private suspend fun notifyList() {
        val list = devicesMap.values.toList().sortedBy { it.name }
        withContext(Dispatchers.Main) {
            onDevicesUpdated(list)
        }
    }

    fun getDiscoveredDevices(): List<DiscoveredDevice> = devicesMap.values.toList()

    private fun getBroadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        try {
            targets.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        // 1. All network interface broadcast addresses (computed directly from real subnet masks)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                if (netInterface.isLoopback || !netInterface.isUp) continue
                for (interfaceAddress in netInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        targets.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Direct Unicast Probes to Candidate IPs (Bypasses Router AP Isolation)
        for (ip in candidateIps) {
            try {
                targets.add(InetAddress.getByName(ip))
            } catch (_: Exception) {}
        }

        return targets.toList()
    }

    fun stop() {
        isListening = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        scope.cancel()
    }
}
