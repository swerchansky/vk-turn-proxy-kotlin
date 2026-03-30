package com.github.swerchansky.vkturnproxy.di

import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.service.ProxyService
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.github.swerchansky.vkturnproxy.ui.onboarding.OnboardingFragment
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, NetworkModule::class, ViewModelModule::class])
interface AppComponent {
    fun inject(activity: MainActivity)
    fun inject(fragment: OnboardingFragment)
    fun inject(service: ProxyService)
    fun viewModelFactory(): ViewModelProvider.Factory
}
