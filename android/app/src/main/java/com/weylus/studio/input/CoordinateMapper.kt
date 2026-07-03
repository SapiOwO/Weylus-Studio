package com.weylus.studio.input

data class NormalizedPoint(val x: Double, val y: Double)

class CoordinateMapper {
    private var serverWidth = 1920
    private var serverHeight = 1080
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080

    private var activeWidth = 1920f
    private var activeHeight = 1080f
    private var marginX = 0f
    private var marginY = 0f

    fun updateViewport(
        serverWidth: Int,
        serverHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int
    ) {
        this.serverWidth = if (serverWidth > 0) serverWidth else 1920
        this.serverHeight = if (serverHeight > 0) serverHeight else 1080
        this.surfaceWidth = if (surfaceWidth > 0) surfaceWidth else 1920
        this.surfaceHeight = if (surfaceHeight > 0) surfaceHeight else 1080

        val rs = this.serverWidth.toFloat() / this.serverHeight.toFloat()
        val rc = this.surfaceWidth.toFloat() / this.surfaceHeight.toFloat()

        if (rs > rc) {
            // Letterboxing (margins top/bottom)
            activeWidth = this.surfaceWidth.toFloat()
            activeHeight = this.surfaceWidth.toFloat() / rs
            marginX = 0f
            marginY = (this.surfaceHeight.toFloat() - activeHeight) / 2f
        } else {
            // Pillarboxing (margins left/right)
            activeHeight = this.surfaceHeight.toFloat()
            activeWidth = this.surfaceHeight.toFloat() * rs
            marginX = (this.surfaceWidth.toFloat() - activeWidth) / 2f
            marginY = 0f
        }
    }

    fun map(x: Float, y: Float): NormalizedPoint {
        val mappedX = ((x - marginX) / activeWidth).coerceIn(0f, 1f)
        val mappedY = ((y - marginY) / activeHeight).coerceIn(0f, 1f)
        return NormalizedPoint(mappedX.toDouble(), mappedY.toDouble())
    }
}
