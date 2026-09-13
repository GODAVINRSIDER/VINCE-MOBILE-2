package com.godavin.vince

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown once per app process launch when a PIN is set (SecurityLock.
 * isPinSet). Not shown per-screen, not shown again if the app is just
 * backgrounded/foregrounded within the same process - only a fresh
 * process start (force-close, reboot, task killed) asks again.
 */
@Composable
fun PinLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GODAVINRSIDER",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter PIN to continue",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 8) { pin = it; error = false } },
            label = { Text("PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            isError = error,
            modifier = Modifier.fillMaxWidth(0.7f)
        )
        if (error) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Incorrect PIN", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = {
            if (SecurityLock.verifyPin(context, pin)) {
                onUnlocked()
            } else {
                error = true
                pin = ""
            }
        }) {
            Text("Unlock")
        }
    }
}
