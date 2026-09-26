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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    object ActivityFull : Screen()
    object MemoryFull : Screen()
    data class Chat(val threadId: String) : Screen()
    data class InCall(val mode: CallLaunchMode) : Screen()
}

@Composable
fun RootScreen() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Security/PIN layer - unlocked state lives only in this composition,
    // so it resets on every fresh process launch (force-close, reboot,
    // task kill) but not on ordinary background/foreground within the
    // same running process. Apps with no PIN ever set skip this entirely.
    var unlocked by remember { mutableStateOf(!SecurityLock.isPinSet(context)) }

    // Watches for the PC calling the phone (findRingingCallFor mirrors
    // vince_relay.py's own lookup) so an incoming call can interrupt
    // whatever screen is open, like a real phone call - but never while
    // already on the call screen itself, or before the PIN is unlocked.
    LaunchedEffect(unlocked, screen) {
        if (!unlocked || screen is Screen.InCall) return@LaunchedEffect
        while (isActive) {
            delay(4000)
            val found = try { CallRepository.findRingingCallFor("phone") } catch (e: Exception) { null }
            if (found != null) {
                val (id, data) = found
                val personaName = (data["persona"] as? String)?.uppercase() ?: Persona.VINCE.name
                screen = Screen.InCall(CallLaunchMode.Incoming(id, personaName))
                break
            }
        }
    }

    if (!unlocked) {
        PinLockScreen(onUnlocked = { unlocked = true })
        return
    }

    when (val s = screen) {
        is Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
        is Screen.ChatList -> ChatListScreen(
            onOpenThread = { id -> screen = Screen.Chat(id) },
            onBack = { screen = Screen.Home }
        )
        is Screen.Chat -> ChatScreen(threadId = s.threadId, onBack = { screen = Screen.ChatList })
        is Screen.ActivityFull -> ActivityLogFullScreen(onBack = { screen = Screen.Home })
        is Screen.MemoryFull -> MemoryFullScreen(onBack = { screen = Screen.Home })
        is Screen.InCall -> CallScreen(mode = s.mode, onBack = { screen = Screen.Home })
        Screen.Home -> DashboardScreen(
            onOpenSettings = { screen = Screen.Settings },
            onOpenChat = { screen = Screen.ChatList },
            onOpenNewChat = { screen = Screen.Chat(java.util.UUID.randomUUID().toString()) },
            onOpenActivityFull = { screen = Screen.ActivityFull },
            onOpenMemoryFull = { screen = Screen.MemoryFull },
            onOpenCall = { screen = Screen.InCall(CallLaunchMode.Outgoing) }
        )
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var geminiText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.GEMINI)) }
    var groqText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.GROQ)) }
    var openRouterText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.OPENROUTER)) }
    var finnhubText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.FINNHUB)) }
    var tavilyText by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.TAVILY)) }
    var saved by remember { mutableStateOf(false) }

    // Fix - this screen grew to 4 stacked sections (API keys, System
    // checks, Security, Backup) without ever adding scroll support, so
    // anything past the bottom of the viewport was simply clipped -
    // which is what made the API key fields look empty and produced the
    // stray line through "Battery optimization" (a border getting cut
    // off exactly at the screen edge). verticalScroll fixes all of it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        PanelCard {
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
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Tavily API key (optional - real-time web search, free tier available at tavily.com)",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            OutlinedTextField(
                value = tavilyText,
                onValueChange = { tavilyText = it; saved = false },
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
                    ApiKeyStore.saveKey(context, Provider.TAVILY, tavilyText)
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

        Spacer(modifier = Modifier.height(20.dp))

        PanelCard { SystemChecksSection() }

        Spacer(modifier = Modifier.height(20.dp))

        PanelCard { SecuritySection() }

        Spacer(modifier = Modifier.height(20.dp))

        PanelCard { BackupSection() }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Full backup/restore. "Backup to Google Drive" tries to open Drive
 * directly with the file ready to upload; if Drive isn't installed, it
 * falls back to the normal share sheet so it still works either way.
 * "Restore from Google Drive" opens Android's own file picker, which
 * already lists Drive (and any other cloud provider you've got) as a
 * browsable source alongside on-device storage - no separate Drive-
 * specific code needed there, that's just how the system picker works.
 * See BackupManager.kt for why this file-based shape was chosen over a
 * built-in email-account integration.
 */
@Composable
fun BackupSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            message = if (BackupManager.restoreFromUri(context, uri)) {
                "Restored - your chats, memory, and activity from that backup are now on this device."
            } else {
                "Couldn't read that file as a VINCE backup."
            }
        }
    }

    Text(
        text = "Backup & restore",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Export everything VINCE has stored - every chat thread, every " +
            "structured memory fact, your activity log - into one file you can keep " +
            "in Google Drive. Restore that same file on a new phone or after " +
            "reinstalling to pick up right where you left off.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(12.dp))

    Row {
        Button(onClick = {
            val intent = BackupManager.shareBackupIntent(context)
            // Try Google Drive directly first - falls back to the
            // regular share sheet if Drive isn't installed, so this
            // never dead-ends.
            val driveIntent = Intent(intent).setPackage("com.google.android.apps.docs")
            try {
                context.startActivity(driveIntent)
            } catch (e: Exception) {
                context.startActivity(Intent.createChooser(intent, "Share VINCE backup"))
            }
        }) {
            Text("Backup to Google Drive")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row {
        OutlinedButton(onClick = {
            restoreLauncher.launch(arrayOf("application/json"))
        }) {
            Text("Restore from Google Drive")
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "The restore picker lets you browse Drive directly, same as any " +
            "other file source on your phone.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    )

    message?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Security/PIN layer - optional. No PIN set = no lock screen ever,
 * exactly like before. Set one and it's required once per fresh app
 * launch (see RootScreen). Stored as a SHA-256 hash only (SecurityLock.kt).
 *
 * Fix - removing the PIN used to be a single tap with no verification at
 * all, meaning anyone holding an already-unlocked phone could just turn
 * the lock off. Now requires re-entering the current PIN first. Also
 * added an optional recovery question (own hashed answer, same as the
 * PIN itself) so a forgotten PIN isn't a permanent lockout.
 */
@Composable
fun SecuritySection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pinSet by remember { mutableStateOf(SecurityLock.isPinSet(context)) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var recoveryQuestion by remember { mutableStateOf("") }
    var recoveryAnswer by remember { mutableStateOf("") }
    var removePinEntry by remember { mutableStateOf("") }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Text(
        text = "App lock (PIN)",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (pinSet)
            "A PIN is set - required once each time the app is freshly launched."
        else
            "No PIN set - the app opens straight in, same as before. Set one below if you want it locked (worth it now that VINCE can open other apps and read your screen).",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (pinSet) {
        if (!showRemoveConfirm) {
            Button(onClick = { showRemoveConfirm = true }) {
                Text("Remove PIN")
            }
        } else {
            Text(
                "Enter your current PIN to confirm removal:",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = removePinEntry,
                onValueChange = { if (it.length <= 8) removePinEntry = it },
                label = { Text("Current PIN") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (SecurityLock.verifyPin(context, removePinEntry)) {
                        SecurityLock.clearPin(context)
                        pinSet = false
                        showRemoveConfirm = false
                        removePinEntry = ""
                        message = "PIN removed."
                    } else {
                        message = "That PIN is incorrect - PIN not removed."
                    }
                }) {
                    Text("Confirm removal")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    showRemoveConfirm = false
                    removePinEntry = ""
                }) {
                    Text("Cancel")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (SecurityLock.hasRecoveryQuestion(context))
                "Recovery question is set - you can reset a forgotten PIN with it."
            else
                "No recovery question set - if you forget your PIN, there's currently no way back in except reinstalling. Set one below.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = recoveryQuestion,
            onValueChange = { recoveryQuestion = it },
            label = { Text("Security question") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = recoveryAnswer,
            onValueChange = { recoveryAnswer = it },
            label = { Text("Answer") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (recoveryQuestion.isBlank() || recoveryAnswer.isBlank()) {
                message = "Fill in both the question and the answer."
            } else {
                SecurityLock.setRecoveryQuestion(context, recoveryQuestion, recoveryAnswer)
                recoveryQuestion = ""
                recoveryAnswer = ""
                message = "Recovery question saved."
            }
        }) {
            Text("Save recovery question")
        }
    } else {
        OutlinedTextField(
            value = newPin,
            onValueChange = { if (it.length <= 8) newPin = it },
            label = { Text("New PIN (4-8 digits)") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 8) confirmPin = it },
            label = { Text("Confirm PIN") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            when {
                newPin.length < 4 -> message = "PIN needs to be at least 4 digits."
                newPin != confirmPin -> message = "PINs don't match."
                else -> {
                    SecurityLock.setPin(context, newPin)
                    pinSet = true
                    newPin = ""
                    confirmPin = ""
                    message = "PIN set. Scroll down to also set a recovery question, in case you forget it."
                }
            }
        }) {
            Text("Set PIN")
        }
    }

    message?.let {
        Spacer(modifier = Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Quick diagnostic shortcuts for the two issues that code alone can't
 * fully fix - both are Android/OEM settings screens, not app bugs:
 *
 * - If the voice picker list above is empty or only shows one voice,
 *   this phone's current TTS engine likely doesn't expose multiple
 *   voices at all (common on some OEM default engines). Switching the
 *   system's default TTS engine to Google's (installable free from the
 *   Play Store if not already present) reliably fixes this.
 * - If reminders still only fire while the app is open even after
 *   allowing "ignore battery optimization", this phone's brand likely
 *   has its own separate "Autostart"/"Background activity" toggle
 *   outside Android's own settings, which has no public API to trigger
 *   automatically - the app-details screen is the closest common jump
 *   point to find it from.
 */
@Composable
fun SystemChecksSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExempted = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    Text(
        text = "System checks",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(10.dp))

    Text(
        text = "Battery optimization: " + if (batteryExempted) "exempted (good)" else "NOT exempted",
        fontSize = 13.sp,
        color = if (batteryExempted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(6.dp))
    if (!batteryExempted) {
        OutlinedButton(onClick = {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) { }
        }) {
            Text("Allow VINCE to ignore battery optimization")
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
    Text(
        text = "If reminders still don't fire with the app closed after allowing " +
            "this, your phone's brand likely has its own separate Autostart / " +
            "background-activity toggle. Tap below to open VINCE's app-details " +
            "screen and look for it there (exact name varies by phone).",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedButton(onClick = {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) { }
    }) {
        Text("Open VINCE app settings")
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = "If the VINCE voice list above is empty or only shows one entry, " +
            "this phone's current speech engine likely doesn't support multiple " +
            "voices. Try switching the system Text-to-Speech engine to Google's " +
            "(Settings > Accessibility or Settings > System > Languages > " +
            "Text-to-speech output) and come back to this screen.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedButton(onClick = {
        try {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e2: Exception) { }
        }
    }) {
        Text("Open Text-to-Speech settings")
    }
}

