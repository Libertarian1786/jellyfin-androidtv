package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.SleepTimer
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.util.popupMenu

class SleepTimerAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_time)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		dismissPopup()
		popup = popupMenu(context, view, Gravity.END) {
			item("Off") { SleepTimer.cancel() }.apply { isChecked = !SleepTimer.isOn }
			item("After this one") { SleepTimer.stopAtEndOfItem() }.apply { isChecked = SleepTimer.endOfItem }
			for (option in SleepTimer.options) {
				item("In ${option.inWholeMinutes} minutes") { SleepTimer.stopAfter(option) }
			}
			SleepTimer.minutesLeft()?.let { left ->
				item("Stops in $left min") {}.apply { isChecked = true; isEnabled = false }
			}
		}
		popup?.menu?.setGroupCheckable(0, true, true)
		popup?.setOnDismissListener {
			videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
			popup = null
		}
		popup?.show()
	}

	fun dismissPopup() {
		popup?.dismiss()
	}
}
