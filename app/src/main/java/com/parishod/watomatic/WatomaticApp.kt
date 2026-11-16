package com.parishod.watomatic

import android.app.Application
import android.util.Log
import androidx.work.Configuration

class WatomaticApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
