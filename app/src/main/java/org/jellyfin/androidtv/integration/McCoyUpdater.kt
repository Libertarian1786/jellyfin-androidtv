package org.jellyfin.androidtv.integration

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal self-updater. On launch it checks the public GitHub fork's latest release for
 * a build newer than this one and, if found, downloads the APK and launches the system
 * installer — so every device picks up new builds without per-device ADB.
 *
 * Builds are tagged "build-<n>" in CI (BuildConfig.MCCOY_BUILD). Local builds have
 * MCCOY_BUILD == 0 and never auto-update. All builds share one signing key, so the
 * update installs in place (login preserved). The first prompt per device asks the user
 * to allow installs from this app; subsequent updates are a single "install" confirm.
 */
object McCoyUpdater {
	private const val LATEST_RELEASE =
		"https://api.github.com/repos/Libertarian1786/jellyfin-androidtv/releases/latest"
	private const val APK_NAME = "mccoy-update.apk"
	private const val PREFS = "mccoy_updater"
	private const val KEY_DOWNLOADED_BUILD = "downloaded_build"

	suspend fun checkAndUpdate(context: Context): Unit = withContext(Dispatchers.IO) {
		if (BuildConfig.MCCOY_BUILD <= 0) return@withContext
		runCatching {
			val release = httpGet(LATEST_RELEASE)?.let { JSONObject(it) } ?: return@runCatching
			val latest = Regex("\\d+").find(release.optString("tag_name"))?.value?.toIntOrNull()
				?: return@runCatching
			// Already current — an earlier update installed, so drop its cached APK.
			if (latest <= BuildConfig.MCCOY_BUILD) return@runCatching discardDownload(context)

			val assets = release.optJSONArray("assets") ?: return@runCatching
			val asset = (0 until assets.length())
				.map { assets.getJSONObject(it) }
				.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching
			val apkUrl = asset.optString("browser_download_url").ifEmpty { return@runCatching }
			val expectedSize = asset.optLong("size", -1L)

			// Reuse an APK already fetched for this build. Without this the full ~25MB is
			// re-downloaded on every cold start for as long as the install is declined,
			// never granted "install unknown apps", or otherwise doesn't complete.
			val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			val cached = File(context.cacheDir, APK_NAME)
			val reusable = prefs.getInt(KEY_DOWNLOADED_BUILD, 0) == latest &&
				cached.isFile &&
				(expectedSize <= 0L || cached.length() == expectedSize)

			val apk = if (reusable) {
				Timber.i("McCoyUpdater: build %d available, reusing cached APK", latest)
				cached
			} else {
				Timber.i("McCoyUpdater: build %d -> %d, downloading update", BuildConfig.MCCOY_BUILD, latest)
				val downloaded = downloadApk(context, apkUrl, expectedSize) ?: return@runCatching
				prefs.edit().putInt(KEY_DOWNLOADED_BUILD, latest).apply()
				downloaded
			}
			withContext(Dispatchers.Main) { launchInstaller(context, apk) }
		}.onFailure { Timber.w(it, "McCoyUpdater check failed") }
		Unit
	}

	/** Remove a cached update APK (and its marker) once it's no longer needed. */
	private fun discardDownload(context: Context) {
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (prefs.contains(KEY_DOWNLOADED_BUILD)) prefs.edit().remove(KEY_DOWNLOADED_BUILD).apply()
		File(context.cacheDir, APK_NAME).delete()
	}

	private fun httpGet(url: String): String? {
		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 10_000
			readTimeout = 10_000
			setRequestProperty("Accept", "application/vnd.github+json")
			setRequestProperty("User-Agent", "mccoy-updater")
		}
		return try {
			if (conn.responseCode != 200) null
			else conn.inputStream.bufferedReader().use { it.readText() }
		} finally {
			conn.disconnect()
		}
	}

	private fun downloadApk(context: Context, url: String, expectedSize: Long): File? {
		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			instanceFollowRedirects = true
			connectTimeout = 15_000
			readTimeout = 120_000
			setRequestProperty("User-Agent", "mccoy-updater")
		}
		return try {
			if (conn.responseCode != 200) return null
			val out = File(context.cacheDir, APK_NAME)
			conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
			// A truncated download would hand the installer a corrupt APK, and caching it
			// would keep re-offering that same corrupt file.
			if (expectedSize > 0L && out.length() != expectedSize) {
				Timber.w("McCoyUpdater: size mismatch (%d != %d), discarding", out.length(), expectedSize)
				out.delete()
				null
			} else {
				out
			}
		} finally {
			conn.disconnect()
		}
	}

	private fun launchInstaller(context: Context, apk: File) {
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		runCatching { context.startActivity(intent) }
			.onFailure { Timber.w(it, "McCoyUpdater install intent failed") }
	}
}
