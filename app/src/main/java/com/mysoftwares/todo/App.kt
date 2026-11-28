package com.mysoftwares.todo

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Res.ctx = applicationContext
        WorkDays.load(this)
        Notifications.createChannels(this)
        Appearance.init(this)
    }
}
