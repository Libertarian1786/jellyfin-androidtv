package org.jellyfin.androidtv.ui.playback;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * A load control that will pre-buffer a queued item even while the playing one is still loading.
 *
 * Stock {@link DefaultLoadControl#shouldContinuePreloading} refuses whenever any period is loading,
 * and a period keeps loading while its buffer is under minBufferMs and the byte cap is unreached.
 * On a connection that is falling behind, both are permanently true, so nothing is ever pre-buffered
 * in exactly the situation where pre-buffering the replacement stream would be worth something.
 *
 * That is a policy, not a limitation, and this is the policy we want: while the player already holds
 * a comfortable buffer of what it is showing, spend the spare capacity on getting the next stream
 * ready. The gate is the caller's: {@link #setPreloadAllowed} is only turned on once the adaptive
 * controller has decided to change quality and has confirmed there is still buffer in hand, and it
 * goes off the moment the changeover happens or is abandoned. So the playing stream is never starved
 * by a speculative fetch.
 */
@UnstableApi
public final class PreloadingLoadControl extends DefaultLoadControl {
    private volatile boolean preloadAllowed = false;

    public PreloadingLoadControl(
            int minBufferMs,
            int maxBufferMs,
            int bufferForPlaybackMs,
            int bufferForPlaybackAfterRebufferMs,
            int targetBufferBytes) {
        super(
                new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs,
                targetBufferBytes,
                /* prioritizeTimeOverSizeThresholds */ false,
                DEFAULT_BACK_BUFFER_DURATION_MS,
                DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME);
    }

    /** Turned on only for the window between deciding to change quality and crossing over. */
    public void setPreloadAllowed(boolean allowed) {
        preloadAllowed = allowed;
    }

    public boolean isPreloadAllowed() {
        return preloadAllowed;
    }

    @Override
    public boolean shouldContinuePreloading(
            Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        if (preloadAllowed) return true;
        return super.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs);
    }
}
