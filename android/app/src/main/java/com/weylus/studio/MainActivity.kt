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
import com.weylus.studio.net.*
import com.weylus.studio.ui.ConnectScreen
import com.weylus.studio.ui.MirrorCanvas
import com.weylus.studio.video.MediaCodecDecoder

class MainActivity : ComponentActivity() {
    private var transport: Transport? = null
    private var session: Session? = null
    private var decoder: MediaCodecDecoder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);

        // Keep screen on for continuous drawing mirror session
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize core engine abstractions
        val transportImpl = WebSocketTransport()
        val sessionImpl = Session(transportImpl)
        val decoderImpl = MediaCodecDecoder()

        transport = transportImpl
        session = sessionImpl
        decoder = decoderImpl

        setContent {
            var sessionState by remember { mutableStateOf(SessionState.DISCONNECTED) }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            // Bind session lifecycle events
            LaunchedEffect(Unit) {
                // Keep references to updates
            }

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
                if (sessionState == SessionState.CONNECTED || sessionState == SessionState.RECONNECTING) {
                    MirrorCanvas(
                        decoder = decoderImpl,
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
                            sessionImpl.start(host, port, sessionListener)
                        },
                        onDisconnect = {
                            sessionImpl.stop()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.stop()
        decoder?.release()
    }
}
