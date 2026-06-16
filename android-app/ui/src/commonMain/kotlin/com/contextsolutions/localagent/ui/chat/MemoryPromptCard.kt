package com.contextsolutions.localagent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.memory.MemoryCategory

/**
 * Inline Save / Dismiss card for middle-band memory candidates.
 * Renders below the assistant bubble that produced it. Save persists
 * the proposed memory; Dismiss drops it (a counter bump tracks the
 * decision). A new turn that produces a fresh candidate auto-dismisses
 * any unanswered card.
 */
@Composable
fun MemoryPromptCard(
    text: String,
    category: MemoryCategory,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Save this as a ${category.label()}?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            // Anchor buttons to the card's own content color — dynamic-dark
            // can pick a `primary` that nearly matches `tertiaryContainer`,
            // making the default OutlinedButton's "Dismiss" text invisible.
            val accent = MaterialTheme.colorScheme.onTertiaryContainer
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSave,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = accent,
                        contentColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, accent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                ) { Text("Dismiss") }
            }
        }
    }
}

private fun MemoryCategory.label(): String = when (this) {
    MemoryCategory.PERSONAL_IDENTITY -> "personal detail"
    MemoryCategory.PREFERENCE -> "preference"
    MemoryCategory.PROFESSIONAL -> "professional detail"
    MemoryCategory.INTEREST -> "interest"
    MemoryCategory.RELATIONSHIP -> "relationship"
    MemoryCategory.TEMPORARY_CONTEXT -> "temporary note"
}
