package com.weylus.studio.video

import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue

interface FrameScheduler {
    /**
     * Queues a render action to be invoked on the next V-Sync pulse.
     * @param presentationTimeUs Presentation timestamp in microseconds (from decoder).
     * @param renderAction Lambda that releases the decoded output buffer to the surface.
     * @param onPresented Optional callback invoked after the frame is actually presented,
     *                    receiving the monotonic present timestamp in microseconds.
     */
    fun onFrameAvailable(
        presentationTimeUs: Long,
        renderAction: () -> Unit,
        onPresented: ((presentTimestampUs: Long) -> Unit)? = null
    )
    fun start()
    fun stop()
}

class ChoreographerFrameScheduler : FrameScheduler {
    private data class PendingFrame(
        val renderAction: () -> Unit,
        val onPresented: ((presentTimestampUs: Long) -> Unit)?
    )

    private val pendingFrames = ConcurrentLinkedQueue<PendingFrame>()
    private var isRunning = false
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            var lastFrame: PendingFrame? = null
            while (!pendingFrames.isEmpty()) {
                lastFrame = pendingFrames.poll()
            }
            if (lastFrame != null) {
                lastFrame.renderAction()
                // Record present timestamp immediately after render action completes.
                val presentTimestampUs = System.nanoTime() / 1_000L
                lastFrame.onPresented?.invoke(presentTimestampUs)
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onFrameAvailable(
        presentationTimeUs: Long,
        renderAction: () -> Unit,
        onPresented: ((presentTimestampUs: Long) -> Unit)?
    ) {
        pendingFrames.offer(PendingFrame(renderAction, onPresented))
    }

    override fun start() {
        if (isRunning) return
        isRunning = true
        Choreographer.getInstance().postFrameCallback(choreographerCallback)
    }

    override fun stop() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(choreographerCallback)
        pendingFrames.clear()
    }
}
