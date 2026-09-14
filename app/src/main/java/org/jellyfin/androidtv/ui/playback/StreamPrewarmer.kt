package org.jellyfin.androidtv.ui.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.data.compat.StreamInfo
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Gets a Jellyfin transcode producing BEFORE the player is asked to play it.
 *
 * Measured on 2026-09-14: the server takes 10-15 seconds to spawn ffmpeg for a newly requested
 * stream, and that is the bulk of what the viewer sees as a freeze when the quality changes. It is
 * the SERVER's latency, so no amount of client buffering or bandwidth juggling can shorten it - but
 * it can be moved off the critical path, because the stream we are leaving is still playing happily
 * out of its own buffer while this runs.
 *
 * So: walk the HLS manifest ourselves and pull the one segment the player will ask for first. That
 * request is what makes Jellyfin start the encode, and by the time the player is handed the URL the
 * segments already exist. The fetch uses the same URL the player will use, so it lands on the same
 * PlaySessionId rather than starting a second transcode.
 *
 * Everything here is best-effort. Any failure, any timeout, and playback proceeds exactly as it did
 * before - a slow change is much better than a broken one.
 */
object StreamPrewarmer {
	/** Bytes of the first segment to pull. Enough to force the encoder to produce, not to buffer. */
	private const val SEGMENT_PROBE_BYTES = 256 * 1024

	private const val CONNECT_TIMEOUT_MS = 8_000
	private const val READ_TIMEOUT_MS = 20_000

	/**
	 * Fetches the manifest and the first segment the player will want at [startPositionMs].
	 * Returns true if a segment actually came back, i.e. the encode is running.
	 */
	suspend fun prewarm(streamInfo: StreamInfo, startPositionMs: Long, timeoutMs: Long): Boolean =
		withTimeoutOrNull(timeoutMs) {
			withContext(Dispatchers.IO) {
				runCatching { fetchFirstSegment(streamInfo, startPositionMs) }
					.onFailure { Timber.d(it, "Adaptive bitrate: could not pre-warm the new stream") }
					.getOrDefault(false)
			}
		} ?: false

	private fun fetchFirstSegment(streamInfo: StreamInfo, startPositionMs: Long): Boolean {
		val master = streamInfo.mediaUrl ?: return false
		if (!master.contains(".m3u8")) return false          // direct play needs no warming

		val masterText = readText(master) ?: return false
		val variant = firstVariantUrl(master, masterText) ?: master
		val variantText = readText(variant) ?: return false
		val segment = segmentAt(variant, variantText, startPositionMs) ?: return false

		val bytes = readBytes(segment, SEGMENT_PROBE_BYTES)
		Timber.i("Adaptive bitrate: pre-warmed the new stream, %d kB of its first segment", bytes / 1024)
		return bytes > 0
	}

	/** The first `#EXT-X-STREAM-INF` entry. Jellyfin only ever offers one. */
	private fun firstVariantUrl(base: String, manifest: String): String? {
		val lines = manifest.lineSequence().map { it.trim() }.toList()
		for ((index, line) in lines.withIndex()) {
			if (!line.startsWith("#EXT-X-STREAM-INF")) continue
			val target = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
			if (target != null) return resolve(base, target)
		}
		return null
	}

	/**
	 * The segment covering [startPositionMs], found by adding up the `#EXTINF` durations - the same
	 * arithmetic the player does, so it asks the server for the same thing.
	 */
	private fun segmentAt(base: String, playlist: String, startPositionMs: Long): String? {
		val wantSeconds = startPositionMs / 1000.0
		var elapsed = 0.0
		var pending: Double? = null
		for (raw in playlist.lineSequence()) {
			val line = raw.trim()
			if (line.startsWith("#EXTINF:")) {
				pending = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull()
				continue
			}
			if (line.isEmpty() || line.startsWith("#")) continue
			val duration = pending ?: continue
			pending = null
			if (elapsed + duration > wantSeconds) return resolve(base, line)
			elapsed += duration
		}
		return null
	}

	private fun resolve(base: String, target: String): String =
		if (target.startsWith("http")) target else URI(base).resolve(target).toString()

	private fun readText(url: String): String? = open(url)?.use { connection ->
		if (connection.responseCode !in 200..299) return null
		connection.inputStream.bufferedReader().readText()
	}

	private fun readBytes(url: String, limit: Int): Int = open(url)?.use { connection ->
		connection.setRequestProperty("Range", "bytes=0-${limit - 1}")
		if (connection.responseCode !in 200..299) return 0
		val buffer = ByteArray(32 * 1024)
		var total = 0
		connection.inputStream.use { stream ->
			while (total < limit) {
				val read = stream.read(buffer)
				if (read <= 0) break
				total += read
			}
		}
		total
	} ?: 0

	private fun open(url: String): HttpURLConnection? = runCatching {
		(URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = CONNECT_TIMEOUT_MS
			readTimeout = READ_TIMEOUT_MS
			requestMethod = "GET"
		}
	}.getOrNull()

	private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
		block(this)
	} finally {
		disconnect()
	}
}
