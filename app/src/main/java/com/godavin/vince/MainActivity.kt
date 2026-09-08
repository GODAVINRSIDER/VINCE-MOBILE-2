package com.godavin.vince

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godavin.vince.ui.theme.VinceTheme

/**
 * Stage 1 shell: proves the fresh project builds green end to end, and gives
 * VINCE Mobile its own securely-stored Gemini API key. No PC dependency
 * anywhere in this file - that's the whole point of the pivot.
 *
 * Real chat/voice/vision wiring lands in later stages, one verified slice
 * at a time, same discipline as before.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VinceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScreen()
                }
            }
        }
    }
}

@Composable
fun RootScreen() {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
    } else {
        HomeScreen(onOpenSettings = { showSettings = true })
    }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyIsSet = remember { ApiKeyStore.hasKey(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "VINCE 2.0",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "STAGE 1 - fresh build check",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (keyIsSet) "Gemini API key: saved" else "Gemini API key: not set yet",
            color = if (keyIsSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var keyText by remember { mutableStateOf(ApiKeyStore.getKey(context)) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "VINCE settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text("Gemini API key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        OutlinedTextField(
            value = keyText,
            onValueChange = {
                keyText = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row {
            Button(onClick = {
                ApiKeyStore.saveKey(context, keyText)
                saved = true
            }) {
                Text("Save")
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }

        if (saved) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Saved.", color = MaterialTheme.colorScheme.primary)
        }
    }
}
