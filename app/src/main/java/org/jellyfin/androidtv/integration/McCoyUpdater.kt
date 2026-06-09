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

	suspend fun checkAndUpdate(context: Context): Unit = withContext(Dispatchers.IO) {
		if (BuildConfig.MCCOY_BUILD <= 0) return@withContext
		runCatching {
			val release = httpGet(LATEST_RELEASE)?.let { JSONObject(it) } ?: return@runCatching
			val latest = Regex("\\d+").find(release.optString("tag_name"))?.value?.toIntOrNull()
				?: return@runCatching
			if (latest <= BuildConfig.MCCOY_BUILD) return@runCatching

			val assets = release.optJSONArray("assets") ?: return@runCatching
			val apkUrl = (0 until assets.length())
				.map { assets.getJSONObject(it) }
				.firstOrNull { it.optString("name").endsWith(".apk") }
				?.optString("browser_download_url") ?: return@runCatching

			Timber.i("McCoyUpdater: build %d -> %d, downloading update", BuildConfig.MCCOY_BUILD, latest)
			val apk = downloadApk(context, apkUrl) ?: return@runCatching
			withContext(Dispatchers.Main) { launchInstaller(context, apk) }
		}.onFailure { Timber.w(it, "McCoyUpdater check failed") }
		Unit
	}

	private fun httpGet(url: String): String? {
		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			connectTimeout = 10_000
			readTimeout = 10_000
			setRequestProperty("Accept", "application/vnd.github+json")
			setRequestProperty("User-Agent", "mccoy-updater")
		}
		return conn.takeIf { it.responseCode == 200 }?.inputStream?.bufferedReader()?.use { it.readText() }
	}

	private fun downloadApk(context: Context, url: String): File? {
		val conn = (URL(url).openConnection() as HttpURLConnection).apply {
			instanceFollowRedirects = true
			connectTimeout = 15_000
			readTimeout = 120_000
			setRequestProperty("User-Agent", "mccoy-updater")
		}
		if (conn.responseCode != 200) return null
		val out = File(context.cacheDir, "mccoy-update.apk")
		conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
		return out
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
