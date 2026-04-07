package com.github.swerchansky.vkturnproxy.di

import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.service.ProxyService
import com.github.swerchansky.vkturnproxy.ui.connect.ConnectFragment
import com.github.swerchansky.vkturnproxy.ui.detail.ConnectionDetailSheet
import com.github.swerchansky.vkturnproxy.ui.logs.LogsFragment
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.github.swerchansky.vkturnproxy.ui.settings.SettingsFragment
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, NetworkModule::class, ViewModelModule::class])
interface AppComponent {
    fun inject(activity: MainActivity)
    fun inject(fragment: ConnectFragment)
    fun inject(sheet: ConnectionDetailSheet)
    fun inject(fragment: LogsFragment)
    fun inject(fragment: SettingsFragment)
    fun inject(service: ProxyService)
    fun viewModelFactory(): ViewModelProvider.Factory
}
