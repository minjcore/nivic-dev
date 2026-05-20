package app.saving.wire.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary      = Color.White,
    onPrimary    = Color.Black,
    background   = Color.Black,
    surface      = Color(0xFF111111),
    onBackground = Color.White,
    onSurface    = Color.White,
    secondary    = Color(0xFF888888),
    onSecondary  = Color.Black,
)

@Composable
fun WireTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
