package net.kanenet.catexplorer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import android.graphics.drawable.ColorDrawable
import androidx.core.view.WindowCompat
import net.kanenet.catexplorer.ui.*
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: RigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val theme by vm.theme.collectAsState()
            val bg = theme[ThemeColor.BACKGROUND]
            SideEffect {
                // colour the bars behind the status/navigation area to match, and flip their icons for light backgrounds
                window.setBackgroundDrawable(ColorDrawable(bg.toArgb()))
                window.statusBarColor = bg.toArgb(); window.navigationBarColor = bg.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = bg.luminance() > 0.5f; isAppearanceLightNavigationBars = bg.luminance() > 0.5f
                }
            }
            CompositionLocalProvider(LocalThemeSettings provides theme) {
                MaterialTheme(colorScheme = theme.materialScheme()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val s by vm.state.collectAsState()
                        var showSettings by remember { mutableStateOf(false) }
                        if (!s.connected && s.xcvr.isEmpty()) ConnectScreen(vm, onSettings = { showSettings = true })
                        else RadioScreen(vm, onSettings = { showSettings = true })
                        if (showSettings) SettingsPanel(vm, onClose = { showSettings = false })
                    }
                }
            }
        }
    }
}
