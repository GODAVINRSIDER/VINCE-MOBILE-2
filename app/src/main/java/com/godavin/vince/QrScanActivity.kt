package com.godavin.vince

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.godavin.vince.ui.theme.VinceTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.9 - QR scanner. Opens Google's code-scanner screen (no camera permission needed, it
 * runs in Google Play services), then shows what the code holds with the right actions:
 * join a Wi-Fi network, open / check a link, call, text, email, add a contact, open a map.
 * Can also read a QR from a saved image or screenshot.
 *
 * Privacy: scanning and parsing stay on the phone. Wi-Fi passwords and authenticator (2FA)
 * codes are never sent to an AI. "Ask VINCE" is only offered for links and plain text.
 */
class QrScanActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, QrScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VinceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrScreen(onClose = { finish() })
                }
            }
        }
    }
}

private fun describeShort(p: QrPayload): String = when (p) {
    is QrPayload.WiFi -> "Wi-Fi network \"${p.ssid}\""
    is QrPayload.Url -> "link ${QrParser.hostOf(p.url)}"
    is QrPayload.Email -> "email address"
    is QrPayload.Phone -> "phone number"
    is QrPayload.Sms -> "text message"
    is QrPayload.Geo -> "map location"
    is QrPayload.Contact -> "contact card"
    is QrPayload.Secret -> "authenticator setup code"
    is QrPayload.Text -> "text"
}

private fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}

private fun scanImage(context: Context, uri: Uri, onFound: (String) -> Unit, onFail: (String) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        scanner.process(image)
            .addOnSuccessListener { list ->
                val raw = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (raw != null) onFound(raw) else onFail("No QR code found in that image.")
            }
            .addOnFailureListener { onFail("Couldn't read that image.") }
    } catch (e: Exception) {
        onFail("Couldn't open that image.")
    }
}

