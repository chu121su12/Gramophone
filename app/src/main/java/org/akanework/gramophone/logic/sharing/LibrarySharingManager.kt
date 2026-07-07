package org.akanework.gramophone.logic.sharing

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.akanework.gramophone.logic.GramophoneApplication
import org.akanework.gramophone.logic.getFile
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import org.nift4.mediastorecompat.MediaStoreCompat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val HEADER_SESSION_ID = "session_id"
private const val NEXT_REMOTE_PREFETCH_DELAY_MS = 15_000L
private const val TRANSPORT_KEEP_ALIVE_PING_MS = 15_000L
private val LYRIC_CACHE_EXTENSIONS = listOf("ttml", "srt", "lrc")
private val HEX_CHARS = "0123456789abcdef".toCharArray()

object LibrarySharingManager {
    const val DEFAULT_PORT = 8080
    const val TRANSPORT_ROUTE = "/gramaphone/transport"
    const val TRANSFER_ROUTE = "/gramaphone/transfer"
    private const val PREF_CLIENT_SESSIONS = "library_sharing_client_sessions"
    private const val PREF_SERVER_SESSIONS = "library_sharing_server_sessions"
    private const val PREF_CLIENT_CONNECTED = "library_sharing_client_connected"
    private const val PREF_CLIENT_LAST_HOST = "library_sharing_last_host"
    private const val PREF_AUTO_RECONNECT = "library_sharing_auto_reconnect"
    private const val PREF_SERVER_PORT = "library_sharing_port"
    private const val PREF_KEEP_ALIVE = "library_sharing_keep_alive"

    private lateinit var app: GramophoneApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextServerClientId = AtomicInteger(1)
    private val serverClients = ConcurrentHashMap<Int, ServerClientConnection>()
    private val transferRequests = ConcurrentHashMap<WebSocket, String>()
    private var server: SharingServer? = null
    private var pendingServerClient: ServerClientConnection? = null
    private var serverLibraryId = UUID.randomUUID().toString()
    private var transportClient: SharingTransportClient? = null
    private var transferClient: ClientTransferSession? = null
    private var transferUri: URI? = null
    private var transferSessionId: String? = null
    private var pendingConnect: CompletableDeferred<StaticRemoteLibraryReader>? = null
    private var pendingRefresh: CompletableDeferred<StaticRemoteLibraryReader>? = null
    private var pendingConnectActivatesReader = true
    private var connectedRemoteReaderValue: StaticRemoteLibraryReader? = null
    private var pendingAutoReconnectReader: StaticRemoteLibraryReader? = null
    private var pendingAutoReconnectHost: String? = null
    private var localPlaybackSnapshot: LocalPlaybackSnapshot? = null
    private var delayedNextPrefetch: Job? = null
    private var queuedNextPrefetch: Job? = null
    private var queuedNextPrefetchStartedTransfer = false
    private var queuedNextPrefetchGeneration = 0
    private var currentRemotePlaybackId: String? = null
    @Volatile
    private var serverSongsById: Map<String, MediaItem> = emptyMap()

    val sharingEnabled = MutableStateFlow(false)
    val acceptWindowVisible = MutableStateFlow(false)
    val pendingClient = MutableStateFlow<PendingClient?>(null)
    val connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    val remoteReader = MutableStateFlow<StaticRemoteLibraryReader?>(null)
    val autoReconnectCandidate = MutableStateFlow<ReconnectCandidate?>(null)
    val remoteDisconnectEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clientState = MutableStateFlow<ClientState>(ClientState.Idle)

    fun init(application: GramophoneApplication) {
        app = application
    }

    fun serverPort(): Int = prefs().getString(PREF_SERVER_PORT, DEFAULT_PORT.toString())
        ?.toIntOrNull()
        ?.takeIf { it in 1..65535 }
        ?: DEFAULT_PORT

    fun setServerPort(port: Int) {
        require(port in 1..65535) { "Port must be 1..65535" }
        prefs().edit()
            .putString(PREF_SERVER_PORT, port.toString())
            .apply()
        if (sharingEnabled.value) {
            stopServer("Library sharing port changed")
            startServerIfNeeded()
        }
    }

