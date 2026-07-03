package com.weylus.studio.video

import android.view.Choreographer
import java.util.concurrent.ConcurrentLinkedQueue

interface FrameScheduler {
    fun onFrameAvailable(presentationTimeUs: Long, renderAction: () -> Unit)
    fun start()
    fun stop()
}

class ChoreographerFrameScheduler : FrameScheduler {
    private val pendingFrames = ConcurrentLinkedQueue<() -> Unit>()
    private var isRunning = false
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            var lastAction: (() -> Unit)? = null
            while (!pendingFrames.isEmpty()) {
                lastAction = pendingFrames.poll()
            }
            lastAction?.invoke()

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onFrameAvailable(presentationTimeUs: Long, renderAction: () -> Unit) {
        pendingFrames.offer(renderAction)
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
