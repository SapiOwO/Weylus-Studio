package com.weylus.studio

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.weylus.studio.input.DeviceCapabilityProvider
import com.weylus.studio.net.*
import com.weylus.studio.ui.ConnectScreen
import com.weylus.studio.ui.MirrorCanvas
import com.weylus.studio.video.ChoreographerFrameScheduler
import com.weylus.studio.video.MediaCodecDecoder

class MainActivity : ComponentActivity() {
    private var transport: Transport? = null
    private var session: Session? = null
    private var decoder: MediaCodecDecoder? = null
    private var scheduler: ChoreographerFrameScheduler? = null
    private var capabilityProvider: DeviceCapabilityProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val transportImpl = WebSocketTransport()
        val sessionImpl = Session(transportImpl)
        val decoderImpl = MediaCodecDecoder()
        val schedulerImpl = ChoreographerFrameScheduler()
        val provider = DeviceCapabilityProvider(this)

        transport = transportImpl
        session = sessionImpl
        decoder = decoderImpl
        scheduler = schedulerImpl
        capabilityProvider = provider

        setContent {
            var sessionState by remember { mutableStateOf(SessionState.DISCONNECTED) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            val sessionListener = object : SessionListener {
                override fun onStateChanged(state: SessionState) {
                    sessionState = state
                    if (state == SessionState.DISCONNECTED) {
                        decoderImpl.flush()
                    }
                }

                override fun onVideoConfigReceived(width: Int, height: Int) {}

                override fun onVideoFrameReceived(bytes: ByteArray) {
                    decoderImpl.feedPacket(bytes, System.nanoTime() / 1000)
                }

                override fun onError(message: String) {
                    errorMessage = message
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                if (sessionState == SessionState.STREAMING || sessionState == SessionState.RECOVERING) {
                    MirrorCanvas(
                        decoder = decoderImpl,
                        scheduler = schedulerImpl,
                        onPointerEvent = { event ->
                            sessionImpl.sendPointerEvent(event)
                        },
                        onError = { err ->
                            errorMessage = err
                            sessionImpl.stop()
                        }
                    )
                } else {
                    ConnectScreen(
                        sessionState = sessionState,
                        errorMessage = errorMessage,
                        onConnect = { host, port, clientName ->
                            errorMessage = null
                            val caps = provider.getCapabilities()
                            sessionImpl.start(host, port, caps, sessionListener)
                        },
                        onDisconnect = {
                            sessionImpl.stop()
                        }
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val activeSession = session ?: return
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        activeSession.sendDisplayChanged(width, height, rotation)
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.stop()
        decoder?.release()
    }
}