    fun localLanAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { it.isLanAddress() && !it.isLoopbackAddress && it is Inet4Address }
                .mapNotNull { it.hostAddress }
                .toList()
        }.getOrDefault(emptyList())

    fun setSharingEnabled(enabled: Boolean) {
        sharingEnabled.value = enabled
        if (enabled) {
            startServerIfNeeded()
            updateKeepAliveService()
        } else {
            stopServer("Library sharing disabled")
            updateKeepAliveService()
        }
    }

    fun refreshKeepAliveService() = updateKeepAliveService()

    fun isKeepAliveEnabledInPreferences(): Boolean =
        prefs().getBoolean(PREF_KEEP_ALIVE, false)

    fun isAutoReconnectEnabledInPreferences(): Boolean =
        prefs().getBoolean(PREF_AUTO_RECONNECT, true)

    fun setAutoReconnectEnabled(enabled: Boolean) {
        prefs().edit()
            .putBoolean(PREF_AUTO_RECONNECT, enabled)
            .apply()
    }

    fun connectedRemoteReader(): StaticRemoteLibraryReader? =
        connectedRemoteReaderValue ?: remoteReader.value

    fun activateConnectedRemoteLibrary(): Boolean {
        val reader = connectedRemoteReader() ?: return false
        remoteReader.value = reader
        connectedRemoteReaderValue = reader
        return true
    }

    fun savedServerSessions(): List<SavedServerSession> {
        val json = jsonPreference(PREF_SERVER_SESSIONS)
        return json.keys().asSequence().map { sessionId ->
            val obj = json.optJSONObject(sessionId)
            SavedServerSession(
                sessionId,
                obj?.optNullableString("deviceName"),
                obj?.optNullableString("address")
            )
        }.toList()
    }

    fun savedClientServers(): List<SavedClientServer> {
        val json = jsonPreference(PREF_CLIENT_SESSIONS)
        return json.keys().asSequence().map { serverKey ->
            SavedClientServer(
                serverKey,
                json.optString(serverKey)
            )
        }.toList()
    }

    fun revokeServerSessionById(sessionId: String) {
        revokeServerSession(sessionId)
        closeServerClientsForSession(sessionId, "Session revoked")
    }

    fun forgetClientServer(serverKey: String) {
        removeClientSession(serverKey)
        if (prefs().getString(PREF_CLIENT_LAST_HOST, null) == serverKey) {
            prefs().edit()
                .remove(PREF_CLIENT_LAST_HOST)
                .putBoolean(PREF_CLIENT_CONNECTED, false)
                .apply()
        }
    }

    fun ensureServerRunningFromService() {
        if (!shouldKeepAliveServer()) return
        sharingEnabled.value = true
        startServerIfNeeded()
    }

    private fun startServerIfNeeded() {
        if (server != null) return
        serverLibraryId = UUID.randomUUID().toString()
        server = SharingServer().also {
            it.connectionLostTimeout = 30
            it.start()
        }
    }

    private fun stopServer(reason: String) {
        closePendingClient(reason)
        serverClients.values.forEach { it.close(reason) }
        serverClients.clear()
        transferRequests.clear()
        serverSongsById = emptyMap()
        updateConnectedClients()
        server?.stopCatching()
        server = null
    }

    private fun updateKeepAliveService() {
        if (shouldKeepAliveServer()) {
            runCatching { LibrarySharingService.start(app) }
        } else {
            runCatching { LibrarySharingService.stop(app) }
        }
    }

    fun shouldKeepAliveServer(): Boolean =
        sharingEnabled.value && isKeepAliveEnabledInPreferences()

    fun setAcceptWindowVisible(visible: Boolean) {
        acceptWindowVisible.value = visible
        if (!visible && pendingServerClient != null) {
            closePendingClient("Library sharing view closed")
        }
    }

    fun acceptPendingClient() {
        val pending = pendingServerClient ?: return
        pending.accepted = true
        val sessionId = UUID.randomUUID().toString()
        pending.sessionId = sessionId
        pendingServerClient = null
        pendingClient.value = null
        serverClients[pending.id] = pending
        saveServerSession(sessionId, pending.deviceName, pending.address)
        updateConnectedClients()
        pending.transport.send(
            JSONObject()
                .put("type", "accepted")
                .put("clientId", pending.id)
                .put("sessionId", sessionId)
                .toString()
        )
        sendLibrary(pending)
    }

    fun rejectPendingClient() {
        pendingServerClient?.transport?.takeIf { it.isOpen }?.send(
            JSONObject()
                .put("type", "rejected")
                .put("reason", "Connection rejected")
                .toString()
        )
        closePendingClient("Connection rejected")
    }

    private fun sendLibrary(client: ServerClientConnection) {
        scope.launch {
            val songItems = app.reader.songListFlow.first()
            serverSongsById = songItems.associateBy { it.mediaId }
            val library = RemoteLibrary.fromSnapshot(
                serverLibraryId,
                Build.MANUFACTURER + " " + Build.MODEL,
                songItems,
                app.reader.playlistListFlow.first(),
                prefs().getBoolean("library_sharing_allow_folder_tabs", false)
            )
            client.transport.takeIf { it.isOpen }?.send(
                JSONObject()
                    .put("type", "library")
                    .put("library", library.toJson())
                    .toString()
            )
        }
    }

    fun disconnectServerClient(id: Int) {
        val client = serverClients.remove(id) ?: return
        client.sessionId?.let { sessionId ->
            revokeServerSession(sessionId)
            serverClients.entries
                .filter { it.value.sessionId == sessionId }
                .forEach {
                    it.value.transfer?.let { transfer -> transferRequests.remove(transfer) }
                    it.value.close("Session revoked")
                    serverClients.remove(it.key)
                }
        }
        client.transfer?.let { transferRequests.remove(it) }
        client.close("Session revoked")
        updateConnectedClients()
    }

    suspend fun connectTo(
        host: String,
        activateReader: Boolean = true
    ): StaticRemoteLibraryReader = withContext(Dispatchers.IO) {
        disconnectRemote(clearReader = activateReader, rememberClientConnected = false)
        val target = parseConnectTarget(host)
        pendingConnectActivatesReader = activateReader
        pendingAutoReconnectHost = if (activateReader) null else target.display
        clientState.value = ClientState.Connecting(target.display)
        if (!target.address.isLanAddress()) {
            clientState.value = ClientState.Failed("Address is not LAN")
            throw IllegalArgumentException("Address is not LAN")
        }
        val uri = URI("ws://${target.uriHost}:${target.port}$TRANSPORT_ROUTE")
        val completion = CompletableDeferred<StaticRemoteLibraryReader>()
        pendingConnect = completion
        val client = SharingTransportClient(
            uri,
            target.hostAddress,
            target.port,
            target.sessionKey,
            clientSessionFor(target.sessionKey),
            completion
        )
        transportClient = client
        client.connectionLostTimeout = 30
        client.connect()
        try {
            val reader = withTimeout(60_000) {
                completion.await()
            }
            if (activateReader) {
                saveConnectedClientTarget(target.display)
            }
            reader
        } catch (e: TimeoutCancellationException) {
            disconnectRemote(clearReader = true, rememberClientConnected = false)
            clientState.value = ClientState.Failed("Connection timed out")
            throw e
        } finally {
            if (activateReader) {
                pendingAutoReconnectHost = null
            }
        }
    }

    fun cancelConnecting() {
        pendingConnect?.cancel("Canceled")
        disconnectRemote(clearReader = true, rememberClientConnected = false)
        clientState.value = ClientState.Idle
    }

    fun disconnectRemote(
        clearReader: Boolean = true,
        notifyDisconnect: Boolean = true,
        rememberClientConnected: Boolean = false
    ) {
        val hadRemoteReader = remoteReader.value != null
        delayedNextPrefetch?.cancel()
        delayedNextPrefetch = null
        queuedNextPrefetchGeneration++
        queuedNextPrefetch?.cancel()
        queuedNextPrefetch = null
        queuedNextPrefetchStartedTransfer = false
        currentRemotePlaybackId = null
        pendingAutoReconnectReader = null
        pendingAutoReconnectHost = null
        autoReconnectCandidate.value = null
        pendingRefresh?.cancel("Remote disconnected")
        pendingRefresh = null
        connectedRemoteReaderValue = null
        transferClient?.close()
        transferClient = null
        transferUri = null
        transferSessionId = null
        transportClient?.close()
        transportClient = null
        pendingConnect = null
        pendingConnectActivatesReader = true
        if (clearReader) {
            remoteReader.value = null
        }
        clientState.value = ClientState.Idle
        if (!rememberClientConnected) {
            prefs().edit().putBoolean(PREF_CLIENT_CONNECTED, false).apply()
        }
        if (notifyDisconnect && clearReader && hadRemoteReader) {
            remoteDisconnectEvents.tryEmit(Unit)
        }
    }

    fun startAutoReconnectIfNeeded() {
        if (!isAutoReconnectEnabledInPreferences()) return
        if (!prefs().getBoolean(PREF_CLIENT_CONNECTED, false)) return
        val host = prefs().getString(PREF_CLIENT_LAST_HOST, null)?.takeIf { it.isNotBlank() }
            ?: return
        if (connectedRemoteReader() != null ||
            pendingConnect != null ||
            pendingAutoReconnectReader != null) return
        scope.launch {
            runCatching { connectTo(host, activateReader = false) }
        }
    }

    fun acceptAutoReconnect(): StaticRemoteLibraryReader? {
        val reader = pendingAutoReconnectReader ?: return null
        connectedRemoteReaderValue = reader
        remoteReader.value = reader
        autoReconnectCandidate.value = null
        pendingAutoReconnectReader = null
        val host = pendingAutoReconnectHost
        pendingAutoReconnectHost = null
        pendingConnectActivatesReader = true
        if (!host.isNullOrBlank()) {
            saveConnectedClientTarget(host)
        }
        return reader
    }

    fun rejectAutoReconnect() {
        pendingConnectActivatesReader = true
        disconnectRemote(clearReader = false, rememberClientConnected = false)
    }

    suspend fun refreshRemoteLibrary(): StaticRemoteLibraryReader = withContext(Dispatchers.IO) {
        val client = transportClient ?: throw IllegalStateException("Remote library is not connected")
        val completion = CompletableDeferred<StaticRemoteLibraryReader>()
        pendingRefresh?.cancel("Refresh replaced")
        pendingRefresh = completion
        try {
            client.requestLibraryRefresh()
            withTimeout(60_000) { completion.await() }
        } finally {
            if (pendingRefresh == completion) {
                pendingRefresh = null
            }
        }
    }

    suspend fun clearDownloadedCache(): ClearedCache = withContext(Dispatchers.IO) {
        cancelQueuedNextPrefetch(restartTransfer = false)
        delayedNextPrefetch?.cancel()
        delayedNextPrefetch = null
        closeTransferSession()
        val root = librarySharingCacheRoot()
        if (!root.exists()) return@withContext ClearedCache(0, 0L)
        var filesDeleted = 0
        var bytesDeleted = 0L
        root.walkBottomUp().forEach { file ->
            if (file == root) return@forEach
            val isFile = file.isFile
            val length = if (isFile) file.length() else 0L
            if (runCatching { file.delete() }.getOrDefault(false) && isFile) {
                filesDeleted++
                bytesDeleted += length
            }
        }
        root.mkdirs()
        ClearedCache(filesDeleted, bytesDeleted)
    }

    fun enterRemotePlayback(player: Player) {
        if (remoteReader.value != null && localPlaybackSnapshot != null) return
        if (localPlaybackSnapshot == null) {
            localPlaybackSnapshot = LocalPlaybackSnapshot(
                items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                repeatMode = player.repeatMode,
                shuffleModeEnabled = player.shuffleModeEnabled
            )
        }
        player.pause()
        player.playWhenReady = false
        if (player.mediaItemCount > 0) {
            player.clearMediaItems()
        }
    }

    fun switchToLocalPlayback(player: Player?) {
        val wasRemote = remoteReader.value != null
        remoteReader.value = null
        if (wasRemote) {
            restoreLocalPlayback(player)
        }
    }

    fun restoreLocalPlayback(player: Player?) {
        delayedNextPrefetch?.cancel()
        delayedNextPrefetch = null
        cancelQueuedNextPrefetch()
        dropTransferForPlaybackChange(null)
        val snapshot = localPlaybackSnapshot
        localPlaybackSnapshot = null
        if (player == null) return
        player.pause()
        player.playWhenReady = false
        if (snapshot == null || snapshot.items.isEmpty()) {
            if (player.mediaItemCount > 0) {
                player.clearMediaItems()
            }
            return
        }
        player.repeatMode = snapshot.repeatMode
        player.shuffleModeEnabled = snapshot.shuffleModeEnabled
        player.setMediaItems(
            snapshot.items,
            snapshot.index.coerceIn(snapshot.items.indices),
            snapshot.positionMs
        )
        player.prepare()
        player.pause()
        player.playWhenReady = false
    }

    fun clearDisconnectedRemotePlayback(player: Player?) {
        if (player == null || remoteReader.value != null) return
        if ((0 until player.mediaItemCount).none { player.getMediaItemAt(it).isRestoredRemoteItem() }) {
            return
        }
        delayedNextPrefetch?.cancel()
        delayedNextPrefetch = null
        cancelQueuedNextPrefetch()
        dropTransferForPlaybackChange(null)
        player.pause()
        player.playWhenReady = false
        player.clearMediaItems()
    }

    suspend fun resolveRemoteQueueForPlayback(
        items: List<MediaItem>,
        startIndex: Int
    ): List<MediaItem> {
        if (items.none { it.isRemoteMediaItem() }) {
            dropTransferForPlaybackChange(null)
            return items
        }
        val mapped = items.map { item ->
            if (item.isRemoteMediaItem()) buildCachedPlaybackShell(item) else item
        }.toMutableList()
        val currentIndex = startIndex.takeIf { it in mapped.indices } ?: 0
        val item = items[currentIndex]
        if (item.isRemoteMediaItem()) {
            dropTransferForPlaybackChange(item.remoteOriginalMediaId())
            mapped[currentIndex] = ensureRemotePlaybackItem(item)
        } else {
            dropTransferForPlaybackChange(null)
        }
        return mapped
    }

    fun prepareRemotePlaybackShells(items: List<MediaItem>): List<MediaItem> =
        items.map { item ->
            if (item.isRemoteMediaItem()) buildCachedPlaybackShell(item) else item
        }

    fun isRestoredRemotePlayback(items: List<MediaItem>): Boolean =
        remoteReader.value == null && items.any { it.isRestoredRemoteItem() }

    private fun MediaItem.isRestoredRemoteItem(): Boolean =
        isRemoteMediaItem() || mediaId.startsWith(REMOTE_MEDIA_ID_PREFIX)

    fun prefetchQueuedNext(items: List<MediaItem>) {
        val item = items.firstOrNull { it.isRemoteMediaItem() }
        cancelQueuedNextPrefetch()
        if (item == null || expectedCachedFiles(item).audio?.exists() == true) return
        val generation = ++queuedNextPrefetchGeneration
        queuedNextPrefetch = scope.launch {
            try {
                delay(NEXT_REMOTE_PREFETCH_DELAY_MS)
                if (generation != queuedNextPrefetchGeneration) return@launch
                if (expectedCachedFiles(item).audio?.exists() == true) return@launch
                queuedNextPrefetchStartedTransfer = true
                runCatching { ensureRemotePlaybackItem(item) }
            } finally {
                if (generation == queuedNextPrefetchGeneration) {
                    queuedNextPrefetch = null
                    queuedNextPrefetchStartedTransfer = false
                }
            }
        }
    }

    fun prefetchAround(player: Player) {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) {
            dropTransferForPlaybackChange(null)
            return
        }
        val currentItem = player.getMediaItemAt(currentIndex)
        if (!currentItem.isRemoteMediaItem()) {
            dropTransferForPlaybackChange(null)
            return
        }
        val currentOriginalId = currentItem.remoteOriginalMediaId()
        dropTransferForPlaybackChange(currentOriginalId)
        scope.launch {
            val resolved = runCatching { ensureRemotePlaybackItem(currentItem) }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) {
                if (currentIndex in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(currentIndex).mediaId == currentItem.mediaId
                ) {
                    val shouldPlay = player.playWhenReady
                    player.replaceMediaItem(currentIndex, resolved)
                    if (shouldPlay) {
                        if (player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                        player.play()
                    }
                }
            }
        }
        delayedNextPrefetch?.cancel()
        val nextIndex = currentIndex + 1
        val nextItem = if (nextIndex in 0 until player.mediaItemCount) {
            player.getMediaItemAt(nextIndex).takeIf { it.isRemoteMediaItem() }
        } else {
            null
        } ?: return
        delayedNextPrefetch = scope.launch {
            delay(NEXT_REMOTE_PREFETCH_DELAY_MS)
            val shouldPrefetch = withContext(Dispatchers.Main) {
                currentIndex in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(currentIndex).remoteOriginalMediaId() == currentOriginalId &&
                    player.isPlaying
            }
            if (!shouldPrefetch) return@launch
            val resolved = runCatching { ensureRemotePlaybackItem(nextItem) }.getOrNull()
                ?: return@launch
            withContext(Dispatchers.Main) {
                if (nextIndex in 0 until player.mediaItemCount &&
                    player.getMediaItemAt(nextIndex).mediaId == nextItem.mediaId &&
                    player.currentMediaItem?.remoteOriginalMediaId() == currentOriginalId
                ) {
                    player.replaceMediaItem(nextIndex, resolved)
                }
            }
        }
    }

    private fun onTransportAccepted(
        sessionId: String,
        host: String,
        port: Int,
        sessionKey: String
    ) {
        saveClientSession(sessionKey, sessionId)
        transferUri = URI("ws://${uriHost(host)}:$port$TRANSFER_ROUTE")
        transferSessionId = sessionId
        resetTransferSession()
        clientState.value = ClientState.TransferringLibrary("$host:$port")
    }

    private fun onRemoteLibrary(library: RemoteLibrary) {
        val reader = library.toReader()
        pendingRefresh?.let { refresh ->
            connectedRemoteReaderValue = reader
            if (remoteReader.value != null) {
                remoteReader.value = reader
            }
            refresh.complete(reader)
            pendingRefresh = null
            clientState.value = ClientState.Connected(library.deviceName)
            return
        }
        if (pendingConnectActivatesReader) {
            connectedRemoteReaderValue = reader
            remoteReader.value = reader
        } else {
            pendingAutoReconnectReader = reader
            autoReconnectCandidate.value = ReconnectCandidate(
                pendingAutoReconnectHost ?: library.deviceName.orEmpty(),
                library.deviceName
            )
        }
        clientState.value = ClientState.Connected(library.deviceName)
        pendingConnect?.complete(reader)
        pendingConnect = null
    }

    private fun onClientFailure(message: String, throwable: Throwable? = null) {
        pendingConnect?.completeExceptionally(throwable ?: IllegalStateException(message))
        pendingConnect = null
        pendingRefresh?.completeExceptionally(throwable ?: IllegalStateException(message))
        pendingRefresh = null
        clientState.value = ClientState.Failed(message)
    }

    private suspend fun ensureRemotePlaybackItem(item: MediaItem): MediaItem {
        val originalId = item.remoteOriginalMediaId()
            ?: throw IllegalArgumentException("Missing remote original id")
        val expected = expectedCachedFiles(item)
        if (expected.audio?.exists() == true) {
            return buildCachedPlaybackShell(item, expected)
        }
        val cached = requestRemoteFiles(item, originalId, expected)
        return buildCachedPlaybackShell(item, cached)
    }

    private suspend fun requestRemoteFiles(
        item: MediaItem,
        originalId: String,
        expected: CachedRemoteFiles
    ): CachedRemoteFiles {
        val session = transferClient ?: resetTransferSession()
            ?: throw IllegalStateException("Transfer socket is not connected")
        return session.requestFiles(item, originalId, expected)
    }

    private fun dropTransferForPlaybackChange(originalId: String?) {
        if (currentRemotePlaybackId == originalId) return
        currentRemotePlaybackId = originalId
        delayedNextPrefetch?.cancel()
        delayedNextPrefetch = null
        resetTransferSession()
    }

    private fun cancelQueuedNextPrefetch(restartTransfer: Boolean = true) {
        val shouldAbortTransfer = queuedNextPrefetchStartedTransfer
        queuedNextPrefetchGeneration++
        queuedNextPrefetch?.cancel()
        queuedNextPrefetch = null
        queuedNextPrefetchStartedTransfer = false
        if (shouldAbortTransfer) {
            if (restartTransfer) {
                resetTransferSession()
            } else {
                closeTransferSession()
            }
        }
    }

    private fun resetTransferSession(): ClientTransferSession? {
        closeTransferSession()
        val uri = transferUri ?: return null
        val sessionId = transferSessionId ?: return null
        return ClientTransferSession(uri, sessionId)
            .also {
                transferClient = it
                it.connect()
            }
    }

    private fun closeTransferSession() {
        transferClient?.close()
        transferClient = null
    }

    private fun buildCachedPlaybackShell(
        item: MediaItem,
        cached: CachedRemoteFiles = expectedCachedFiles(item)
    ): MediaItem {
        val audioFile = cached.audio
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            audioFile?.let { putString(uk.akane.libphonograph.items.EXTRA_FILE, it.path) }
        }
        val metadata = item.mediaMetadata.buildUpon()
            .setArtworkUri(cached.art?.takeIf { it.exists() }?.toUri())
            .setExtras(extras)
            .build()
        return item.buildUpon()
            .setUri(audioFile?.toUri() ?: Uri.EMPTY)
            .setMimeType(item.remoteMimeType())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun expectedCachedFiles(item: MediaItem): CachedRemoteFiles {
        val originalId = item.remoteOriginalMediaId() ?: item.mediaId
        val key = cacheKey(item.remoteLibraryId().orEmpty(), originalId)
        val extension = item.remoteFileExtension()
            ?: item.getFile()?.extension?.takeIf { it.isNotBlank() }
            ?: "bin"
        val dir = cacheDir(item.remoteLibraryId().orEmpty())
        val audio = File(dir, "$key.$extension")
        val art = File(dir, "$key.jpg")
        val lyric = LYRIC_CACHE_EXTENSIONS
            .asSequence()
            .map { File(dir, "$key.$it") }
            .firstOrNull { it.exists() }
        return CachedRemoteFiles(audio, art.takeIf { it.exists() }, lyric)
    }

    private fun librarySharingCacheRoot(): File =
        File(app.filesDir, "shared-library-cache")

    private fun cacheDir(libraryId: String): File =
        File(librarySharingCacheRoot(), libraryId).also { it.mkdirs() }

    private fun cacheKey(libraryId: String, originalId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$libraryId:$originalId".toByteArray())
        val chars = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_CHARS[value ushr 4]
            chars[index * 2 + 1] = HEX_CHARS[value and 0x0f]
        }
        return String(chars)
    }

    private fun prefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(app)

    private fun clientSessionFor(sessionKey: String): String? =
        jsonPreference(PREF_CLIENT_SESSIONS).optString(sessionKey, "")
            .takeIf { it.isNotBlank() }

    private fun saveClientSession(sessionKey: String, sessionId: String) {
        val json = jsonPreference(PREF_CLIENT_SESSIONS)
            .put(sessionKey, sessionId)
        saveJsonPreference(PREF_CLIENT_SESSIONS, json)
    }

    private fun saveConnectedClientTarget(target: String) {
        prefs().edit()
            .putBoolean(PREF_CLIENT_CONNECTED, true)
            .putString(PREF_CLIENT_LAST_HOST, target)
            .apply()
    }

    private fun removeClientSession(sessionKey: String) {
        val json = jsonPreference(PREF_CLIENT_SESSIONS)
        json.remove(sessionKey)
        saveJsonPreference(PREF_CLIENT_SESSIONS, json)
    }

    private fun serverSessionFor(sessionId: String): AuthorizedSession? {
        val json = jsonPreference(PREF_SERVER_SESSIONS).optJSONObject(sessionId) ?: return null
        return AuthorizedSession(
            if (json.isNull("deviceName")) null else json.optString("deviceName")
        )
    }

    private fun saveServerSession(sessionId: String, deviceName: String?, address: String) {
        val json = jsonPreference(PREF_SERVER_SESSIONS)
            .put(
                sessionId,
                JSONObject()
                    .put("deviceName", deviceName)
                    .put("address", address)
            )
        saveJsonPreference(PREF_SERVER_SESSIONS, json)
    }

    private fun revokeServerSession(sessionId: String) {
        val json = jsonPreference(PREF_SERVER_SESSIONS)
        json.remove(sessionId)
        saveJsonPreference(PREF_SERVER_SESSIONS, json)
    }

    private fun jsonPreference(key: String): JSONObject =
        runCatching { JSONObject(prefs().getString(key, "{}").orEmpty()) }
            .getOrDefault(JSONObject())

    private fun saveJsonPreference(key: String, json: JSONObject) {
        prefs().edit().putString(key, json.toString()).apply()
    }

    private fun parseConnectTarget(input: String): ConnectTarget {
        val trimmed = input.trim()
        val portPart = trimmed.substringAfterLast(':', "")
        val hasSimplePort = trimmed.count { it == ':' } == 1 && portPart.toIntOrNull() != null
        val host = if (hasSimplePort) trimmed.substringBeforeLast(':') else trimmed
        val port = if (hasSimplePort) {
            portPart.toInt()
        } else {
            serverPort()
        }
        require(port in 1..65535) { "Port must be 1..65535" }
        val address = InetAddress.getByName(host)
        val hostAddress = address.hostAddress ?: host
        return ConnectTarget(
            address,
            hostAddress,
            uriHost(hostAddress),
            port,
            "$hostAddress:$port",
            "$hostAddress:$port"
        )
    }

    private fun onServerTransportOpen(conn: WebSocket, handshake: ClientHandshake) {
        val path = handshake.resourceDescriptor?.substringBefore('?') ?: ""
        if (path != TRANSPORT_ROUTE) {
            conn.close(1008, "Bad route")
            return
        }
        if (!sharingEnabled.value) {
            conn.close(1008, "Library sharing is disabled")
            return
        }
        val address = conn.remoteSocketAddress.address
        val sessionId = handshake.sessionId()
        if (sessionId.isNotBlank()) {
            val session = serverSessionFor(sessionId)
            if (session == null) {
                conn.send(JSONObject().put("type", "unauthorized").toString())
                conn.close(1008, "Unknown session")
                return
            }
            closeServerClientsForSession(sessionId, "Session reconnected")
            val clientAddress = socketAddress(conn)
            saveServerSession(sessionId, session.deviceName, clientAddress)
            val client = ServerClientConnection(
                nextServerClientId.getAndIncrement(),
                conn,
                clientAddress,
                sessionId
            ).also {
                it.accepted = true
                it.deviceName = session.deviceName
                it.startTransportKeepAlive()
            }
            serverClients[client.id] = client
            updateConnectedClients()
            conn.send(
                JSONObject()
                    .put("type", "accepted")
                    .put("clientId", client.id)
                    .put("sessionId", sessionId)
                    .toString()
            )
            sendLibrary(client)
            return
        }
        if (!address.isLanAddress()) {
            conn.close(1008, "Unknown client")
            return
        }
        if (!acceptWindowVisible.value) {
            conn.close(1008, "Authorization requires library sharing settings")
            return
        }
        if (pendingServerClient != null) {
            conn.close(1013, "Another client is pending")
            return
        }
        val client = ServerClientConnection(
            nextServerClientId.getAndIncrement(),
            conn,
            socketAddress(conn),
            null
        )
        client.startTransportKeepAlive()
        pendingServerClient = client
        pendingClient.value = client.toPendingClient()
        conn.send(JSONObject().put("type", "accept_required").toString())
    }

    private fun onServerTransferOpen(conn: WebSocket, handshake: ClientHandshake) {
        val descriptor = handshake.resourceDescriptor ?: ""
        val path = descriptor.substringBefore('?')
        if (path != TRANSFER_ROUTE) {
            conn.close(1008, "Bad route")
            return
        }
        val sessionId = handshake.sessionId()
        if (sessionId.isBlank() || serverSessionFor(sessionId) == null) {
            conn.close(1008, "Unknown client")
            return
        }
        serverClients.values
            .firstOrNull { it.sessionId == sessionId }
            ?.let { client ->
                client.transfer?.close(1000, "Transfer socket replaced")
                client.transfer = conn
                client.address = socketAddress(conn)
                client.lastSeenMs = System.currentTimeMillis()
                client.sessionId?.let { saveServerSession(it, client.deviceName, client.address) }
                updateConnectedClients()
            }
        transferRequests[conn] = sessionId
    }

    private fun closeServerClientsForSession(sessionId: String, reason: String) {
        var changed = false
        serverClients.entries
            .filter { it.value.sessionId == sessionId }
            .forEach { (id, client) ->
                if (serverClients.remove(id, client)) {
                    client.transfer?.let { transferRequests.remove(it) }
                    client.close(reason)
                    changed = true
                }
            }
        if (changed) {
            updateConnectedClients()
        }
    }

    private fun onServerMessage(conn: WebSocket, message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("type")) {
            "ping" -> {
                markTransportClientSeen(conn)
                sendTransportPong(conn)
            }
            "pong" -> markTransportClientSeen(conn)
            "hello" -> {
                val deviceName = json.optNullableString("deviceName")
                val pending = pendingServerClient?.takeIf { it.transport == conn }
                if (pending != null) {
                    pending.deviceName = deviceName
                    pendingClient.value = pending.toPendingClient()
                    return
                }
                serverClients.values.firstOrNull { it.transport == conn }?.let { client ->
                    client.address = socketAddress(conn)
                    client.deviceName = deviceName
                    client.lastSeenMs = System.currentTimeMillis()
                    client.sessionId?.let { saveServerSession(it, deviceName, client.address) }
                    updateConnectedClients()
                }
            }
            "disconnect" -> {
                serverClients.entries.firstOrNull { it.value.transport == conn }?.let {
                    disconnectServerClient(it.key)
                }
            }
            "refresh_library" -> {
                serverClients.values.firstOrNull { it.transport == conn }?.let { client ->
                    sendLibrary(client)
                }
            }
            "request" -> handleTransferRequest(conn, json)
        }
    }

    private fun markTransportClientSeen(conn: WebSocket) {
        serverClients.values.firstOrNull { it.transport == conn }?.let { client ->
            client.address = socketAddress(conn)
            client.lastSeenMs = System.currentTimeMillis()
            updateConnectedClients()
        }
    }

    private fun startTransportKeepAlive(conn: WebSocket): Job =
        scope.launch {
            while (conn.isOpen) {
                delay(TRANSPORT_KEEP_ALIVE_PING_MS)
                sendTransportPing(conn)
            }
        }

    private fun sendTransportPing(conn: WebSocket) {
        conn.takeIf { it.isOpen }?.let { socket ->
            runCatching {
                socket.send(JSONObject().put("type", "ping").toString())
            }
        }
    }

    private fun sendTransportPong(conn: WebSocket) {
        conn.takeIf { it.isOpen }?.let { socket ->
            runCatching {
                socket.send(JSONObject().put("type", "pong").toString())
            }
        }
    }

    private fun handleTransferRequest(conn: WebSocket, json: JSONObject) {
        transferRequests[conn] ?: return
        val originalId = json.optNullableString("originalId") ?: return
        val parts = json.optJSONArray("parts")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } ?: emptySet()
        scope.launch {
            val item = serverSongById(originalId)
            if (item == null) {
                conn.send(JSONObject().put("type", "error").put("message", "Item not found").toString())
                return@launch
            }
            runCatching {
                if ("art" in parts) {
                    findArtworkSource(item)?.let { sendSource(conn, "art", it) }
                }
                if ("lyric" in parts) {
                    findLyricSource(item)?.let { sendSource(conn, "lyric", it) }
                }
                if ("audio" in parts) {
                    findAudioSource(item)?.let { sendSource(conn, "audio", it) }
                }
                conn.send(JSONObject().put("type", "done").toString())
            }.onFailure {
                conn.takeIf { socket -> socket.isOpen }?.send(
                    JSONObject()
                        .put("type", "error")
                        .put("message", it.message ?: it.javaClass.name)
                        .toString()
                )
            }
        }
    }

    private suspend fun serverSongById(mediaId: String): MediaItem? {
        serverSongsById[mediaId]?.let { return it }
        val item = app.reader.songListFlow.first().firstOrNull { it.mediaId == mediaId }
            ?: return null
        serverSongsById = serverSongsById + (mediaId to item)
        return item
    }

    private fun sendSource(conn: WebSocket, part: String, source: TransferSource) {
        conn.send(
            JSONObject()
                .put("type", "file")
                .put("part", part)
                .put("name", source.name)
                .put("mimeType", source.mimeType)
                .put("size", source.size)
                .toString()
        )
        source.open().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (conn.isOpen) {
                val read = input.read(buffer)
                if (read < 0) break
                conn.send(ByteBuffer.wrap(buffer.copyOf(read)))
            }
        }
        conn.send(JSONObject().put("type", "file_done").put("part", part).toString())
    }

    private fun findAudioSource(item: MediaItem): TransferSource? {
        val uri = item.localConfiguration?.uri
        val file = item.getFile()
        return when {
            uri != null -> TransferSource(
                file?.name ?: "audio",
                item.localConfiguration?.mimeType,
                -1L
            ) { app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("open audio failed") }
            file?.canRead() == true -> TransferSource(file.name, item.localConfiguration?.mimeType, file.length()) {
                FileInputStream(file)
            }
            else -> null
        }
    }

    private fun findArtworkSource(item: MediaItem): TransferSource? {
        val uri = item.mediaMetadata.artworkUri ?: return null
        return TransferSource("art.jpg", "image/jpeg", -1L) {
            app.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("open artwork failed")
        }
    }

    private fun findLyricSource(item: MediaItem): TransferSource? {
        val musicFile = item.getFile() ?: return null
        val extensions = listOf("ttml", "srt", "lrc")
        extensions.forEach { ext ->
            val file = musicFile.resolveSibling(musicFile.nameWithoutExtension + ".$ext")
            if (file.canRead()) {
                return TransferSource(file.name, "text/plain", file.length()) {
                    FileInputStream(file)
                }
            }
        }
        val paths = extensions.map { musicFile.resolveSibling("${musicFile.nameWithoutExtension}.$it").path }
        app.contentResolver.query(
            MediaStoreCompat.FILES_EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA),
            "${MediaStore.Files.FileColumns.DATA} IN (${paths.joinToString { "?" }})",
            paths.toTypedArray(),
            null
        ).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val id = cursor.getLong(idColumn)
            val path = cursor.getString(dataColumn)
            val uri = ContentUris.withAppendedId(MediaStoreCompat.FILES_EXTERNAL_CONTENT_URI, id)
            return TransferSource(File(path).name, "text/plain", -1L) {
                app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("open lyric failed")
            }
        }
    }

    private fun closePendingClient(reason: String) {
        pendingServerClient?.close(reason)
        pendingServerClient = null
        pendingClient.value = null
    }

    private fun onServerClose(conn: WebSocket) {
        if (transferRequests.remove(conn) != null) {
            serverClients.values.firstOrNull { it.transfer == conn }?.transfer = null
            return
        }
        if (pendingServerClient?.transport == conn) {
            pendingServerClient = null
            pendingClient.value = null
        }
        val removed = serverClients.entries.firstOrNull {
            it.value.transport == conn
        }
        if (removed != null) {
            removed.value.close("Connection closed")
            serverClients.remove(removed.key)
            removed.value.transfer?.let { transferRequests.remove(it) }
            updateConnectedClients()
        }
    }

    private fun updateConnectedClients() {
        connectedClients.value = serverClients.values.map { it.toConnectedClient() }
    }

    private class SharingServer : WebSocketServer(InetSocketAddress(LibrarySharingManager.serverPort())) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val path = handshake.resourceDescriptor?.substringBefore('?') ?: ""
            if (path == TRANSFER_ROUTE) {
                LibrarySharingManager.onServerTransferOpen(conn, handshake)
            } else {
                LibrarySharingManager.onServerTransportOpen(conn, handshake)
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            LibrarySharingManager.onServerClose(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            LibrarySharingManager.onServerMessage(conn, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) = Unit
        override fun onStart() = Unit

        fun stopCatching() {
            runCatching { stop(1000) }
        }
    }

    private class SharingTransportClient(
        uri: URI,
        private val host: String,
        private val port: Int,
        private val sessionKey: String,
        private val sessionId: String?,
        private val completion: CompletableDeferred<StaticRemoteLibraryReader>
    ) : WebSocketClient(uri, sessionHeaders(sessionId)) {
        private var keepAliveJob: Job? = null

        override fun onOpen(handshakedata: ServerHandshake) {
            keepAliveJob = LibrarySharingManager.startTransportKeepAlive(this)
            send(
                JSONObject()
                    .put("type", "hello")
                    .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                    .toString()
            )
        }

        fun requestLibraryRefresh() {
            send(JSONObject().put("type", "refresh_library").toString())
        }

        override fun onMessage(message: String) {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return
            when (json.optString("type")) {
                "ping" -> LibrarySharingManager.sendTransportPong(this)
                "pong" -> Unit
                "accept_required" ->
                    LibrarySharingManager.clientState.value = ClientState.WaitingForAccept(host)
                "accepted" -> LibrarySharingManager.onTransportAccepted(
                    json.getString("sessionId"),
                    host,
                    port,
                    sessionKey
                )
                "library" -> LibrarySharingManager.onRemoteLibrary(
                    RemoteLibrary.fromJson(json.getJSONObject("library"))
                )
                "unauthorized" -> {
                    if (!sessionId.isNullOrBlank()) {
                        LibrarySharingManager.removeClientSession(sessionKey)
                    }
                    LibrarySharingManager.onClientFailure("Session revoked")
                    close()
                }
                "rejected" -> {
                    LibrarySharingManager.onClientFailure(
                        json.optString("reason", "Connection rejected")
                    )
                    close()
                }
                "disconnect" -> {
                    LibrarySharingManager.disconnectRemote(clearReader = true)
                    completion.completeExceptionally(IllegalStateException("Disconnected by server"))
                }
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            if (!completion.isCompleted) {
                LibrarySharingManager.onClientFailure(reason.ifBlank { "Connection closed" })
            }
        }

        override fun onError(ex: Exception) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            if (!completion.isCompleted) {
                LibrarySharingManager.onClientFailure(ex.message ?: ex.javaClass.name, ex)
            }
        }
    }

    private class ClientTransferSession(
        private val uri: URI,
        sessionId: String
    ) {
        private val mutex = Mutex()
        private val opened = CompletableDeferred<Unit>()
        private var incoming: IncomingTransfer? = null
        private val client = object : WebSocketClient(uri, sessionHeaders(sessionId)) {
            override fun onOpen(handshakedata: ServerHandshake) {
                opened.complete(Unit)
            }

            override fun onMessage(message: String) {
                val json = runCatching { JSONObject(message) }.getOrNull() ?: return
                incoming?.onText(json)
            }

            override fun onMessage(bytes: ByteBuffer) {
                incoming?.onBytes(bytes)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                val error = IllegalStateException(reason.ifBlank { "Transfer closed" })
                if (!opened.isCompleted) opened.completeExceptionally(error)
                incoming?.fail(error)
            }

            override fun onError(ex: Exception) {
                if (!opened.isCompleted) opened.completeExceptionally(ex)
                incoming?.fail(ex)
            }
        }

        fun connect() {
            client.connectionLostTimeout = 30
            client.connect()
        }

        fun close() {
            val error = IllegalStateException("Transfer closed")
            if (!opened.isCompleted) opened.completeExceptionally(error)
            incoming?.fail(error)
            runCatching { client.close() }
        }

        suspend fun requestFiles(
            item: MediaItem,
            originalId: String,
            expected: CachedRemoteFiles
        ): CachedRemoteFiles =
            mutex.withLock {
                withTimeout(30_000) { opened.await() }
                val parts = mutableListOf<String>()
                if (expected.art?.exists() != true) parts += "art"
                if (expected.lyric?.exists() != true) parts += "lyric"
                if (expected.audio?.exists() != true) parts += "audio"
                val transfer = IncomingTransfer(item, originalId, expected)
                incoming = transfer
                client.send(
                    JSONObject()
                        .put("type", "request")
                        .put("originalId", originalId)
                        .put("parts", JSONArray(parts))
                        .toString()
                )
                try {
                    transfer.await()
                } finally {
                    incoming = null
                }
            }
    }

    private class IncomingTransfer(
        private val item: MediaItem,
        originalId: String,
        expected: CachedRemoteFiles
    ) {
        private val libraryId = item.remoteLibraryId().orEmpty()
        private val key = LibrarySharingManager.cacheKey(libraryId, originalId)
        private val dir = LibrarySharingManager.cacheDir(libraryId)
        private val audioExtension = item.remoteFileExtension()
            ?: item.getFile()?.extension?.takeIf { it.isNotBlank() }
            ?: "bin"
        private var audioFile = File(dir, "$key.$audioExtension")
        private var currentPart: String? = null
        private var currentOutput: java.io.OutputStream? = null
        private var artFile: File? = expected.art
        private var lyricFile: File? = expected.lyric
        private val done = CompletableDeferred<CachedRemoteFiles>()

        fun onText(json: JSONObject) {
            when (json.optString("type")) {
                "file" -> startFile(json)
                "file_done" -> finishFile()
                "done" -> {
                    finishFile()
                    if (!audioFile.exists()) {
                        fail(IllegalStateException("Audio missing from transfer"))
                    } else {
                        done.complete(
                            CachedRemoteFiles(
                                audioFile,
                                artFile?.takeIf { it.exists() },
                                lyricFile?.takeIf { it.exists() }
                            )
                        )
                    }
                }
                "error" -> fail(IllegalStateException(json.optString("message", "Transfer failed")))
            }
        }

        fun onBytes(bytes: ByteBuffer) {
            val out = currentOutput ?: return
            if (bytes.hasArray()) {
                val length = bytes.remaining()
                out.write(bytes.array(), bytes.arrayOffset() + bytes.position(), length)
                bytes.position(bytes.limit())
            } else {
                val buffer = ByteArray(bytes.remaining())
                bytes.get(buffer)
                out.write(buffer)
            }
        }

        fun fail(t: Throwable) {
            runCatching { currentOutput?.close() }
            if (!done.isCompleted) {
                runCatching { audioFile.delete() }
                runCatching { artFile?.delete() }
                runCatching { lyricFile?.delete() }
                done.completeExceptionally(t)
            }
        }

        suspend fun await(): CachedRemoteFiles = done.await()

        private fun startFile(json: JSONObject) {
            finishFile()
            currentPart = json.getString("part")
            val name = json.optString("name", "")
            val ext = File(name).extension.takeIf { it.isNotBlank() }
            val target = when (currentPart) {
                "audio" -> File(dir, "$key.${ext ?: audioExtension}").also { audioFile = it }
                "art" -> File(dir, "$key.${ext ?: "jpg"}").also { artFile = it }
                "lyric" -> File(dir, "$key.${ext ?: "lrc"}").also { lyricFile = it }
                else -> File(dir, "$key.tmp")
            }
            currentOutput = target.outputStream()
        }

        private fun finishFile() {
            currentOutput?.close()
            currentOutput = null
            currentPart = null
        }
    }

    private data class ServerClientConnection(
        val id: Int,
        val transport: WebSocket,
        var address: String,
        var sessionId: String?,
    ) {
        var accepted = false
        var deviceName: String? = null
        var transfer: WebSocket? = null
        var lastSeenMs: Long = System.currentTimeMillis()
        private var keepAliveJob: Job? = null

        fun toPendingClient() = PendingClient(id, address, deviceName)
        fun toConnectedClient() = ConnectedClient(id, address, deviceName, sessionId, lastSeenMs)
        fun startTransportKeepAlive() {
            keepAliveJob?.cancel()
            keepAliveJob = LibrarySharingManager.startTransportKeepAlive(transport)
        }
        fun close(reason: String) {
            keepAliveJob?.cancel()
            keepAliveJob = null
            runCatching {
                transport.takeIf { it.isOpen }?.send(
                    JSONObject().put("type", "disconnect").put("reason", reason).toString()
                )
            }
            runCatching { transfer?.close(1000, reason) }
            runCatching { transport.close(1000, reason) }
        }
    }

    data class PendingClient(val id: Int, val address: String, val deviceName: String?)
    data class ReconnectCandidate(val host: String, val deviceName: String?)
    data class SavedServerSession(
        val sessionId: String,
        val deviceName: String?,
        val address: String?
    )
    data class SavedClientServer(val serverKey: String, val sessionId: String?)
    data class ClearedCache(val filesDeleted: Int, val bytesDeleted: Long)
    data class ConnectedClient(
        val id: Int,
        val address: String,
        val deviceName: String?,
        val sessionId: String?,
        val lastSeenMs: Long
    )
    data class CachedRemoteFiles(val audio: File?, val art: File?, val lyric: File?)
    private data class LocalPlaybackSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffleModeEnabled: Boolean
    )
    private data class AuthorizedSession(val deviceName: String?)
    private data class ConnectTarget(
        val address: InetAddress,
        val hostAddress: String,
        val uriHost: String,
        val port: Int,
        val sessionKey: String,
        val display: String
    )

    sealed interface ClientState {
        data object Idle : ClientState
        data class Connecting(val host: String) : ClientState
        data class WaitingForAccept(val host: String) : ClientState
        data class TransferringLibrary(val host: String) : ClientState
        data class Connected(val deviceName: String?) : ClientState
        data class Failed(val reason: String) : ClientState
    }

    private data class TransferSource(
        val name: String,
        val mimeType: String?,
        val size: Long,
        val open: () -> InputStream
    )
}

private fun sessionHeaders(sessionId: String?): Map<String, String> =
    if (sessionId.isNullOrBlank()) {
        emptyMap()
    } else {
        mapOf(HEADER_SESSION_ID to sessionId)
    }

private fun ClientHandshake.sessionId(): String =
    getFieldValue(HEADER_SESSION_ID)?.trim().orEmpty()

private fun uriHost(host: String): String =
    if (host.contains(':') && !host.startsWith('[')) "[$host]" else host

private fun socketAddress(conn: WebSocket): String =
    conn.remoteSocketAddress.address?.hostAddress ?: conn.remoteSocketAddress.hostString

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name)

private fun InetAddress.isLanAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress) {
        return true
    }
    if (this is Inet4Address) {
        val bytes = address.map { it.toInt() and 0xff }
        return bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168)
    }
    if (this is Inet6Address) {
        val first = address[0].toInt() and 0xfe
        return first == 0xfc
    }
    return false
}
