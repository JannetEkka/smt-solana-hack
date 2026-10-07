package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// SMT World's palette (smt_world/style.css): navy, gold, ink.
val Navy = Color(0xFF081A34)
val NavyRaised = Color(0xFF0F2747)
val NavyLine = Color(0xFF1F3B63)
val Gold = Color(0xFFD4AF37)
val GoldSoft = Color(0xFFF3D98B)
val Ink = Color(0xFFE8EEFB)
val Muted = Color(0xFF9FB4CF)
val Up = Color(0xFF3DDC97)
val Down = Color(0xFFFF6B6B)

private val Scheme = darkColorScheme(
    primary = Gold, onPrimary = Navy,
    secondary = GoldSoft, onSecondary = Navy,
    background = Navy, onBackground = Ink,
    surface = NavyRaised, onSurface = Ink,
    surfaceVariant = NavyRaised, onSurfaceVariant = Muted,
    surfaceContainer = NavyRaised,
    outline = NavyLine,
    error = Down,
)

@Composable
fun SmtTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/** A visible gold ring on the focused element, so a TV remote's arrow keys show where you are. */
fun Modifier.focusRing(radius: Int = 12): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this.onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, GoldSoft, RoundedCornerShape(radius.dp)) else Modifier)
}
