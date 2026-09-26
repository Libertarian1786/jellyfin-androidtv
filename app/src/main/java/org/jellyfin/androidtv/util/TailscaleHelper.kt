package org.jellyfin.androidtv.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Turns Tailscale on when the server is only reachable through it. Tailscale's Android app
 * accepts a CONNECT_VPN broadcast from any app (its exported IPNReceiver, used by Tasker etc.):
 * if it is signed in and already has VPN permission it connects by itself, otherwise it posts a
 * notification asking the user to open it.
 */
object TailscaleHelper {
	const val PACKAGE = "com.tailscale.ipn"
	private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
	private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"

	/** Tailscale hands out 100.64.0.0/10 addresses. */
	fun isTailscaleAddress(addressOrHost: String?): Boolean {
		val host = addressOrHost?.let { if ("://" in it) it.toUri().host else it } ?: return false
		val parts = host.split('.').mapNotNull { it.toIntOrNull() }
		return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
	}

	@Suppress("DEPRECATION")
	fun vpnRunning(context: Context): Boolean {
		val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
		return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
	}

	fun isInstalled(context: Context): Boolean =
		runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

	/**
	 * If [serverAddress] goes through Tailscale and no VPN is up, ask Tailscale to connect and
	 * wait up to 10 seconds for it. Returns true when the VPN is (now) running.
	 */
	suspend fun ensureConnected(context: Context, serverAddress: String?): Boolean {
		if (!isTailscaleAddress(serverAddress)) return true
		if (vpnRunning(context)) return true
		if (!isInstalled(context)) return false

		Timber.i("Tailscale is off; asking it to connect")
		runCatching {
			context.sendBroadcast(Intent(ACTION_CONNECT).setComponent(ComponentName(PACKAGE, RECEIVER)))
		}.onFailure { Timber.w(it, "Tailscale connect request failed") }

		repeat(20) {
			delay(500)
			if (vpnRunning(context)) return true
		}
		return vpnRunning(context)
	}
}
