package com.getfirepit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.getfirepit.app.ui.mock.DesignScreen
import com.getfirepit.app.ui.shell.AppShell
import com.getfirepit.app.ui.theme.FirepitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val design = if (BuildConfig.DEBUG) intent?.getStringExtra("design") else null
        setContent {
            FirepitTheme {
                if (design != null) DesignScreen(design) else AppShell()
            }
        }
    }
}
