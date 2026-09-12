package com.alcohol.englishstudying

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alcohol.englishstudying.ui.nav.AppNavHost
import com.alcohol.englishstudying.ui.theme.EnglishStudyingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EnglishStudyingApp).container

        setContent {
            EnglishStudyingTheme {
                AppNavHost(container = container)
            }
        }
    }
}
