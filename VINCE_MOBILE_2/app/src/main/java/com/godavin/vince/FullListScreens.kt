package com.godavin.vince

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dashboard "See All" for the Activity panel - the FULL real activity
 * log (ActivityLog.getAll), not just the 4 most recent shown on the
 * dashboard itself.
 */
@Composable
fun ActivityLogFullScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { ActivityLog.getAll(context) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Activity", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Text(
                "Nothing logged yet - actions like sending a message, analyzing a " +
                    "photo/screen, or opening another app will show up here.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn {
                items(entries) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(entry.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            ActivityLog.relativeLabel(entry.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}

/**
 * Dashboard "See All" for the Memory panel - the FULL list of real
 * structured facts VINCE actually has saved (StructuredMemory.getFacts),
 * not a fabricated dataset.
 */
@Composable
fun MemoryFullScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val facts = remember { StructuredMemory.getFacts(context) }
    val preferredName = remember { StructuredMemory.getPreferredName(context) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Memory", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (preferredName != null) {
            Text("Preferred name: $preferredName", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (facts.isEmpty()) {
            Text(
                "No structured facts saved yet - say something like \"remember that " +
                    "my weekly target is \$150\" in any chat and it'll show up here.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn {
                items(facts) { fact ->
                    Text(
                        "• $fact",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}
