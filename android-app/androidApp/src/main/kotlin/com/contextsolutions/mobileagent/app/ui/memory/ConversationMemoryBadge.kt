package com.contextsolutions.mobileagent.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small numeric badge — shown in the chat top bar when this conversation
 * has produced any memories. Tap to open [ConversationMemoryListScreen]
 * scoped to the current chat.
 *
 * Hidden when [count] == 0 so the chat top bar stays clean for new
 * conversations / queries that haven't yielded memories yet.
 */
@Composable
fun ConversationMemoryBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
