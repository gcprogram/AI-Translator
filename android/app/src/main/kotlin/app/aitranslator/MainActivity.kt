package app.aitranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aitranslator.ui.AppTheme
import app.aitranslator.ui.MainViewModel
import app.aitranslator.ui.TranslatorApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TranslatorApplication
        setContent {
            AppTheme {
                // ViewModel der Anwendung statt der Activity: die Übersetzung überlebt das Schließen der Oberfläche
                val viewModel: MainViewModel = viewModel(
                    viewModelStoreOwner = app,
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app),
                )
                TranslatorApp(viewModel)
            }
        }
    }
}
