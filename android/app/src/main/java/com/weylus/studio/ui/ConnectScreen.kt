package com.weylus.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weylus.studio.net.SessionState

@Composable
fun ConnectScreen(
    sessionState: SessionState,
    errorMessage: String?,
    onConnect: (String, Int, String) -> Unit,
    onDisconnect: () -> Unit
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("1701") }
    var clientName by remember { mutableStateOf("Android Tablet") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Weylus Studio",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Android Native Client",
                    fontSize = 14.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                if (sessionState == SessionState.DISCONNECTED || sessionState == SessionState.RECOVERING) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Server Host IP") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ADB5),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ADB5),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = clientName,
                        onValueChange = { clientName = it },
                        label = { Text("Client Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00ADB5),
                            unfocusedBorderColor = Color.Gray
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                    )

                    Button(
                        onClick = {
                            val portInt = port.toIntOrNull() ?: 1701
                            onConnect(host, portInt, clientName)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00ADB5)
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularProgressIndicator(color = Color(0xFF00ADB5))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Status: $sessionState",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Red
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Disconnect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (!errorMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