@Composable
private fun QrScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var payload by remember { mutableStateOf<QrPayload?>(null) }
    var status by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var aiText by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }

    fun onRaw(raw: String?) {
        if (raw.isNullOrBlank()) {
            status = "No QR code found."
            return
        }
        val p = QrParser.parse(raw)
        payload = p
        showPw = false
        aiText = ""
        status = ""
        try { ActivityLog.addEvent(context, "Scanned QR: " + describeShort(p)) } catch (e: Exception) { }
    }

    fun startScan() {
        status = ""
        try {
            val opts = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, opts).startScan()
                .addOnSuccessListener { b -> onRaw(b.rawValue) }
                .addOnCanceledListener { if (payload == null) status = "Scan cancelled." }
                .addOnFailureListener { e ->
                    status = "Couldn't start the scanner (${e.message ?: "unknown error"}). It needs Google Play " +
                        "services. The first time, connect to the internet so the scanner module can download, then try again."
                }
        } catch (e: Exception) {
            status = "Couldn't start the scanner: ${e.message ?: "unknown error"}"
        }
    }

    fun open(intent: Intent, failMsg: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            status = failMsg
        } catch (e: Exception) {
            status = failMsg
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scanImage(context, uri, { onRaw(it) }, { status = it })
    }

    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        status = if (res.resultCode == Activity.RESULT_OK) {
            "Saved. Your phone will join this network whenever it's in range."
        } else {
            "Not saved (cancelled, or your phone refused it)."
        }
    }

    fun connectWifi(p: QrPayload.WiFi) {
        if (Build.VERSION.SDK_INT >= 30 && p.security != "WEP") {
            try {
                val b = WifiNetworkSuggestion.Builder().setSsid(p.ssid).setIsHiddenSsid(p.hidden)
                if (p.security == "WPA") b.setWpa2Passphrase(p.password)
                val list = arrayListOf(b.build())
                val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list)
                wifiLauncher.launch(i)
                return
            } catch (e: Exception) {
                // fall through to the copy-password route
            }
        }
        if (p.password.isNotBlank()) copyToClipboard(context, "Wi-Fi password", p.password, sensitive = true)
        open(Intent(Settings.ACTION_WIFI_SETTINGS), "Couldn't open Wi-Fi settings.")
        status = "Your phone can't add this network directly, so I copied the password. Pick \"${p.ssid}\" in Wi-Fi settings and paste it."
    }

    fun askAi(p: QrPayload) {
        val prompt = when (p) {
            is QrPayload.Url -> "I scanned a QR code that contains this link: ${p.url} . Tell me what this site or page is, " +
                "who is behind it, and whether anything looks risky or like a scam. Search if you need to. " +
                "Keep it short and plain, no Markdown."
            is QrPayload.Text -> "I scanned a QR code that contains this text: \"${p.raw.take(1500)}\". Explain what it is and " +
                "what I should do with it, in a few plain sentences, no Markdown."
            else -> return
        }
        aiBusy = true
        aiText = ""
        scope.launch {
            val persona = PersonaState.getActive(context)
            aiText = try {
                withContext(Dispatchers.IO) { BrainRouter.sendMessage(context, prompt, persona, emptyList()) }
            } catch (e: Exception) {
                "Couldn't reach an AI provider just now."
            }
            aiBusy = false
        }
    }

    LaunchedEffect(Unit) { startScan() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Scan QR code", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Wi-Fi codes, links, contacts and more. Scanning stays on your phone.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (status.isNotBlank()) {
            Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
        }

        val p = payload
        if (p == null) {
            PanelCard {
                Text("Point your camera at a QR code, or pick a screenshot or photo that has one.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(12.dp))
                ButtonGrid(
                    BtnSpec("Scan with camera", primary = true) { startScan() },
                    BtnSpec("Scan from image") { picker.launch("image/*") },
                    perRow = 2
                )
            }
        } else {
            val specs = ArrayList<BtnSpec>()
            PanelCard {
                when (p) {
                    is QrPayload.WiFi -> {
                        Header("Wi-Fi network")
                        Line("Name", p.ssid)
                        Line("Security", when (p.security) {
                            "WPA" -> "WPA / WPA2 / WPA3"
                            "WEP" -> "WEP (old and weak)"
                            else -> "Open (no password)"
                        })
                        Line("Hidden network", if (p.hidden) "Yes" else "No")
                        if (p.security != "nopass") {
                            Line("Password", if (showPw) p.password.ifBlank { "(none)" } else "••••••••")
                        }
                        specs.add(BtnSpec("Connect", primary = true) { connectWifi(p) })
                        if (p.security != "nopass") {
                            specs.add(BtnSpec(if (showPw) "Hide password" else "Show password") { showPw = !showPw })
                            specs.add(BtnSpec("Copy password") {
                                copyToClipboard(context, "Wi-Fi password", p.password, sensitive = true)
                                status = "Password copied."
                            })
                        }
                    }
                    is QrPayload.Url -> {
                        Header("Link")
                        Line("Website", QrParser.hostOf(p.url).ifBlank { "unknown" })
                        Line("Full address", p.url.take(300))
                        val warnings = QrParser.linkWarnings(p.url)
                        Spacer(modifier = Modifier.height(6.dp))
                        if (warnings.isEmpty()) {
                            Text(
                                "Nothing obviously odd in the link itself. That is not a guarantee the site is safe.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        } else {
                            warnings.forEach {
                                Text("Careful: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        specs.add(BtnSpec("Open link", primary = true) {
                            open(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)), "No browser found to open it.")
                        })
                        specs.add(BtnSpec("Ask VINCE about it", enabled = !aiBusy) { askAi(p) })
                        specs.add(BtnSpec("Copy") { copyToClipboard(context, "QR link", p.url); status = "Copied." })
                        specs.add(BtnSpec("Share") {
                            open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.url), "Share link"), "Couldn't share.")
                        })
                    }
                    is QrPayload.Email -> {
                        Header("Email")
                        Line("To", p.address)
                        if (p.subject.isNotBlank()) Line("Subject", p.subject)
                        if (p.body.isNotBlank()) Line("Message", p.body.take(300))
                        specs.add(BtnSpec("Write email", primary = true) {
                            open(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${p.address}"))
                                    .putExtra(Intent.EXTRA_SUBJECT, p.subject).putExtra(Intent.EXTRA_TEXT, p.body),
                                "No email app found."
                            )
                        })
                        specs.add(BtnSpec("Copy address") { copyToClipboard(context, "Email", p.address); status = "Copied." })
                    }
                    is QrPayload.Phone -> {
                        Header("Phone number")
                        Line("Number", p.number)
                        specs.add(BtnSpec("Open dialer", primary = true) {
                            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.number}")), "Couldn't open the dialer.")
                        })
                        specs.add(BtnSpec("Copy number") { copyToClipboard(context, "Phone", p.number); status = "Copied." })
                    }
                    is QrPayload.Sms -> {
                        Header("Text message")
                        Line("To", p.number)
                        if (p.body.isNotBlank()) Line("Message", p.body.take(300))
                        specs.add(BtnSpec("Open text draft", primary = true) {
                            open(
                                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${p.number}")).putExtra("sms_body", p.body),
                                "No messaging app found."
                            )
                        })
                    }
                    is QrPayload.Geo -> {
                        Header("Map location")
                        Line("Coordinates", "${p.lat}, ${p.lng}")
                        specs.add(BtnSpec("Open in Maps", primary = true) {
                            open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}")), "No maps app found.")
                        })
                        specs.add(BtnSpec("Copy coordinates") { copyToClipboard(context, "Location", "${p.lat}, ${p.lng}"); status = "Copied." })
                    }
                    is QrPayload.Contact -> {
                        Header("Contact card")
                        if (p.name.isNotBlank()) Line("Name", p.name)
                        if (p.phone.isNotBlank()) Line("Phone", p.phone)
                        if (p.email.isNotBlank()) Line("Email", p.email)
                        if (p.org.isNotBlank()) Line("Organisation", p.org)
                        specs.add(BtnSpec("Add to contacts", primary = true) {
                            val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                                .putExtra(ContactsContract.Intents.Insert.NAME, p.name)
                                .putExtra(ContactsContract.Intents.Insert.PHONE, p.phone)
                                .putExtra(ContactsContract.Intents.Insert.EMAIL, p.email)
                                .putExtra(ContactsContract.Intents.Insert.COMPANY, p.org)
                            open(i, "Couldn't open Contacts.")
                        })
                    }
                    is QrPayload.Secret -> {
                        Header(p.label)
                        Text(
                            "This code holds a secret key for logging in to an account. VINCE does not read it out, store it or send it anywhere. " +
                                "Only scan it with your authenticator app, and never share a picture of it.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    is QrPayload.Text -> {
                        Header("Text")
                        Text(p.raw.take(1200), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        specs.add(BtnSpec("Ask VINCE about it", primary = true, enabled = !aiBusy) { askAi(p) })
                        specs.add(BtnSpec("Copy") { copyToClipboard(context, "QR text", p.raw); status = "Copied." })
                        specs.add(BtnSpec("Search Google") {
                            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(p.raw.take(200)))), "No browser found.")
                        })
                    }
                }

                if (aiBusy) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Looking into it...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (aiText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(aiText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                if (specs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    ButtonGrid(*specs.toTypedArray(), perRow = 2)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ButtonGrid(
                BtnSpec("Scan another") { payload = null; startScan() },
                BtnSpec("From image") { payload = null; picker.launch("image/*") },
                perRow = 2
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        ButtonGrid(BtnSpec("Close") { onClose() }, perRow = 1)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(end = 12.dp))
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
