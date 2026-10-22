package com.godavin.vince

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings card for the shared hub (see HubRepository): this phone's hub ID, sync status and a Sync-now button. */
@Composable
fun HubSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hubId by remember { mutableStateOf("checking...") }
    var status by remember { mutableStateOf(HubRepository.statusLine()) }

    LaunchedEffect(Unit) {
        HubRepository.start(context)
        hubId = HubRepository.myId() ?: "not available yet (needs internet once)"
        status = HubRepository.statusLine()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Shared hub", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Lets this phone and VINCE 3.0 on your PC share where you left off and what they learn about you, so it feels like " +
                "one VINCE. Each still works fully on its own. To connect them, copy this phone's hub ID into VINCE 3.0 > " +
                "Settings > \"VINCE Mobile hub ID\" and press Save.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("This phone's hub ID", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(hubId, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(8.dp))
        ButtonGrid(
            BtnSpec("Copy hub ID") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("VINCE hub ID", hubId))
            },
            BtnSpec("Sync now") {
                HubRepository.flushNow(context)
                scope.launch {
                    delay(3000)
                    status = HubRepository.statusLine()
                }
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
    }
}
