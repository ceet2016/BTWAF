package com.bt.waf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bt.waf.ui.WafApp

@Composable
fun BTWAFTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) {
            androidx.compose.material3.darkColorScheme(primary = Color(0xFF2F6BFF))
        } else {
            androidx.compose.material3.lightColorScheme(primary = Color(0xFF2F6BFF))
        },
        content = { Surface(color = MaterialTheme.colorScheme.background, content = content) }
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WafApp()
        }
    }
}
