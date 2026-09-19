package com.htn.breadboardar.network

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads one self-contained GLB over ordinary HTTP(S).
 *
 * This deliberately is not a WebSocket transport: the phone pulls a completed model
 * from a stable backend URL, verifies the binary before Filament sees it, then keeps
 * the direct buffer alive in the renderer. Calling [download] again makes only the
 * newest request eligible to replace the current model.
 */
internal class GlbModelDownloader(
    private val listener: Listener,
) {
    interface Listener {
        fun onGlbDownloaded(buffer: ByteBuffer, sourceUrl: String)
        fun onGlbDownloadFailed(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val requestGeneration = AtomicInteger()

    @Volatile
    private var closed = false

    /** Starts an asynchronous GET request. The URL must point directly to a `.glb` file. */
    fun download(rawUrl: String) {
        val sourceUrl = rawUrl.trim()
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) {
            "Enter an http:// or https:// URL for a .glb file."
        }
        val generation = requestGeneration.incrementAndGet()
        executor.execute {
            val result = runCatching { fetch(sourceUrl) }
            if (closed || generation != requestGeneration.get()) return@execute
            mainHandler.post {
                if (closed || generation != requestGeneration.get()) return@post
                result
                    .onSuccess { listener.onGlbDownloaded(it, sourceUrl) }
                    .onFailure { error ->
                        listener.onGlbDownloadFailed(
                            error.message ?: "Could not download the GLB model.",
                        )
                    }
            }
        }
    }

    /** Cancels pending delivery and releases the single background worker. */
    fun close() {
        closed = true
        requestGeneration.incrementAndGet()
        executor.shutdownNow()
    }

    private fun fetch(sourceUrl: String): ByteBuffer {
        val connection = (URL(sourceUrl).openConnection() as? HttpURLConnection)
            ?: throw IOException("The model URL must use HTTP or HTTPS.")
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "model/gltf-binary, application/octet-stream")

            val status = connection.responseCode
            if (status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
                throw IOException("Model server returned HTTP $status.")
            }

            val declaredSize = connection.contentLengthLong
            if (declaredSize > GlbFormat.MAX_BYTES) {
                throw IOException("The GLB is larger than ${GlbFormat.MAX_MEBIBYTES} MiB.")
            }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (contentType != null && contentType.isNotBlank() &&
                contentType !in ACCEPTED_CONTENT_TYPES
            ) {
                throw IOException(
                    "Expected a GLB response, but the server returned Content-Type $contentType.",
                )
            }

            val bytes = connection.inputStream.use { input -> readAtMost(input, declaredSize) }
            GlbFormat.requireValid(bytes)
            return ByteBuffer.allocateDirect(bytes.size)
                .order(ByteOrder.nativeOrder())
                .apply {
                    put(bytes)
                    rewind()
                }
        } finally {
            connection.disconnect()
        }
    }

    private fun readAtMost(input: InputStream, declaredSize: Long): ByteArray {
        val initialSize = declaredSize.coerceIn(0L, GlbFormat.MAX_BYTES.toLong()).toInt()
        val output = ByteArrayOutputStream(initialSize)
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (output.size() + count > GlbFormat.MAX_BYTES) {
                throw IOException("The GLB is larger than ${GlbFormat.MAX_MEBIBYTES} MiB.")
            }
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
        val ACCEPTED_CONTENT_TYPES = setOf(
            "model/gltf-binary",
            "application/octet-stream",
            "application/gltf-buffer",
        )
    }
}

/** Small, dependency-free validation before passing untrusted bytes into native Filament. */
internal object GlbFormat {
    const val MAX_MEBIBYTES = 32
    const val MAX_BYTES = MAX_MEBIBYTES * 1024 * 1024
    // A GLB needs its 12-byte header plus the first 8-byte chunk header. glTF 2.0
    // requires that first chunk to be the JSON document; accepting only the 12-byte
    // header would let a truncated download reach native rendering code.
    private const val FIRST_CHUNK_HEADER_SIZE = 20
    private const val GLB_VERSION_2 = 2L
    private const val JSON_CHUNK_TYPE = 0x4E4F534AL // "JSON" encoded little-endian.

    fun requireValid(bytes: ByteArray) {
        if (bytes.size < FIRST_CHUNK_HEADER_SIZE) {
            throw IOException("The download is too small to be a GLB file.")
        }
        if (bytes[0] != 'g'.code.toByte() ||
            bytes[1] != 'l'.code.toByte() ||
            bytes[2] != 'T'.code.toByte() ||
            bytes[3] != 'F'.code.toByte()
        ) {
            throw IOException("The download is not a GLB file (missing glTF header).")
        }
        val version = littleEndianUInt(bytes, 4)
        if (version != GLB_VERSION_2) {
            throw IOException("Only binary glTF 2.0 (.glb) files are supported.")
        }
        val declaredSize = littleEndianUInt(bytes, 8)
        if (declaredSize != bytes.size.toLong()) {
            throw IOException("The GLB length in its header does not match the downloaded file.")
        }
        val jsonChunkLength = littleEndianUInt(bytes, 12)
        if (littleEndianUInt(bytes, 16) != JSON_CHUNK_TYPE) {
            throw IOException("The first GLB chunk must be JSON.")
        }
        if (FIRST_CHUNK_HEADER_SIZE + jsonChunkLength > bytes.size.toLong()) {
            throw IOException("The first GLB JSON chunk extends past the downloaded file.")
        }
    }

    private fun littleEndianUInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
}
