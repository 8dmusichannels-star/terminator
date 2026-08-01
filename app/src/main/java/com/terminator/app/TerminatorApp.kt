package com.terminator.app

import android.app.Application
import com.terminator.app.session.SessionRepository
import com.terminator.app.settings.SettingsRepository

class TerminatorApp : Application() {
    lateinit var sessionRepository: SessionRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        sessionRepository = SessionRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
    }
}
