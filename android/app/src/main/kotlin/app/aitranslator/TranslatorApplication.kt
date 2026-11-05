package app.aitranslator

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext) // Schriften und Zeichentabellen für das PDF-Lesen
    }
}
