package org.futo.inputmethod.latin.uix.sync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.min

class XloidShareActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = firstSharedUri(intent)
        if (uri == null) {
            toast("No file to share")
            finish()
            return
        }

        scope.launch {
            try {
                val link = withContext(Dispatchers.IO) { uploadSharedFile(uri) }
                copyLink(link)
                toast("Link copied and sent")
            } catch (e: Exception) {
                toast("Share failed: ${e.message ?: "unknown error"}")
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun firstSharedUri(intent: Intent): Uri? {
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            return uris?.firstOrNull()
        }

        return null
    }

    private fun uploadSharedFile(uri: Uri): String {
        val fileName = displayName(uri)
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        val size = contentLength(uri)

        if (size != null) {
            if (size > MaxSharedFileBytes) throw IllegalArgumentException("file is over 3 GB")
            if (size > MaxSingleWorkerUploadBytes) {
                return uploadMultipartFile(uri, fileName, mime, size)
            }
        }

        return uploadSmallFile(uri, fileName, mime)
    }

    private fun uploadSmallFile(uri: Uri, fileName: String, mime: String): String {
        val temp = copyToTempFile(uri)

        try {
            val connection = URL(shareUrl()).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setFixedLengthStreamingMode(temp.length())
            connection.setRequestProperty("Authorization", "Bearer ${relayToken()}")
            connection.setRequestProperty("Content-Type", mime)
            connection.setRequestProperty("X-File-Name", fileName)
            connection.setRequestProperty("X-Device-Id", XloidClipboardSync.getOrCreateDeviceId(this))

            temp.inputStream().use { input ->
                connection.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val responseText = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP ${connection.responseCode} $error")
            }

            return JSONObject(responseText).getString("url")
        } finally {
            temp.delete()
        }
    }

    private fun uploadMultipartFile(uri: Uri, fileName: String, mime: String, size: Long): String {
        val start = postJson(
            endpoint = "${shareUrl()}/direct/start",
            body = JSONObject()
                .put("name", fileName)
                .put("mime", mime)
                .put("bytes", size)
        )

        val id = start.getString("id")
        val uploadId = start.getString("uploadId")
        val serverName = start.getString("name")
        val partSize = min(start.optLong("partSize", MultipartPartBytes), MultipartPartBytes).toInt()
        val parts = mutableListOf<UploadedPart>()
        var uploaded = 0L
        var partNumber = 1

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                while (uploaded < size) {
                    val bytesToRead = min(partSize.toLong(), size - uploaded).toInt()
                    val chunk = input.readChunk(bytesToRead)
                    if (chunk.isEmpty()) break

                    val partResponse = postJson(
                        endpoint = "${shareUrl()}/direct/part",
                        body = JSONObject()
                            .put("id", id)
                            .put("uploadId", uploadId)
                            .put("name", serverName)
                            .put("partNumber", partNumber)
                    )

                    parts.add(UploadedPart(partNumber, putDirectPart(partResponse, chunk)))
                    uploaded += chunk.size
                    partNumber += 1
                }
            } ?: throw IllegalArgumentException("could not open file")

            if (uploaded != size) {
                throw IllegalStateException("only uploaded $uploaded of $size bytes")
            }

            val partsJson = JSONArray()
            parts.forEach {
                partsJson.put(
                    JSONObject()
                        .put("partNumber", it.partNumber)
                        .put("etag", it.etag)
                )
            }

            return postJson(
                endpoint = "${shareUrl()}/direct/complete",
                body = JSONObject()
                    .put("id", id)
                    .put("uploadId", uploadId)
                    .put("name", serverName)
                    .put("mime", mime)
                    .put("bytes", size)
                    .put("parts", partsJson)
            ).getString("url")
        } catch (e: Exception) {
            try {
                postJson(
                    endpoint = "${shareUrl()}/direct/abort",
                    body = JSONObject()
                        .put("id", id)
                        .put("uploadId", uploadId)
                        .put("name", serverName)
                )
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Authorization", "Bearer ${relayToken()}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Device-Id", XloidClipboardSync.getOrCreateDeviceId(this))
        connection.outputStream.use { it.write(bytes) }
        return JSONObject(readResponse(connection))
    }

    private fun putDirectPart(partResponse: JSONObject, bytes: ByteArray): String {
        val endpoint = partResponse.getString("url")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.setFixedLengthStreamingMode(bytes.size)
        partResponse.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { key ->
                connection.setRequestProperty(key, headers.getString(key))
            }
        }
        connection.outputStream.use { it.write(bytes) }
        readResponse(connection)
        return connection.getHeaderField("ETag")
            ?: throw IllegalStateException("R2 did not return ETag")
    }

    private fun readResponse(connection: HttpURLConnection): String {
        return if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("HTTP ${connection.responseCode} $error")
        }
    }

    private fun copyToTempFile(uri: Uri): File {
        val temp = File.createTempFile("xloid-share-", ".tmp", cacheDir)
        var total = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MaxSingleWorkerUploadBytes) {
                        throw IllegalArgumentException("file is too large for simple upload")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("could not open file")

        if (total == 0L) throw IllegalArgumentException("empty file")
        return temp
    }

    private fun InputStream.readChunk(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(maxBytes)
        val buffer = ByteArray(64 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = read(buffer, 0, min(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun shareUrl(): String {
        val base = getSettingBlocking(XloidClipboardSyncRoomUrl)
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .trimEnd('/')
        val pairId = URLEncoder.encode(getSettingBlocking(XloidClipboardSyncPairId), "UTF-8")
        return "$base/$pairId/share"
    }

    private fun relayToken(): String = getSettingBlocking(XloidClipboardSyncRelayToken)

    private fun displayName(uri: Uri): String {
        queryDisplayName(uri)?.let { return it }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor: Cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) return null
            return it.getString(index)?.takeIf { name -> name.isNotBlank() }
        }
    }

    private fun contentLength(uri: Uri): Long? {
        val cursor: Cursor = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0) return null
            val size = it.getLong(index)
            return size.takeIf { value -> value > 0L }
        }
    }

    private fun copyLink(link: String) {
        XloidClipboardSync.markLocalTextApplied(link)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Xloidflare synced share link", link))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MaxSharedFileBytes = 3L * 1024L * 1024L * 1024L
        private const val MaxSingleWorkerUploadBytes = 95L * 1024L * 1024L
        private const val MultipartPartBytes = 64L * 1024L * 1024L
    }
}

private data class UploadedPart(
    val partNumber: Int,
    val etag: String
)
