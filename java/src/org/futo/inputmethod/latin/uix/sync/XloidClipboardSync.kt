package org.futo.inputmethod.latin.uix.sync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.actions.clipboard.CLIPBOARD_AUTHORITY
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardPasteRequest
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardProviderState
import org.futo.inputmethod.latin.uix.actions.clipboard.ScreenshotHelper
import org.futo.inputmethod.latin.uix.actions.clipboard.ScreenshotListener
import org.futo.inputmethod.latin.uix.getSettingFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory

val XloidClipboardSyncEnabled = SettingsKey(
    booleanPreferencesKey("xloid_clipboard_sync_enabled"),
    true
)

val XloidClipboardSyncRoomUrl = SettingsKey(
    stringPreferencesKey("xloid_clipboard_sync_room_url"),
    BuildConfig.XLOIDFLARE_DEFAULT_ROOM_URL
)

val XloidClipboardSyncPairId = SettingsKey(
    stringPreferencesKey("xloid_clipboard_sync_pair_id"),
    BuildConfig.XLOIDFLARE_DEFAULT_PAIR_ID
)

val XloidClipboardSyncRelayToken = SettingsKey(
    stringPreferencesKey("xloid_clipboard_sync_relay_token"),
    BuildConfig.XLOIDFLARE_RELAY_TOKEN
)

val XloidClipboardSyncScreenshots = SettingsKey(
    booleanPreferencesKey("xloid_clipboard_sync_screenshots"),
    false
)

private data class SyncConfig(
    val enabled: Boolean,
    val roomUrl: String,
    val pairId: String,
    val relayToken: String
) {
    val isUsable: Boolean
        get() = enabled && roomUrl.isNotBlank() && pairId.length >= 12 && relayToken.isNotBlank()
}

private data class LocalClipboardPayload(
    val mime: String,
    val bytes: ByteArray,
    val digest: String,
    val source: String
)

object XloidClipboardSync {
    private const val Tag = "XloidClipboardSync"
    private const val MaxTextBytes = 96 * 1024
    private const val MaxImageBytes = 8 * 1024 * 1024
    private const val ClipTtlMs = 60L * 1000L
    private const val RecentDigestTtlMs = 2L * 60L * 1000L
    private const val SyncedClipExtra = "org.futo.inputmethod.latin.xloidflare.SYNCED_CLIP"
    private const val SourceClipboard = "clipboard"
    private const val SourceScreenshot = "screenshot"
    private val ImageMimeTypes = setOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp")
    private val secureRandom = SecureRandom()

    private var started = false
    private var config: SyncConfig? = null
    private var deviceId: String? = null
    private var websocket: SimpleWebSocket? = null
    private var listenerRegistered = false
    private val recentDigests = mutableMapOf<String, Long>()
    private var sendScope: CoroutineScope? = null
    private var screenshotHelper: ScreenshotHelper? = null

    private lateinit var appContext: Context
    private lateinit var clipboardManager: ClipboardManager

    private val primaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        val activeConfig = config ?: return@OnPrimaryClipChangedListener
        if (!activeConfig.isUsable) return@OnPrimaryClipChangedListener

        val context = appContext
        val payload = try {
            readLocalClipboard(context) ?: return@OnPrimaryClipChangedListener
        } catch (e: Exception) {
            Log.w(Tag, "Failed reading local clipboard", e)
            return@OnPrimaryClipChangedListener
        }

        if (shouldIgnoreDigest(payload.digest)) {
            return@OnPrimaryClipChangedListener
        }

