package com.contextsolutions.mobileagent.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Shared assistant-text renderer (docs/DESKTOP_PORT_PLAN.md, Phase 7) — the
 * cross-platform replacement for `:androidApp`'s `MarkdownMathText`, gated by the
 * persisted `renderMarkdown` flag (invariant #41).
 *
 * The gate itself is common: when [renderMarkdown] is false (deterministic
 * weather/finance cards, clock/todo handlers, and every search-grounded LLM
 * turn) the text renders as plain `bodyMedium` — markdown reflow would mangle
 * verbatim figures/citations. When true (a freely-composed answer) it renders
 * markdown + LaTeX via the platform [PlatformMarkdownMath] actual: Markwon +
 * jlatexmath on Android, a Compose-MP markdown renderer + JLaTeXMath-to-image on
 * desktop. Both normalize LaTeX delimiters through the shared [LatexNormalizer].
 *
 * Consumed by the Chat screen once it moves into `:ui` (Phase 9); for now it
 * stands beside `:androidApp`'s `MarkdownMathText`, which the live Android Chat
 * still uses.
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
 * Platform markdown + LaTeX renderer. The caller guarantees the markdown gate is
 * ON; the actual renders [text] (LaTeX normalized to `$$…$$` via
 * [LatexNormalizer]) as markdown with rendered math.
 */
@Composable
expect fun PlatformMarkdownMath(text: String, modifier: Modifier)
