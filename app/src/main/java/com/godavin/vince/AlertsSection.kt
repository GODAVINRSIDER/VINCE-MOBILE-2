package com.godavin.vince

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings card for everything proactive: session alerts, news heads-up,
 * daily briefing, journal check-in, plus the rules/plan the briefing uses.
 * Every switch re-arms the alarms immediately.
 */
@Composable
fun AlertsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var london by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_LONDON)) }
    var ny by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_NY)) }
    var asia by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_ASIA)) }
    var news by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_NEWS)) }
    var allCcy by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_NEWS_ALL_CCY)) }
    var briefing by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_BRIEFING)) }
    var journal by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_JOURNAL)) }
    var reliable by remember { mutableStateOf(AlertPrefs.isOn(context, AlertPrefs.K_RELIABLE)) }
    var briefingMin by remember { mutableStateOf(AlertPrefs.briefingMinutes(context)) }
    var journalMin by remember { mutableStateOf(AlertPrefs.journalMinutes(context)) }
    var sessionLead by remember { mutableStateOf(AlertPrefs.sessionLead(context)) }
    var newsLead by remember { mutableStateOf(AlertPrefs.newsLead(context)) }
    var rulesText by remember { mutableStateOf(TradingPlanStore.getRules(context)) }
    var planText by remember { mutableStateOf(TradingPlanStore.getPlanToday(context)) }
    var planSaved by remember { mutableStateOf(false) }
    var testNote by remember { mutableStateOf<String?>(null) }

    fun toggle(key: String, value: Boolean, forceNews: Boolean = false) {
        AlertPrefs.setOn(context, key, value)
        AlertScheduler.rescheduleAll(context, forceNews)
    }

    val exactOk = run {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    Text("Alerts and briefings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Times follow your phone's clock. Session alerts use each market's own hours, so daylight-saving " +
            "changes are handled automatically. Weekends are skipped.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(10.dp))

    AlertSwitchRow("London open", "Alert $sessionLead min before 8:00 London time", london) { london = it; toggle(AlertPrefs.K_LONDON, it) }
    AlertSwitchRow("New York open", "Alert $sessionLead min before 8:00 New York time", ny) { ny = it; toggle(AlertPrefs.K_NY, it) }
    AlertSwitchRow("Asia (Tokyo) open", "Off by default - it opens in the middle of the night here", asia) { asia = it; toggle(AlertPrefs.K_ASIA, it) }

    OutlinedButton(
        onClick = {
            val opts = listOf(5, 10, 15, 30)
            val next = opts[((opts.indexOf(sessionLead).takeIf { it >= 0 } ?: 0) + 1) % opts.size]
            sessionLead = next
            AlertPrefs.setInt(context, AlertPrefs.K_SESSION_LEAD, next)
            AlertScheduler.rescheduleAll(context)
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
    ) { Text("Session alert lead time: $sessionLead min (tap to change)", fontSize = 12.sp) }

    Spacer(modifier = Modifier.height(12.dp))
    AlertSwitchRow("High-impact news heads-up", "Alert $newsLead min before big releases (NFP, CPI, FOMC...)", news) {
        news = it; toggle(AlertPrefs.K_NEWS, it, forceNews = true)
    }
    AlertSwitchRow("News from every currency", "Default is USD, EUR, GBP and JPY only", allCcy) {
        allCcy = it; toggle(AlertPrefs.K_NEWS_ALL_CCY, it, forceNews = true)
    }
    OutlinedButton(
        onClick = {
            val opts = listOf(5, 15, 30, 60)
            val next = opts[((opts.indexOf(newsLead).takeIf { it >= 0 } ?: 0) + 1) % opts.size]
            newsLead = next
            AlertPrefs.setInt(context, AlertPrefs.K_NEWS_LEAD, next)
            AlertScheduler.rescheduleAll(context, forceNews = true)
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
    ) { Text("News heads-up lead time: $newsLead min (tap to change)", fontSize = 12.sp) }

    Spacer(modifier = Modifier.height(12.dp))
    AlertSwitchRow("Daily briefing", "Sessions, high-impact news, headlines, your rules and plan", briefing) { briefing = it; toggle(AlertPrefs.K_BRIEFING, it) }
    if (briefing) {
        TimeStepperRow("Briefing time", briefingMin) {
            briefingMin = it
            AlertPrefs.setInt(context, AlertPrefs.K_BRIEF_MIN, it)
            AlertScheduler.rescheduleAll(context)
        }
    }
    AlertSwitchRow("Journal check-in", "End-of-day prompt to log how you traded", journal) { journal = it; toggle(AlertPrefs.K_JOURNAL, it) }
    if (journal) {
        TimeStepperRow("Check-in time", journalMin) {
            journalMin = it
            AlertPrefs.setInt(context, AlertPrefs.K_JOURNAL_MIN, it)
            AlertScheduler.rescheduleAll(context)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Your rules and today's plan", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Text(
        "VINCE Mobile isn't linked to a trading account, so the briefing and journal use what you write here " +
            "(or say in chat: \"my rules: ...\", \"today's plan: ...\").",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    OutlinedTextField(
        value = rulesText,
        onValueChange = { rulesText = it; planSaved = false },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("Trading rules (one per line)") }
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = planText,
        onValueChange = { planText = it; planSaved = false },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        label = { Text("Today's plan / targets (clears at midnight)") }
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = {
        TradingPlanStore.setRules(context, rulesText)
        TradingPlanStore.setPlanToday(context, planText)
        planSaved = true
    }) { Text("Save rules and plan") }
    if (planSaved) {
        Text("Saved.", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Reliability", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    AlertSwitchRow("Reliable mode", "Uses alarm-clock alarms that skip battery batching (shows a small alarm icon)", reliable) {
        reliable = it; toggle(AlertPrefs.K_RELIABLE, it)
    }
    Text(
        if (exactOk) "Exact alarms: allowed" else "Exact alarms: NOT allowed - alerts can arrive late",
        fontSize = 12.sp,
        color = if (exactOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    )
    if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { /* settings page unavailable */ }
        }) { Text("Allow exact alarms") }
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        "On Xiaomi, Redmi, Tecno and Infinix phones: turn on Autostart for VINCE, set its battery mode to " +
            "\"No restrictions\", and lock VINCE in the recent-apps screen. Swiping VINCE away from recents can " +
            "cancel alarms on some phones; opening the app re-arms everything.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { /* nothing to open */ }
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("App settings", fontSize = 12.sp) }
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) { /* nothing to open */ }
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("Battery settings", fontSize = 12.sp) }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Test", fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            Notifs.show(context, Notifs.CH_SESSION, 7999, "VINCE test alert", "If you can see this, alerts can reach you.")
            testNote = "Test alert sent. If nothing appeared, allow notifications for VINCE in App settings."
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("Test alert", fontSize = 12.sp) }
        OutlinedButton(onClick = {
            testNote = "Building briefing..."
            scope.launch {
                val text = BriefingBuilder.buildWithin(context, 9_000, includeHeadlines = true)
                ConversationStore.ensureThread(context, BriefingBuilder.BRIEFING_THREAD, "Daily briefing")
                ConversationStore.addMessage(
                    context, BriefingBuilder.BRIEFING_THREAD,
                    ChatMessage(fromUser = false, text = text, persona = PersonaState.getActive(context).name)
                )
                Notifs.show(
                    context, Notifs.CH_BRIEFING, 7104, "Your briefing is ready", text,
                    openThreadId = BriefingBuilder.BRIEFING_THREAD
                )
                testNote = "Briefing sent - tap the notification to open it."
            }
        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("Test briefing", fontSize = 12.sp) }
    }
    testNote?.let {
        Spacer(modifier = Modifier.height(4.dp))
        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun AlertSwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (hint != null) {
                Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TimeStepperRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label: ${AlertPrefs.formatMinutes(minutes)}", modifier = Modifier.weight(1f), fontSize = 13.sp)
        OutlinedButton(
            onClick = { onChange((minutes - 30 + 1440) % 1440) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) { Text("-30m", fontSize = 12.sp) }
        Spacer(modifier = Modifier.width(6.dp))
        OutlinedButton(
            onClick = { onChange((minutes + 30) % 1440) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
        ) { Text("+30m", fontSize = 12.sp) }
    }
}
