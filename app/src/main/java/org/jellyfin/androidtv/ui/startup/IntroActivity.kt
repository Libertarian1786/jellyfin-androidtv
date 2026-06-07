package org.jellyfin.androidtv.ui.startup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import org.jellyfin.androidtv.R

/**
 * Plays a short full-screen intro video on cold start, then hands off to
 * [StartupActivity]. Any remote key press (or touch) skips the intro.
 *
 * This is the launcher activity; because it finishes itself after handing off,
 * it only runs on a genuine cold start, not when resuming an existing task.
 */
class IntroActivity : Activity() {
	private var proceeded = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		val container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
		val videoView = VideoView(this).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			).apply { gravity = Gravity.CENTER }
		}
		container.addView(videoView)
		setContentView(container)

		val uri = Uri.parse("android.resource://$packageName/${R.raw.intro_splash}")
		videoView.setVideoURI(uri)
		videoView.setOnPreparedListener { player ->
			player.isLooping = false
			videoView.start()
		}
		videoView.setOnCompletionListener { proceed() }
		videoView.setOnErrorListener { _, _, _ ->
			proceed()
			true
		}
	}

	private fun proceed() {
		if (proceeded) return
		proceeded = true
		startActivity(Intent(this, StartupActivity::class.java))
		finish()
		@Suppress("DEPRECATION")
		overridePendingTransition(0, 0)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN) {
			proceed()
			return true
		}
		return super.dispatchKeyEvent(event)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		proceed()
		return true
	}
}
