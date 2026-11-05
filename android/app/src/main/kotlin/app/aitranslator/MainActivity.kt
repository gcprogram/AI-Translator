package app.aitranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aitranslator.ui.AppTheme
import app.aitranslator.ui.MainViewModel
import app.aitranslator.ui.TranslatorApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val viewModel: MainViewModel = viewModel()
                TranslatorApp(viewModel)
            }
        }
    }
}
