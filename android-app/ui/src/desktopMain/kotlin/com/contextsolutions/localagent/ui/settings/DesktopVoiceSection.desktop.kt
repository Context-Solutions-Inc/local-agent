package com.contextsolutions.localagent.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.voice.ChatSpeaker
import com.contextsolutions.localagent.voice.DesktopTtsPreferences
import com.contextsolutions.localagent.voice.DesktopTtsVoices
import com.contextsolutions.localagent.voice.DesktopVoice
import com.contextsolutions.localagent.voice.DesktopVoiceConfig
import com.contextsolutions.localagent.voice.DesktopVoiceEngine
import com.contextsolutions.localagent.voice.PiperSpeechSynthesizer
import com.contextsolutions.localagent.voice.PiperState
import com.contextsolutions.localagent.voice.PiperVoices
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Desktop read-aloud voice picker (PR #66). The top engine choice is the bundled **Piper**
 * neural engine (downloaded on first use, like the LLM/Vosk models, played in-JVM); the rest
 * are the OS speech-dispatcher output modules. Voice + speech-rate + a "Test voice" button
 * round it out. Writes through the shared `DesktopTtsPreferences`, so `DesktopTtsSpeaker`
 * picks the new selection on its next utterance.
 */
@Composable
actual fun DesktopVoiceSection() {
    val prefs = koinInject<DesktopTtsPreferences>()
    val enumerator = koinInject<DesktopTtsVoices>()
    val speaker = koinInject<ChatSpeaker>()
    val piper = koinInject<PiperSpeechSynthesizer>()

    val config by prefs.voiceConfigFlow().let { flow ->
        produceState(initialValue = prefs.voiceConfig(), flow) { flow.collect { value = it } }
    }
    val engines by produceState(initialValue = emptyList<DesktopVoiceEngine>(), enumerator) {
        value = withContext(Dispatchers.IO) { enumerator.engines() }
    }
    val voices by produceState(initialValue = emptyList<DesktopVoice>(), enumerator) {
        value = withContext(Dispatchers.IO) { enumerator.voices() }
    }

    val piperSelected = config.engine == DesktopVoiceConfig.PIPER_ENGINE

    HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))
    SectionHeaderText("Read-aloud voice")
    Text(
        "Pick the voice used when read-aloud is on. Piper is a high-quality neural voice " +
            "downloaded the first time you use it (~90 MB) and runs fully offline. The other " +
            "options come from your system's speech engine.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    // Engine: Piper (if a prebuilt exists for this OS) first, then "System default", then
    // the OS speech-dispatcher output modules.
    val engineOptions: List<DesktopVoiceEngine?> = buildList {
        if (piper.isAvailable()) {
            add(DesktopVoiceEngine(DesktopVoiceConfig.PIPER_ENGINE, "Piper — neural (recommended)"))
        }
        add(null) // System default
        addAll(engines)
    }
    LabeledDropdown(
        label = "Engine",
        selectedLabel = when {
            piperSelected -> "Piper — neural (recommended)"
            config.engine.isBlank() -> "System default"
            else -> engines.firstOrNull { it.id == config.engine }?.label ?: config.engine
        },
        options = engineOptions,
        optionLabel = { it?.label ?: "System default" },
        onSelect = { engine ->
            val newEngine = engine?.id.orEmpty()
            val newVoice = if (newEngine == DesktopVoiceConfig.PIPER_ENGINE) PiperVoices.DEFAULT.id else ""
            prefs.setVoiceConfig(config.copy(engine = newEngine, voice = newVoice))
            if (newEngine == DesktopVoiceConfig.PIPER_ENGINE) piper.prepare(newVoice)
        },
    )
    Spacer(Modifier.height(12.dp))

    if (piperSelected) {
        PiperVoiceControls(
            selectedId = config.voice,
            onSelect = { prefs.setVoiceConfig(config.copy(voice = it)); piper.prepare(it) },
            state = piper.state.collectAsState().value,
        )
    } else {
        VoiceDropdown(
            voices = voices,
            selectedId = config.voice,
            onSelect = { prefs.setVoiceConfig(config.copy(voice = it)) },
        )
    }

    Spacer(Modifier.height(16.dp))
    Text("Speech rate — ${rateLabel(config.rate)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = config.rate.toFloat(),
        onValueChange = { prefs.setVoiceConfig(config.copy(rate = it.roundToInt())) },
        valueRange = DesktopVoiceConfig.RATE_MIN.toFloat()..DesktopVoiceConfig.RATE_MAX.toFloat(),
        steps = 19,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { speaker.speak("This is the read-aloud voice.") }) {
        Text("Test voice")
    }
}

@Composable
private fun PiperVoiceControls(
    selectedId: String,
    onSelect: (String) -> Unit,
    state: PiperState,
) {
    LabeledDropdown(
        label = "Voice",
        selectedLabel = PiperVoices.byId(selectedId).label,
        options = PiperVoices.ALL,
        optionLabel = { it.label },
        onSelect = { onSelect(it.id) },
    )
    Spacer(Modifier.height(8.dp))
    when (state) {
        is PiperState.Downloading -> {
            Text(
                "Downloading neural voice… ${(state.fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        }
        is PiperState.Ready -> Text(
            "Neural voice ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is PiperState.Failed -> Text(
            "${state.message} It will retry on the next read-aloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is PiperState.Unavailable -> Text(
            "Not available on this platform.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        is PiperState.Idle -> Text(
            "Downloads ~90 MB the first time it speaks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun rateLabel(rate: Int): String = when {
    rate == 0 -> "normal"
    rate < 0 -> "slower ($rate)"
    else -> "faster (+$rate)"
}

@Composable
private fun SectionHeaderText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun <T> LabeledDropdown(
    label: String,
    selectedLabel: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        AssistChip(onClick = { open = true }, label = { Text(selectedLabel) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); open = false },
                )
            }
        }
    }
}

@Composable
private fun VoiceDropdown(
    voices: List<DesktopVoice>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selectedLabel = voices.firstOrNull { it.id == selectedId }?.label
        ?: selectedId.ifBlank { "System default" }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Voice: ", style = MaterialTheme.typography.bodyMedium)
        AssistChip(onClick = { open = true }, label = { Text(selectedLabel) })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            if (voices.size > FILTER_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            DropdownMenuItem(text = { Text("System default") }, onClick = { onSelect(""); open = false })
            val filtered = voices
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                .take(MAX_VISIBLE)
            filtered.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.label) }, onClick = { onSelect(voice.id); open = false })
            }
            if (voices.size > filtered.size) {
                Text(
                    "Showing ${filtered.size} of ${voices.size} — type to filter.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val FILTER_THRESHOLD = 25
private const val MAX_VISIBLE = 80
