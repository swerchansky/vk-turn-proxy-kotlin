package com.github.swerchansky.vkturnproxy

import android.app.Application
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
    }
}
