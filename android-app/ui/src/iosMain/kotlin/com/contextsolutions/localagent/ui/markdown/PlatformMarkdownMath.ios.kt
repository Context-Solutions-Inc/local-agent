package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * iOS [PlatformMarkdownMath] (PR #41) — the mikepenz Compose-Multiplatform
 * markdown renderer (no WebView). LaTeX is **not** rendered to images on iOS this
 * milestone (JLaTeXMath is JVM-only); any math is left as literal text. Wrapped in
 * a [SelectionContainer] so LLM answers can be selected + copied, matching the
 * desktop actual.
 *
 * Body text is pinned to `bodyMedium` so a rendered answer matches the font size of
 * the plain-text (weather/finance/search) answers and the user prompt bubbles — the
 * library's default body style is `bodyLarge`, which rendered noticeably larger.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val body = MaterialTheme.typography.bodyMedium
    SelectionContainer(modifier) {
        Markdown(
            content = text,
            typography = markdownTypography(
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
            ),
        )
    }
}
