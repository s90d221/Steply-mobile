package com.steply.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import com.steply.app.ui.navigation.SteplyApp
import com.steply.app.ui.theme.SteplyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as SteplyApplication).container

        setContent {
            SteplyTheme {
                SteplyApp(appContainer = appContainer)
            }
        }
    }
}
