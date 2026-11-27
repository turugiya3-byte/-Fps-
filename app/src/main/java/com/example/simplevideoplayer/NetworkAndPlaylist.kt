package com.example.simplevideoplayer

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.URL
import java.util.EnumSet
import java.util.Locale

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

data class ExplorerEntry(
    val name: String,
    val isDirectory: Boolean,
    val objectIdOrPath: String,
    val streamUrl: String? = null
)

data class SmbCredentials(
    val isAnonymous: Boolean = true,
    val username: String = "匿名",
    val password: String = "",
    val domain: String = "",
    val shareName: String = "video"
) {
    fun shouldUseAnonymous(): Boolean {
        return isAnonymous ||
            username.isBlank() ||
            username == "匿名" ||
            username.equals("guest", ignoreCase = true) ||
            username.equals("anonymous", ignoreCase = true)
    }
}

@UnstableApi
class SmbMediaDataSourceFactory(
    private val defaultCredentials: SmbCredentials
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SmbMediaDataSource(defaultCredentials)
    }
}

@UnstableApi
class SmbMediaDataSource(
    private val defaultCredentials: SmbCredentials
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var smbFile: File? = null

    private var fileSize: Long = C.LENGTH_UNSET.toLong()
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri

        try {
            val uri = dataSpec.uri
            val host = uri.host ?: throw IOException("SMBホスト名が指定されていません: $uri")
            val segments = uri.pathSegments.filter { it.isNotBlank() }
            if (segments.isEmpty()) {
                throw IOException("SMB共有名・ファイルパスが指定されていません: $uri")
            }

            val shareName = segments.first().ifBlank { defaultCredentials.shareName }
            val filePath = segments.drop(1).joinToString("\\")
            if (filePath.isBlank()) {
                throw IOException("SMBファイルパスが空です: $uri")
            }

            val client = SMBClient()
            val conn = client.connect(host)
            val authCtx = if (defaultCredentials.shouldUseAnonymous()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    defaultCredentials.username,
                    defaultCredentials.password.toCharArray(),
                    defaultCredentials.domain
                )
            }
            val sess = conn.authenticate(authCtx)
            val share = sess.connectShare(shareName) as DiskShare
            val handle = share.openFile(
                filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            val totalSize = handle.fileInformation.standardInformation.endOfFile
            val startPos = dataSpec.position
            if (startPos > totalSize) {
                throw IOException("シーク位置がファイルサイズを超えています ($startPos > $totalSize)")
            }

            smbClient = client
            connection = conn
            session = sess
            diskShare = share
            smbFile = handle
            fileSize = totalSize
            readPosition = startPos

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length.coerceAtMost(totalSize - startPos)
            } else {
                totalSize - startPos
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            closeQuietly()
            throw IOException("SMBファイルのオープンに失敗しました: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val file = smbFile ?: throw IOException("SMBファイルが開かれていません")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        return try {
            val buf = ByteArray(toRead)
            val readBytes = file.read(buf, 0, readPosition, toRead)
            if (readBytes <= 0) {
                bytesRemaining = 0L
                C.RESULT_END_OF_INPUT
            } else {
                System.arraycopy(buf, 0, buffer, offset, readBytes)
                readPosition += readBytes.toLong()
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= readBytes.toLong()
                }
                bytesTransferred(readBytes)
                readBytes
            }
        } catch (e: Exception) {
            throw IOException("SMBファイルの読み込み中にエラーが発生しました: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpened = opened
        opened = false
        closeQuietly()
        if (wasOpened) {
            transferEnded()
        }
    }

    private fun closeQuietly() {
        try { smbFile?.close() } catch (_: Exception) {}
        smbFile = null
        try { diskShare?.close() } catch (_: Exception) {}
        diskShare = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        try { smbClient?.close() } catch (_: Exception) {}
        smbClient = null
        readPosition = 0L
        bytesRemaining = 0L
    }
}

object NetworkDiscoveryManager {

    private val videoExtensions = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".webm", ".flv", ".wmv"
    )

    fun isVideoFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return videoExtensions.any { lower.endsWith(it) }
    }

    fun buildDirectUri(rawPath: String): Uri = Uri.parse(rawPath.trim())

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
            if (isPortOpen(ip, 445, 80) || isPortOpen(ip, 139, 80)) {
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

    suspend fun browseDlna(
        device: DiscoveredNetworkDevice,
        objectId: String = "0"
    ): Result<List<ExplorerEntry>> = withContext(Dispatchers.IO) {
        try {
            val controlUrl = fetchDlnaControlUrl(device.locationOrShare)
                ?: return@withContext Result.failure(IllegalStateException("ContentDirectory 制御URLが見つかりません"))

            val soapBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                  <s:Body>
                    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                      <ObjectID>$objectId</ObjectID>
                      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                      <Filter>*</Filter>
                      <StartingIndex>0</StartingIndex>
                      <RequestedCount>200</RequestedCount>
                      <SortCriteria></SortCriteria>
                    </u:Browse>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()

            val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            }
            conn.outputStream.use { it.write(soapBody.toByteArray(Charsets.UTF_8)) }

            val responseXml = conn.inputStream.bufferedReader().use { it.readText() }
            val didlLiteXml = extractDidlLiteResult(responseXml)
            val entries = parseDidlLiteEntries(didlLiteXml)
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchDlnaControlUrl(locationUrl: String): String? {
        val conn = (URL(locationUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 4000
        }
        val xmlText = conn.inputStream.bufferedReader().use { it.readText() }
        val baseUri = URL(locationUrl)

        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(xmlText))
        }
        var eventType = parser.eventType
        var inService = false
        var serviceType = ""
        var controlUrl = ""
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    if (currentTag.equals("service", ignoreCase = true)) {
                        inService = true
                        serviceType = ""
                        controlUrl = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inService) {
                        val txt = parser.text?.trim().orEmpty()
                        when {
                            currentTag.equals("serviceType", ignoreCase = true) -> serviceType = txt
                            currentTag.equals("controlURL", ignoreCase = true) -> controlUrl = txt
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag.equals("service", ignoreCase = true) && inService) {
                        if (serviceType.contains("ContentDirectory", ignoreCase = true) && controlUrl.isNotBlank()) {
                            return URL(baseUri, controlUrl).toString()
                        }
                        inService = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return null
    }

    private fun extractDidlLiteResult(soapXml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(soapXml))
        }
        var eventType = parser.eventType
        var currentTag = ""
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name ?: ""
                XmlPullParser.TEXT -> {
                    if (currentTag.equals("Result", ignoreCase = true)) {
                        return parser.text.orEmpty()
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return ""
    }

    private fun parseDidlLiteEntries(didlXml: String): List<ExplorerEntry> {
        if (didlXml.isBlank()) return emptyList()
        val entries = mutableListOf<ExplorerEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(StringReader(didlXml))
        }
        var eventType = parser.eventType
        var inContainer = false
        var inItem = false
        var id = ""
        var title = ""
        var resUrl = ""
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when {
                        currentTag.equals("container", ignoreCase = true) -> {
                            inContainer = true
                            id = parser.getAttributeValue(null, "id").orEmpty()
                            title = ""
                        }
                        currentTag.equals("item", ignoreCase = true) -> {
                            inItem = true
                            id = parser.getAttributeValue(null, "id").orEmpty()
                            title = ""
                            resUrl = ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text?.trim().orEmpty()
                    if (inContainer && currentTag.endsWith("title", ignoreCase = true)) {
                        title = txt
                    } else if (inItem) {
                        if (currentTag.endsWith("title", ignoreCase = true)) {
                            title = txt
                        } else if (currentTag.endsWith("res", ignoreCase = true) && resUrl.isBlank()) {
                            resUrl = txt
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag.equals("container", ignoreCase = true) && inContainer) {
                        entries.add(
                            ExplorerEntry(
                                name = title.ifBlank { "フォルダ ($id)" },
                                isDirectory = true,
                                objectIdOrPath = id.ifBlank { "0" }
                            )
                        )
                        inContainer = false
                    } else if (tag.equals("item", ignoreCase = true) && inItem) {
                        if (resUrl.isNotBlank()) {
                            entries.add(
                                ExplorerEntry(
                                    name = title.ifBlank { resUrl.substringAfterLast('/') },
                                    isDirectory = false,
                                    objectIdOrPath = id,
                                    streamUrl = resUrl
                                )
                            )
                        }
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return entries
    }

    suspend fun browseSmb(
        host: String,
        credentials: SmbCredentials,
        subPath: String = ""
    ): Result<List<ExplorerEntry>> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            client.connect(host).use { connection ->
                val authCtx = if (credentials.shouldUseAnonymous()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        credentials.username,
                        credentials.password.toCharArray(),
                        credentials.domain
                    )
                }
                val session = connection.authenticate(authCtx)
                (session.connectShare(credentials.shareName) as DiskShare).use { share ->
                    val normalizedPath = subPath.trim('/')
                    val list = share.list(normalizedPath)
                        .filter { info ->
                            val name = info.fileName
                            name != "." && name != ".."
                        }
                        .mapNotNull { info ->
                            val name = info.fileName
                            val isDir = (info.fileAttributes and 0x10L) != 0L
                            val nextRelPath = if (normalizedPath.isBlank()) name else "$normalizedPath/$name"
                            if (isDir) {
                                ExplorerEntry(
                                    name = name,
                                    isDirectory = true,
                                    objectIdOrPath = nextRelPath
                                )
                            } else if (isVideoFileName(name)) {
                                ExplorerEntry(
                                    name = name,
                                    isDirectory = false,
                                    objectIdOrPath = nextRelPath,
                                    streamUrl = "smb://$host/${credentials.shareName}/$nextRelPath"
                                )
                            } else {
                                null
                            }
                        }
                        .sortedWith(
                            compareBy<ExplorerEntry> { !it.isDirectory }
                                .thenBy { it.name.lowercase(Locale.US) }
                        )
                    Result.success(list)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}
