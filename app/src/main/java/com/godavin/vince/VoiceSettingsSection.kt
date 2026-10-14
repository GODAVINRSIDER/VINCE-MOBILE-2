package com.godavin.vince

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings card: ElevenLabs as the main voice, phone voice as the fallback. */
@Composable
fun VoiceSettingsSection() {
    val context = LocalContext.current
    var key by remember { mutableStateOf(ApiKeyStore.getKey(context, Provider.ELEVENLABS)) }
    var enabled by remember { mutableStateOf(ElevenVoice.isEnabled(context)) }
    var vince by remember { mutableStateOf(ElevenVoice.voiceId(context, Persona.VINCE)) }
    var clara by remember { mutableStateOf(ElevenVoice.voiceId(context, Persona.CLARA)) }
    var davina by remember { mutableStateOf(ElevenVoice.voiceId(context, Persona.DAVINA)) }
    var saved by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    val status = when {
        key.isBlank() -> "No ElevenLabs key saved - using the phone's voice."
        !enabled -> "ElevenLabs is switched off - using the phone's voice."
        tick >= 0 && ProviderHealth.coolingDown("ElevenLabs") ->
            "ElevenLabs problem: ${ElevenVoice.lastError.ifBlank { "key or quota?" }} - using the phone's voice for a while."
        tick >= 0 && ElevenVoice.lastError.isNotBlank() ->
            "ElevenLabs is on, but the last request failed: ${ElevenVoice.lastError}. That voice uses the phone voice until you fix its ID."
        else -> "ElevenLabs is the main voice. The phone's voice takes over if it fails."
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Voice (ElevenLabs)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "ElevenLabs gives VINCE, CLARA and DAVINA much more natural voices and starts speaking within a second or " +
                "two. If it's off, out of credit or offline, VINCE falls back to the phone's built-in voice automatically.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))

        Text("ElevenLabs API key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        OutlinedTextField(
            value = key, onValueChange = { key = it; saved = false },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Use ElevenLabs voice", modifier = Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = enabled, onCheckedChange = { enabled = it; saved = false })
        }

        Text("Voice IDs (from your ElevenLabs voice library - the same ones VINCE 3.0 uses)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(4.dp))
        Text("VINCE voice ID", fontSize = 12.sp)
        OutlinedTextField(value = vince, onValueChange = { vince = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("CLARA voice ID", fontSize = 12.sp)
        OutlinedTextField(value = clara, onValueChange = { clara = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("DAVINA voice ID", fontSize = 12.sp)
        OutlinedTextField(value = davina, onValueChange = { davina = it; saved = false }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pad = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            Button(onClick = {
                ApiKeyStore.saveKey(context, Provider.ELEVENLABS, key.trim())
                ElevenVoice.reloadKey(context)
                ElevenVoice.clearProblems()
                ElevenVoice.setEnabled(context, enabled)
                ElevenVoice.setVoiceId(context, Persona.VINCE, vince)
                ElevenVoice.setVoiceId(context, Persona.CLARA, clara)
                ElevenVoice.setVoiceId(context, Persona.DAVINA, davina)
                saved = true
                tick++
            }, contentPadding = pad) { Text("Save voice settings", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                VoiceOutput.speak("Hi, this is VINCE. How do I sound?", Persona.VINCE); tick++
            }, contentPadding = pad) { Text("Test", fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            val pad = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            OutlinedButton(onClick = { VoiceOutput.speak("Hi, this is Clara.", Persona.CLARA); tick++ }, contentPadding = pad) { Text("Test CLARA", fontSize = 12.sp) }
            OutlinedButton(onClick = { VoiceOutput.speak("Hi, this is Davina.", Persona.DAVINA); tick++ }, contentPadding = pad) { Text("Test DAVINA", fontSize = 12.sp) }
        }
        if (saved) Text("Saved. Tap Test to hear it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp))
    }
}
