package com.jasonbible.phone

import android.app.Application
import platform.AppPlatform


/**
 * Initialises the platform bridge first thing, so the desktop-derived
 * repositories (settings, notes, Bible catalog) always have a Context.
 */
class BibleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPlatform.init(this)
    }
}
