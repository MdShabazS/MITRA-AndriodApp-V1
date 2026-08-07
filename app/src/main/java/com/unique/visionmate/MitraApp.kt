package com.unique.visionmate

import android.app.Application

class MitraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MitraLogRecorder.start(this)
    }
}
