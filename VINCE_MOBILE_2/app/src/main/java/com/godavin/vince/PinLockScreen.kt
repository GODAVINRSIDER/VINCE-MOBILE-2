package com.godavin.vince

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 *
 * Added "Forgot PIN?" recovery - answering the saved security question
 * correctly lets you set a brand new PIN right here, instead of a
 * forgotten PIN being a permanent lockout.
 */
@Composable
fun PinLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    var recoveryAnswer by remember { mutableStateOf("") }
    var recoveryError by remember { mutableStateOf(false) }
    var answeredCorrectly by remember { mutableStateOf(false) }
    var resetPin by remember { mutableStateOf("") }
    var resetConfirmPin by remember { mutableStateOf("") }
    var resetMessage by remember { mutableStateOf<String?>(null) }

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

        if (!showRecovery) {
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

            if (SecurityLock.hasRecoveryQuestion(context)) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Forgot PIN?",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        showRecovery = true
                        error = false
                    }
                )
            }
        } else if (!answeredCorrectly) {
            Text(
                text = SecurityLock.getRecoveryQuestion(context) ?: "",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            OutlinedTextField(
                value = recoveryAnswer,
                onValueChange = { recoveryAnswer = it; recoveryError = false },
                label = { Text("Your answer") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = recoveryError,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
            if (recoveryError) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("That's not the answer on file.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Button(onClick = {
                    if (SecurityLock.verifyRecoveryAnswer(context, recoveryAnswer)) {
                        answeredCorrectly = true
                        recoveryAnswer = ""
                    } else {
                        recoveryError = true
                    }
                }) {
                    Text("Submit")
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = {
                    showRecovery = false
                    recoveryAnswer = ""
                    recoveryError = false
                }) {
                    Text("Back")
                }
            }
        } else {
            Text(
                text = "Set a new PIN",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            OutlinedTextField(
                value = resetPin,
                onValueChange = { if (it.length <= 8) resetPin = it },
                label = { Text("New PIN (4-8 digits)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = resetConfirmPin,
                onValueChange = { if (it.length <= 8) resetConfirmPin = it },
                label = { Text("Confirm new PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.8f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                when {
                    resetPin.length < 4 -> resetMessage = "PIN needs to be at least 4 digits."
                    resetPin != resetConfirmPin -> resetMessage = "PINs don't match."
                    else -> {
                        SecurityLock.setPin(context, resetPin)
                        onUnlocked()
                    }
                }
            }) {
                Text("Save new PIN & unlock")
            }
            resetMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
