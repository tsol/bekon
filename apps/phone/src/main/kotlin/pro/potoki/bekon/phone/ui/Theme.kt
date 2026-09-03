package pro.potoki.bekon.phone.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val Copper = Color(0xFFE8A87C)
private val CopperDim = Color(0xFFB86B45)
private val Answer = Color(0xFF3DDC97)
private val Hangup = Color(0xFFE85D4C)
private val Night = Color(0xFF121214)
private val NightLift = Color(0xFF1C1C20)
private val Cream = Color(0xFFF4F1EC)
private val CreamDim = Color(0xFFB9B4AD)

val BekonAnswer = Answer
val BekonHangup = Hangup

private val Dark = darkColorScheme(
    primary = Copper,
    onPrimary = Color(0xFF1A120C),
    primaryContainer = CopperDim,
    onPrimaryContainer = Color(0xFFFFF1E6),
    secondary = Answer,
    onSecondary = Color(0xFF042016),
    background = Night,
    surface = NightLift,
    onBackground = Cream,
    onSurface = Cream,
    onSurfaceVariant = CreamDim,
    outline = Color(0xFF3F3D42),
    error = Hangup,
    onError = Cream,
)

@Composable
fun BekonPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Dark.background,
            contentColor = Dark.onBackground,
            content = content,
        )
    }
}
