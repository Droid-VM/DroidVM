// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.ui.markdown

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import com.google.android.material.color.MaterialColors
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

/**
 * The one place Markdown is turned into views. Everything else in the app is Java and Android
 * Views; this is Compose because the renderer is, and it is kept to a single entry point so that
 * the editor's preview and the read-only cards cannot drift apart -- they are the same call.
 *
 * The palette is lifted off the hosting Android theme rather than Compose's defaults, so a card
 * of rendered notes sits on the same surface colour as the card next to it instead of arriving
 * in Compose purple.
 *
 * One rule for hosts: the [ComposeView] must be measured with a bounded width. Markdown puts
 * tables and code blocks in horizontally scrollable containers, and Compose throws rather than
 * lay one of those out against an infinite width -- which is what a weighted LinearLayout child
 * is measured with on its first pass.
 */
object MarkdownRender {
    /** Renders [markdown] into [view], replacing whatever it held. */
    @JvmStatic
    fun bind(view: ComposeView, markdown: String) {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            MaterialTheme(colorScheme = schemeOf(view.context)) {
                Markdown(
                    content = markdown,
                    typography = phoneTypography(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    /**
     * A heading ladder for a phone card. The library's defaults start at displayLarge, which is
     * 57sp: right for a document rendered on its own page, absurd in a card three of which fit on
     * one screen -- an h1 would take a line and a half on its own. This tops out at headlineSmall
     * and steps down from there, so a heading still reads as a heading next to 14sp body text.
     */
    @Composable
    private fun phoneTypography(): MarkdownTypography {
        val type = MaterialTheme.typography
        val bold = FontWeight.SemiBold
        return markdownTypography(
            h1 = type.headlineSmall.copy(fontWeight = bold),
            h2 = type.titleLarge.copy(fontWeight = bold),
            h3 = type.titleMedium.copy(fontWeight = bold),
            h4 = type.titleSmall.copy(fontWeight = bold),
            h5 = type.bodyLarge.copy(fontWeight = bold),
            h6 = type.bodyMedium.copy(fontWeight = bold),
            text = type.bodyMedium,
            paragraph = type.bodyMedium,
            ordered = type.bodyMedium,
            bullet = type.bodyMedium,
            list = type.bodyMedium,
            table = type.bodySmall,
        )
    }

    /** The host theme's Material colours, as the scheme Compose draws with. */
    private fun schemeOf(context: Context): ColorScheme {
        val surface = color(context, com.google.android.material.R.attr.colorSurface, 0xFF121212)
        val onSurface = color(context, com.google.android.material.R.attr.colorOnSurface, 0xFFE6E6E6)
        val primary = color(context, androidx.appcompat.R.attr.colorPrimary, 0xFF7DA0FA)
        val onSurfaceVariant =
            color(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFB0B0B0)
        val outline = color(context, com.google.android.material.R.attr.colorOutline, 0xFF6F6F6F)
        // surfaceVariant is what code spans and blocks sit on: a shade off the card, either way.
        val surfaceVariant = surface.shiftedTowards(onSurface, 0.08f)
        val base = if (surface.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = primary,
            onPrimary = surface,
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
        )
    }

    private fun color(context: Context, attr: Int, fallback: Long): Color =
        Color(MaterialColors.getColor(context, attr, Color(fallback).toArgb()))

    /** [fraction] of the way from this colour to [other]; both are opaque. */
    private fun Color.shiftedTowards(other: Color, fraction: Float): Color = Color(
        red = red + (other.red - red) * fraction,
        green = green + (other.green - green) * fraction,
        blue = blue + (other.blue - blue) * fraction,
    )
}