        rememberDigest(payload.digest)
        sendPayload(activeConfig, payload)
    }

    private val screenshotListener = object : ScreenshotListener {
        override fun onScreenshotAdded(mime: String, uri: Uri) {
            val activeConfig = config ?: return
            if (!activeConfig.isUsable) return

            sendScope?.launch(Dispatchers.IO) {
                val payload = try {
                    readImageUriPayload(appContext, mime, uri, SourceScreenshot) ?: return@launch
                } catch (e: Exception) {
                    Log.w(Tag, "Failed reading screenshot", e)
                    return@launch
                }

                if (shouldIgnoreDigest(payload.digest)) return@launch

                rememberDigest(payload.digest)
                sendPayload(activeConfig, payload)
            }
        }

        override fun onScreenshotChange(uri: Uri, checkTrashed: suspend () -> Boolean) = Unit
    }

    fun start(context: Context, scope: CoroutineScope) {
        if (started) return
        started = true

        appContext = context.applicationContext
        clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        deviceId = getOrCreateDeviceId(appContext)
        sendScope = scope
        screenshotHelper = ScreenshotHelper(
            context = appContext,
            lifecycleScope = scope,
            listener = screenshotListener,
            enabledSetting = XloidClipboardSyncScreenshots
        )

        scope.launch(Dispatchers.Main) {
            if (!listenerRegistered) {
                clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener)
                listenerRegistered = true
            }
        }

        scope.launch(Dispatchers.IO) {
            combine(
                appContext.getSettingFlow(XloidClipboardSyncEnabled),
                appContext.getSettingFlow(XloidClipboardSyncRoomUrl),
                appContext.getSettingFlow(XloidClipboardSyncPairId),
                appContext.getSettingFlow(XloidClipboardSyncRelayToken)
            ) { enabled, roomUrl, pairId, token ->
                SyncConfig(enabled, roomUrl.trim(), pairId.trim(), token.trim())
            }.distinctUntilChanged().collectLatest { newConfig ->
                config = newConfig
                disconnect()

                if (newConfig.isUsable) {
                    connectLoop(newConfig, this)
                }
            }
        }
    }

    fun stop() {
        disconnect()
        screenshotHelper?.onDestroy()
        screenshotHelper = null
        if (listenerRegistered) {
            clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener)
            listenerRegistered = false
        }
        started = false
        sendScope = null
    }

    private suspend fun connectLoop(activeConfig: SyncConfig, scope: kotlinx.coroutines.CoroutineScope) {
        while (scope.isActive && config == activeConfig && activeConfig.isUsable) {
            try {
                val socket = SimpleWebSocket.connect(
                    url = roomUrl(activeConfig),
                    authorization = "Bearer ${activeConfig.relayToken}"
                )
                websocket = socket
                socket.readLoop { message ->
                    handleRemoteMessage(activeConfig, message)
                }
            } catch (e: Exception) {
                Log.w(Tag, "WebSocket disconnected", e)
            } finally {
                disconnect()
            }

            delay(2_000L)
        }
    }

    private fun disconnect() {
        websocket?.close()
        websocket = null
    }

    private fun sendPayload(activeConfig: SyncConfig, payload: LocalClipboardPayload) {
        val socket = websocket ?: return
        sendScope?.launch(Dispatchers.IO) {
            try {
                socket.sendText(buildClipPacket(activeConfig, payload))
            } catch (e: Exception) {
                Log.w(Tag, "Failed sending clipboard packet", e)
            }
        }
    }

    private fun roomUrl(config: SyncConfig): String {
        val base = config.roomUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')
        return "$base/${config.pairId}"
    }

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("xloid_clipboard_sync", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing

        val newId = "android-${UUID.randomUUID()}"
        prefs.edit().putString("device_id", newId).apply()
        return newId
    }

    fun markLocalTextApplied(text: String) {
        rememberDigest(digest("text/plain", text.toByteArray(Charsets.UTF_8)))
    }

    private fun readLocalClipboard(context: Context): LocalClipboardPayload? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount < 1) return null

        val description = clip.description ?: return null
        val isSensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        } else {
            false
        }
        if (isSensitive) return null
        if (isSyncedClip(description)) return null

        val item = clip.getItemAt(0) ?: return null
        val mimeTypes = description.mimeTypes()
        val uri = item.uri

        if (uri != null) {
            val imageMime = mimeTypes.firstOrNull { it in ImageMimeTypes }
                ?: context.contentResolver.getType(uri)?.takeIf { it in ImageMimeTypes }
            if (imageMime != null) {
                return readImageUriPayload(context, imageMime, uri, SourceClipboard)
            }
        }

        val text = item.coerceToText(context)?.toString() ?: return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MaxTextBytes) return null

        return LocalClipboardPayload("text/plain", bytes, digest("text/plain", bytes), SourceClipboard)
    }

    private fun readImageUriPayload(context: Context, mime: String, uri: Uri, source: String): LocalClipboardPayload? {
        val normalizedMime = normalizeMime(mime)
        if (normalizedMime !in ImageMimeTypes) return null

        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readLimited(MaxImageBytes)
        } ?: return null
        if (bytes.isEmpty()) return null

        return LocalClipboardPayload(normalizedMime, bytes, digest(normalizedMime, bytes), source)
    }

    private fun isSyncedClip(description: ClipDescription): Boolean {
        if (description.label?.toString()?.startsWith("Xloidflare synced") == true) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            description.extras?.getBoolean(SyncedClipExtra, false) == true
        } else {
            false
        }
    }

    private fun ClipDescription.mimeTypes(): List<String> =
        (0 until mimeTypeCount).map { getMimeType(it) }

    private fun normalizeMime(mime: String): String =
        if (mime == "image/jpg") "image/jpeg" else mime

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IllegalArgumentException("Clipboard payload too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun buildClipPacket(config: SyncConfig, payload: LocalClipboardPayload): String {
        val clipId = UUID.randomUUID().toString()
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val encrypted = encrypt(config, nonce, payload.bytes)
        val now = System.currentTimeMillis()

        return JSONObject()
            .put("kind", "clip")
            .put("clipId", clipId)
            .put("fromDevice", deviceId)
            .put("source", payload.source)
            .put("mime", payload.mime)
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("createdAt", now)
            .put("expiresAt", now + ClipTtlMs)
            .toString()
    }

    private suspend fun handleRemoteMessage(config: SyncConfig, message: String) {
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            return
        }

        if (json.optString("kind") == "latest") {
            handleRemoteMessage(config, json.optJSONObject("clip")?.toString() ?: return)
            return
        }

        if (json.optString("kind") != "clip") return
        if (json.optString("fromDevice") == deviceId) return
        if (json.optLong("expiresAt", 0L) <= System.currentTimeMillis()) return

        val mime = normalizeMime(json.optString("mime"))
        if (mime != "text/plain" && mime !in ImageMimeTypes) return

        val nonce = Base64.decode(json.optString("nonce"), Base64.DEFAULT)
        val encrypted = Base64.decode(json.optString("ciphertext"), Base64.DEFAULT)
        val plaintext = try {
            decrypt(config, nonce, encrypted)
        } catch (e: Exception) {
            Log.w(Tag, "Failed decrypting remote clipboard packet", e)
            return
        }

        val digest = digest(mime, plaintext)
        withContext(Dispatchers.Main) {
            applyRemoteClipboard(mime, plaintext, digest, json.optString("clipId"))
        }
    }

    private fun applyRemoteClipboard(mime: String, bytes: ByteArray, digest: String, clipId: String) {
        rememberDigest(digest)

        if (mime == "text/plain") {
            clipboardManager.setPrimaryClip(syncedTextClip(bytes.toString(Charsets.UTF_8)))
            return
        }

        val file = writeSyncedImage(bytes, mime, clipId)
        val requestId = ClipboardProviderState.addRequest(
            ClipboardPasteRequest(
                file = file,
                mimeType = mime,
                expiration = System.currentTimeMillis() + 60L * 60L * 1000L
            )
        )
        val uri = Uri.parse("content://$CLIPBOARD_AUTHORITY/clip/$requestId")
        val clipData = ClipData(
            syncedClipDescription("Xloidflare synced image", arrayOf(mime)),
            ClipData.Item(uri)
        )
        clipboardManager.setPrimaryClip(clipData)
    }

    private fun syncedTextClip(text: String): ClipData {
        val clip = ClipData.newPlainText("Xloidflare synced text", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = syncedExtras()
        }
        return clip
    }

    private fun syncedClipDescription(label: String, mimeTypes: Array<String>): ClipDescription {
        return ClipDescription(label, mimeTypes).also { description ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                description.extras = syncedExtras()
            }
        }
    }

    private fun syncedExtras(): PersistableBundle =
        PersistableBundle().apply {
            putBoolean(SyncedClipExtra, true)
        }

    private fun writeSyncedImage(bytes: ByteArray, mime: String, clipId: String): File {
        val dir = File(appContext.cacheDir, "xloid_clipboard_sync").also { it.mkdirs() }
        val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
        dir.listFiles()?.forEach {
            if (it.lastModified() < cutoff) it.delete()
        }

        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val safeName = clipId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safeName.$extension").also {
            it.writeBytes(bytes)
        }
    }

    private fun keyFor(config: SyncConfig): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("xloidflare:${config.pairId}:${config.relayToken}".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private fun encrypt(config: SyncConfig, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(config), GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(config: SyncConfig, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFor(config), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun digest(mime: String, bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(mime.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rememberDigest(digest: String) {
        synchronized(recentDigests) {
            pruneRecentDigestsLocked()
            recentDigests[digest] = System.currentTimeMillis() + RecentDigestTtlMs
        }
    }

    private fun shouldIgnoreDigest(digest: String): Boolean {
        synchronized(recentDigests) {
            pruneRecentDigestsLocked()
            return recentDigests.containsKey(digest)
        }
    }

    private fun pruneRecentDigestsLocked() {
        val now = System.currentTimeMillis()
        recentDigests.entries.removeIf { it.value <= now }
    }
}

private class SimpleWebSocket private constructor(
    private val socket: javax.net.ssl.SSLSocket,
    private val input: InputStream,
    private val output: OutputStream
) {
    private val writeLock = Object()
    @Volatile private var closed = false

    companion object {
        fun connect(url: String, authorization: String): SimpleWebSocket {
            val uri = URI(url)
            require(uri.scheme == "wss") { "Only wss:// sync relay URLs are supported" }

            val port = if (uri.port > 0) uri.port else 443
            val socket = SSLSocketFactory.getDefault().createSocket(uri.host, port) as javax.net.ssl.SSLSocket
            socket.soTimeout = 5_000
            socket.startHandshake()

            val input = socket.inputStream
            val output = socket.outputStream
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val websocketKey = Base64.encodeToString(key, Base64.NO_WRAP)
            val path = buildString {
                append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
            }

            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(uri.host).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: ").append(websocketKey).append("\r\n")
                append("Authorization: ").append(authorization).append("\r\n")
                append("\r\n")
            }

            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val response = readHttpHeaders(input)
            if (!response.startsWith("HTTP/1.1 101") && !response.startsWith("HTTP/1.0 101")) {
                socket.close()
                throw IllegalStateException("WebSocket upgrade failed: ${response.lineSequence().firstOrNull()}")
            }

            return SimpleWebSocket(socket, input, output)
        }

        private fun readHttpHeaders(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val tail = ArrayDeque<Int>()
            while (true) {
                val value = input.read()
                if (value < 0) throw EOFException("Unexpected EOF during WebSocket handshake")
                out.write(value)
                tail.addLast(value)
                if (tail.size > 4) tail.removeFirst()
                if (tail.size == 4 && tail.toList() == listOf(13, 10, 13, 10)) break
                if (out.size() > 32 * 1024) throw IllegalStateException("WebSocket handshake too large")
            }
            return out.toString("US-ASCII")
        }
    }

    suspend fun readLoop(onText: suspend (String) -> Unit) {
        while (!closed && currentCoroutineContext().isActive) {
            val frame = try {
                readFrame() ?: break
            } catch (_: SocketTimeoutException) {
                continue
            }

            when (frame.opcode) {
                0x1 -> onText(frame.payload.toString(Charsets.UTF_8))
                0x8 -> break
                0x9 -> sendFrame(0xA, frame.payload)
            }
        }
    }

    fun sendText(text: String) {
        sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    fun close() {
        closed = true
        try {
            sendFrame(0x8, ByteArray(0))
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null

        val opcode = first and 0x0F
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((readByte() shl 8) or readByte()).toLong()
        } else if (length == 127L) {
            length = 0L
            repeat(8) {
                length = (length shl 8) or readByte().toLong()
            }
        }

        val masked = (second and 0x80) != 0
        val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
        if (length > Int.MAX_VALUE) throw IllegalStateException("Frame too large")

        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
            val read = input.read(payload, offset, payload.size - offset)
            if (read < 0) throw EOFException("Unexpected EOF in WebSocket frame")
            offset += read
        }

        if (mask != null) {
            payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        }

        return Frame(opcode, payload)
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Unexpected EOF in WebSocket frame")
        return value and 0xFF
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            if (closed && opcode != 0x8) return

            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(0x80 or 126)
                    header.write((payload.size ushr 8) and 0xFF)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    val length = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        header.write(((length ushr shift) and 0xFF).toInt())
                    }
                }
            }

            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            header.write(mask)
            val maskedPayload = ByteArray(payload.size)
            payload.indices.forEach { maskedPayload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }

            output.write(header.toByteArray())
            output.write(maskedPayload)
            output.flush()
        }
    }
}
