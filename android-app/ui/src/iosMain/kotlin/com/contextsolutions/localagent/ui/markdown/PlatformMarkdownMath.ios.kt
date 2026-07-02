package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.contextsolutions.localagent.platform.NativeLatexRenderer
import com.contextsolutions.localagent.ui.util.decodeImageBitmap
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import org.koin.compose.getKoin

/**
 * iOS [PlatformMarkdownMath] (PR #41; LaTeX added PR #46) — the shared mikepenz
 * Compose-Multiplatform markdown renderer (no WebView) + the common
 * [parseMathBlocks]/[InlineMathParagraph] path, with LaTeX via the [NativeLatexRenderer] Swift
 * bridge (SwiftMath). The only iOS-specific piece is the rasterizer lambda; everything else is
 * shared with desktop + Android (invariant #41).
 *
 * Never crashes: a missing bridge / unparseable formula / decode miss falls back to the raw LaTeX
 * as inline code. Wrapped in a [SelectionContainer] so answers can be selected + copied (shared
 * with desktop). Body text is pinned to `bodyMedium` (the library defaults to the larger
 * `bodyLarge`) so a rendered answer matches the plain-text answers + the user bubbles.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    val colorArgb = LocalContentColor.current.toArgb()
    val body = MaterialTheme.typography.bodyMedium
    // sp value ≈ pt; keeps rendered math sized to the surrounding body text.
    val fontSizePt = body.fontSize.value

    // Optional resolution — never crash if the bridge isn't registered (mirrors the
    // getKoin().getOrNull<JobBadge>() idiom in ChatScreen.kt).
    val koin = getKoin()
    val renderer = remember { runCatching { koin.getOrNull<NativeLatexRenderer>() }.getOrNull() }

    val mdTypography = markdownTypography(
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
    )

    val blocks = remember(text) { parseMathBlocks(text) }

    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { index, block ->
                key(index) {
                    when (block) {
                        is MdBlock.Markdown ->
                            Markdown(content = block.text, typography = mdTypography)
                        is MdBlock.InlineText ->
                            InlineMathParagraph(
                                raw = block.raw,
                                baseStyle = body,
                                colorArgb = colorArgb,
                                fontSizePt = fontSizePt,
                            ) { latex, pt, argb ->
                                renderer?.render(latex, pt, argb)?.let { img ->
                                    decodeImageBitmap(img.png)?.let { bmp ->
                                        InlineMathImage(
                                            bitmap = bmp,
                                            widthEm = (img.widthPt / pt).toFloat(),
                                            heightEm = (img.heightPt / pt).toFloat(),
                                        )
                                    }
                                }
                            }
                        is MdBlock.DisplayMath -> {
                            val img = remember(block.latex, colorArgb, renderer) {
                                renderer?.render(block.latex, fontSizePt, colorArgb)
                            }
                            val bitmap = img?.png?.let { decodeImageBitmap(it) }
                            if (img != null && bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(img.widthPt.dp, img.heightPt.dp),
                                )
                            } else {
                                // Bridge absent / unparseable formula / decode miss — show raw LaTeX.
                                Markdown(content = "`${block.latex}`", typography = mdTypography)
                            }
                        }
                    }
                }
            }
        }
    }
}
