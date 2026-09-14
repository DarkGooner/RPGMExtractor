package com.personal.rpgmextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.personal.rpgmextractor.ui.ExtractorScreen
import com.personal.rpgmextractor.ui.theme.RpgmExtractorTheme
import com.personal.rpgmextractor.viewmodel.ExtractorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ExtractorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RpgmExtractorTheme {
                ExtractorScreen(viewModel = viewModel)
            }
        }
    }
}
