package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import timber.log.Timber

private val bluetoothOutputTypes = buildSet {
	add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
	add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		add(AudioDeviceInfo.TYPE_BLE_HEADSET)
		add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
	}
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
}

/** True when media sound is currently routed to Bluetooth (headphones, earbuds, a Bluetooth speaker). */
fun isBluetoothAudioOutput(context: Context): Boolean = runCatching {
	val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
	val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		val media = AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
			.build()
		audioManager.getAudioDevicesForAttributes(media).any { it.type in bluetoothOutputTypes }
	} else {
		@Suppress("DEPRECATION")
		audioManager.isBluetoothA2dpOn
	}
	if (routed) Timber.i("Sound is going to Bluetooth: asking the server for stereo")
	routed
}.getOrElse {
	Timber.w(it, "Could not read the audio route")
	false
}
