package com.dwind

import android.app.Application
import com.dwind.settings.AppSettings

class DwindApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
