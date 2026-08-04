package com.loooong.reader

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class InspectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
