package org.linesolver.base

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import org.linesolver.base.ui.BaseScreen
import org.linesolver.base.ui.SafetyScreen
import org.linesolver.base.ui.SafetySession

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // A new root task is a fresh launch even if Android retained the old process.
        // Rotation/recreation has saved state; process death still resets the memory flag.
        if(savedInstanceState==null && isTaskRoot)SafetySession.freshLaunch()
        enableEdgeToEdge()
        setContent {
            val colors=if(isSystemInDarkTheme())darkColorScheme(primary=Color(0xFF8FCFD1),tertiary=Color(0xFFFFC58D))
                else lightColorScheme(primary=Color(0xFF155F66),tertiary=Color(0xFF89551D),surface=Color(0xFFF7F8F5),background=Color(0xFFF7F8F5))
            MaterialTheme(colorScheme=colors) {
                if(SafetySession.accepted)BaseScreen(viewModel())
                else SafetyScreen(true){SafetySession.accept()}
            }
        }
    }
}
