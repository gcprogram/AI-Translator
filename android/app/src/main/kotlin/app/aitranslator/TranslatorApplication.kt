package app.aitranslator

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Die Anwendung ist selbst Besitzer des ViewModels: es lebt so lange wie der Prozess und nicht nur wie die Oberfläche.
 * Wird die Activity geschlossen (Zurück-Taste, aus der Übersicht gewischt), läuft eine begonnene Übersetzung weiter;
 * der Vordergrunddienst ([TranslationService]) hält den Prozess dabei am Leben.
 */
class TranslatorApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext) // Schriften und Zeichentabellen für das PDF-Lesen
    }
}
