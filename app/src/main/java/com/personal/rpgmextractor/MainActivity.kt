package com.personal.rpgmextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.personal.rpgmextractor.ui.ExtractorScreen
import com.personal.rpgmextractor.ui.theme.RpgmExtractorTheme
import com.personal.rpgmextractor.viewmodel.ExtractorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ExtractorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge consistently across gesture nav and classic
        // 3-button nav; combined with explicit insets padding in
        // ExtractorScreen this keeps content/buttons from being hidden
        // behind the system navigation bar on 3-button devices.
        enableEdgeToEdge()
        setContent {
            RpgmExtractorTheme {
                ExtractorScreen(viewModel = viewModel)
            }
        }
    }
}
