package com.bt.waf

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.bt.waf.ui.WafApp
import com.bt.waf.ui.theme.BTWAFTheme

/**
 * 单 Activity 宿主。
 *
 * 显式接管系统栏：Compose 的 MaterialTheme 管不到 statusBar / navigationBar，
 * 如果不在此设置，状态栏会停留在主题默认灰色（真机上表现为顶部一条灰带）。
 * 做法是 edge-to-edge，并在主题切到深色时同步图标明暗。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用，系统栏才会变成透明并交给应用绘制
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BTWAFTheme {
                val dark = androidx.compose.foundation.isSystemInDarkTheme()
                // 跟随系统深浅色切换时刷新图标明暗
                SideEffect { applyIconMode(dark) }
                WafApp()
            }
        }
    }

    private fun applyIconMode(dark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            controller.isAppearanceLightStatusBars = !dark
        }
    }
}
