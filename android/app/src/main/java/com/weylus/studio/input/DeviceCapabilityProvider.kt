package com.weylus.studio.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import com.weylus.studio.net.protocol.ClientCapabilities
import com.weylus.studio.net.protocol.DisplayCapability

class DeviceCapabilityProvider(private val context: Context) {

    fun getCapabilities(): ClientCapabilities {
        var hasPressure = false
        var hasHover = false
        var hasTilt = false

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val sources = device.sources

            if ((sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) {
                val pressureRange = device.getMotionRange(MotionEvent.AXIS_PRESSURE)
                if (pressureRange != null) {
                    hasPressure = true
                }
                val tiltRange = device.getMotionRange(MotionEvent.AXIS_TILT)
                if (tiltRange != null) {
                    hasTilt = true
                }
                hasHover = true
            }
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val refreshRate = display?.refreshRate ?: 60f

        val displayCap = DisplayCapability(
            width = width,
            height = height,
            refresh_rate = refreshRate
        )

        return ClientCapabilities(
            virtual_keyboard = true,
            uinput = true,
            hover = hasHover,
            clipboard = true,
            pressure = hasPressure,
            display = displayCap
        )
    }
}
