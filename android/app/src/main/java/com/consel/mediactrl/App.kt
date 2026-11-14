package com.consel.mediactrl

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Remote.init(this)
    }
}
