//
//  MainActivity.kt
//  app
//
//  A0 skeleton placeholder. The iOS-matching theme, RootView and the full
//  UI port (LoginView, MainTabView, …) replace this body in A2–A4; the
//  dark background color here comes from the iOS icon palette so the
//  launch flash already looks right.
//

package dev.personal.amphora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmphoraPlaceholderTheme {
                PlaceholderScreen()
            }
        }
    }
}

private val AmphoraDeepViolet = Color(0xFF0C081A)

@Composable
private fun AmphoraPlaceholderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = AmphoraDeepViolet,
            surface = AmphoraDeepViolet,
        ),
        content = content,
    )
}

@Composable
private fun PlaceholderScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmphoraDeepViolet),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Amphora",
            color = Color(0xFFFCE294),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
