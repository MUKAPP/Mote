package com.mukapp.mote

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.mukapp.mote.tools.BusyBoxManager

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Thread({ BusyBoxManager.initialize(this) }, "BusyBoxInit").start()
    }
}
