package com.godavin.vince

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceOutput.init(this)

        // Stage 13 - ask for notification permission up front so reminders
        // can actually show when they fire, rather than silently doing
        // nothing the first time one's set. Android 13+ only - older
        // versions never required this permission at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Stage 14 fix - reminders were only ever firing while the app was
        // open/foreground. Root cause: most Android phones (especially
        // Tecno/Infinix/Itel/Xiaomi-family skins, common in Kenya) kill an
        // app's background processes aggressively unless it's explicitly
        // whitelisted from battery optimization - which silently prevents
        // the AlarmManager broadcast from ever reaching ReminderReceiver.
        // This asks, once, for that whitelist exemption.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Some OEM skins block this intent outright - nothing more
                // to do here besides the manual-settings note in Settings.
            }
        }

        setContent {
            VinceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScreen()
                }
            }
        }
    }
}

private sealed class Screen {
    object Home : Screen()
    object Settings : Screen()
    object ChatList : Screen()
    data class Chat(val threadId: String) : Screen()
}

@Composable
fun RootScreen() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val s = screen) {
        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        is Screen.ChatList -> ChatListScreen(
            onOpenThread = { id -> screen = Screen.Chat(id) },
            onBack = { screen = Screen.Home }
        )
        is Screen.Chat -> ChatScreen(threadId = s.threadId, onBack = { screen = Screen.ChatList })
        Screen.Home -> HomeScreen(
            onOpenSettings = { screen = Screen.Settings },
            onOpenChat = { screen = Screen.ChatList }
        )
    }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenChat: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyIsSet = remember { ApiKeyStore.hasAnyKey(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "GODAVINRSIDER",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "STAGE 14 - persona switching",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (keyIsSet) "At least one API key saved" else "No API keys set yet",
            color = if (keyIsSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onOpenChat) {
            Text("Chat")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var geminiText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.GEMINI)) }
    var groqText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.GROQ)) }
    var openRouterText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.OPENROUTER)) }
    var finnhubText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.FINNHUB)) }
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
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Chat tries these in order - Gemini, then Groq, then OpenRouter - " +
                "falling through automatically if one is unavailable or over quota. " +
                "Leave any of them blank to skip that provider.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text("Gemini API key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        OutlinedTextField(
            value = geminiText,
            onValueChange = { geminiText = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text("Groq API key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        OutlinedTextField(
            value = groqText,
            onValueChange = { groqText = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text("OpenRouter API key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        OutlinedTextField(
            value = openRouterText,
            onValueChange = { openRouterText = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Finnhub API key (optional - crypto/forex price backup)",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        OutlinedTextField(
            value = finnhubText,
            onValueChange = { finnhubText = it; saved = false },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row {
            Button(onClick = {
                ApiKeyStore.saveKey(context, Provider.GEMINI, geminiText)
                ApiKeyStore.saveKey(context, Provider.GROQ, groqText)
                ApiKeyStore.saveKey(context, Provider.OPENROUTER, openRouterText)
                ApiKeyStore.saveKey(context, Provider.FINNHUB, finnhubText)
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

        Spacer(modifier = Modifier.height(32.dp))
        Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.height(20.dp))

        VinceVoiceSection()
    }
}

/**
 * Stage 14 fix - VINCE voice. Automatic "find a male-labeled voice" name
 * matching doesn't work reliably (most engines, including Google's own,
 * don't label voices with the word "male" at all), so instead this lists
 * every voice this phone's TTS engine actually has, lets Vincent preview
 * each one out loud, and saves whichever one he picks for VINCE. One-time
 * setup per device, guaranteed correct instead of guessed.
 */
@Composable
fun VinceVoiceSection() {
    val voices = remember { VoiceOutput.availableVoiceNames() }
    var selected by remember { mutableStateOf(VoiceOutput.getVinceVoiceOverride()) }

    Text(
        text = "VINCE voice",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Tap a voice to hear it, then tap \"Use\" to set it as VINCE's voice. " +
            "Until you pick one, VINCE uses a lower-pitched default voice.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (voices.isEmpty()) {
        Text(
            "No voices found yet - open this screen again once the speech " +
                "engine has finished loading.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    } else {
        Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            voices.forEach { voiceName ->
                val isSelected = voiceName == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = voiceName,
                        fontSize = 13.sp,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { VoiceOutput.previewVoice(voiceName) }
                    )
                    if (isSelected) {
                        Text("In use", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = {
                            VoiceOutput.setVinceVoiceOverride(voiceName)
                            selected = voiceName
                        }) {
                            Text("Use", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
