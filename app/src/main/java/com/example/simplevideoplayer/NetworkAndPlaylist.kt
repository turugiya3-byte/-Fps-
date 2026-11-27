package com.example.simplevideoplayer

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket

data class PlaylistItem(
    val id: String,
    val title: String,
    val uriString: String,
    val sourceType: String
)

enum class NetworkProtocolType(val label: String) {
    DLNA("DLNA"),
    SMB("SMB")
}

data class DiscoveredNetworkDevice(
    val name: String,
    val host: String,
    val protocol: NetworkProtocolType,
    val locationOrShare: String
)

object NetworkDiscoveryManager {

    suspend fun discoverDevices(context: Context): List<DiscoveredNetworkDevice> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<DiscoveredNetworkDevice>()
            found.addAll(discoverDlnaDevices())
            found.addAll(discoverSmbHosts(context))
            found.distinctBy { "${it.protocol.name}:${it.host}:${it.locationOrShare}" }
        }

    private fun discoverDlnaDevices(): List<DiscoveredNetworkDevice> {
        val results = mutableListOf<DiscoveredNetworkDevice>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = 1800
            }
            val group = InetAddress.getByName("239.255.255.250")
            val searchMsg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)

            val packet = DatagramPacket(searchMsg, searchMsg.size, group, 1900)
            socket.send(packet)

            val deadline = System.currentTimeMillis() + 1800L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val text = String(resp.data, 0, resp.length, Charsets.UTF_8)
                    val location = extractHeader(text, "LOCATION") ?: "http://${resp.address.hostAddress}/"
                    val serverHeader = extractHeader(text, "SERVER") ?: "DLNA MediaServer"
                    val friendlyName = serverHeader.substringBefore(",").trim().ifBlank { "DLNAサーバー" }
                    results.add(
                        DiscoveredNetworkDevice(
                            name = friendlyName,
                            host = resp.address.hostAddress ?: "",
                            protocol = NetworkProtocolType.DLNA,
                            locationOrShare = location
                        )
                    )
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        return results
    }

    private fun discoverSmbHosts(context: Context): List<DiscoveredNetworkDevice> {
        val results = mutableListOf<DiscoveredNetworkDevice>()
        val subnetPrefix = resolveSubnetPrefix(context) ?: return results

        for (lastOctet in 1..254) {
            val ip = "$subnetPrefix.$lastOctet"
            if (isPortOpen(ip, 445, 90) || isPortOpen(ip, 139, 90)) {
                val hostName = try {
                    InetAddress.getByName(ip).hostName ?: ip
                } catch (_: Exception) {
                    ip
                }
                results.add(
                    DiscoveredNetworkDevice(
                        name = if (hostName != ip) "$hostName ($ip)" else "SMBホスト ($ip)",
                        host = ip,
                        protocol = NetworkProtocolType.SMB,
                        locationOrShare = "smb://$ip/video"
                    )
                )
            }
        }
        return results
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveSubnetPrefix(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return null
            val b1 = ipInt and 0xff
            val b2 = (ipInt shr 8) and 0xff
            val b3 = (ipInt shr 16) and 0xff
            if (b1 == 0) null else "$b1.$b2.$b3"
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHeader(raw: String, headerName: String): String? {
        return raw.lineSequence()
            .firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    fun buildStreamUri(device: DiscoveredNetworkDevice, customPath: String): Uri {
        val trimmed = customPath.trim()
        if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.startsWith("smb://", true) ||
            trimmed.startsWith("rtsp://", true)
        ) {
            return Uri.parse(trimmed)
        }
        return when (device.protocol) {
            NetworkProtocolType.DLNA -> {
                val base = device.locationOrShare.substringBeforeLast("/")
                val cleanPath = trimmed.trimStart('/')
                Uri.parse(if (cleanPath.isBlank()) device.locationOrShare else "$base/$cleanPath")
            }
            NetworkProtocolType.SMB -> {
                val cleanPath = trimmed.trimStart('/')
                val base = if (cleanPath.isBlank()) device.locationOrShare else "smb://${device.host}/$cleanPath"
                Uri.parse(base)
            }
        }
    }
}
