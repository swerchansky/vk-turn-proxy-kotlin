package com.github.swerchansky.vkturnproxy

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.github.swerchansky.vkturnproxy.di.AppComponent
import com.github.swerchansky.vkturnproxy.di.AppModule
import com.github.swerchansky.vkturnproxy.di.DaggerAppComponent

class App : Application() {

    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()
        appComponent = DaggerAppComponent.builder()
            .appModule(AppModule(this))
            .build()
        applyTheme()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("proxy_settings", MODE_PRIVATE)
        val mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
