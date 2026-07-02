package com.contextsolutions.localagent.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared assistant-text renderer, gated by the persisted `renderMarkdown` flag (invariant #41).
 *
 * When [renderMarkdown] is false (deterministic weather/finance/stock cards + the
 * clock/my-list/memory handlers) the text renders as plain `bodyMedium` — markdown reflow would
 * mangle verbatim figures. When true (any freely-composed OR search-grounded LLM answer) it renders
 * markdown + LaTeX via the platform [PlatformMarkdownMath] actual, which on EVERY platform uses the
 * mikepenz Compose-MP markdown renderer + the shared [parseMathBlocks] parser; only the LaTeX
 * rasterizer differs (JLaTeXMath on desktop/JVM, jlatexmath-android on Android, SwiftMath on iOS).
 */
@Composable
fun MarkdownMath(text: String, renderMarkdown: Boolean, modifier: Modifier = Modifier) {
    if (renderMarkdown) {
        PlatformMarkdownMath(text, modifier)
    } else {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
    }
}

/**
 * Platform markdown + LaTeX renderer. The caller guarantees the markdown gate is ON; the actual
 * parses [text] via the shared [parseMathBlocks] and renders it as markdown with rendered math.
 */
@Composable
expect fun PlatformMarkdownMath(text: String, modifier: Modifier)
