package org.jellyfin.androidtv.ui.browsing

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * "Can't reach your server" check. On a Chromecast the usual cause is Tailscale not running after
 * a restart (the server address is a Tailscale 100.64-127.x.x address), so say that and offer to
 * open Tailscale; otherwise the PC is asleep or Jellyfin is closed.
 */
object ServerReachability {
	private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
	private const val MIN_INTERVAL_MS = 60_000L

	private var lastCheck = 0L
	private var dialog: AlertDialog? = null

	private fun isTailscaleAddress(host: String): Boolean {
		val parts = host.split('.').mapNotNull { it.toIntOrNull() }
		return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
	}

	@Suppress("DEPRECATION")
	private fun vpnRunning(context: Context): Boolean {
		val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
		return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
	}

	private suspend fun reachable(api: ApiClient): Boolean = withTimeoutOrNull(8.seconds) {
		withContext(Dispatchers.IO) { runCatching { api.systemApi.getPublicSystemInfo(); true }.getOrDefault(false) }
	} ?: false

	fun check(activity: FragmentActivity, api: ApiClient, onRecovered: () -> Unit, force: Boolean = false) {
		val now = android.os.SystemClock.uptimeMillis()
		if (!force && now - lastCheck < MIN_INTERVAL_MS) return
		if (dialog?.isShowing == true) return
		lastCheck = now

		activity.lifecycleScope.launch {
			if (reachable(api)) return@launch
			val host = api.baseUrl?.toUri()?.host.orEmpty()
			val tailscaleOff = isTailscaleAddress(host) && !vpnRunning(activity)
			Timber.w("Server $host unreachable (tailscaleOff=$tailscaleOff)")
			if (activity.isFinishing || activity.isDestroyed) return@launch

			val builder = AlertDialog.Builder(activity)
				.setTitle("Can't reach your server")
				.setNegativeButton("Try again") { _, _ ->
					activity.lifecycleScope.launch {
						if (reachable(api)) onRecovered() else check(activity, api, onRecovered, force = true)
					}
				}
			if (tailscaleOff) {
				val launch = activity.packageManager.getLeanbackLaunchIntentForPackage(TAILSCALE_PACKAGE)
					?: activity.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
				builder.setMessage("Tailscale is off on this TV, and the server is only reachable through it. Open Tailscale, turn it on, then come back.")
				if (launch != null) builder.setPositiveButton("Open Tailscale") { _, _ -> activity.startActivity(launch) }
			} else {
				builder.setMessage("The PC may be asleep or Jellyfin may be closed. Check the PC, then try again.")
			}
			dialog = builder.create().apply {
				setOnShowListener { getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
				show()
			}
		}
	}
}
