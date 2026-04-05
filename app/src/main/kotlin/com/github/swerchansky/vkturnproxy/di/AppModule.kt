package com.github.swerchansky.vkturnproxy.di

import android.app.Application
import android.content.Context
import com.github.swerchansky.vkturnproxy.data.preferences.AppPreferences
import com.github.swerchansky.vkturnproxy.logging.AndroidProxyLogger
import com.github.swerchansky.vkturnproxy.logging.ProxyLogger
import com.github.terrakok.cicerone.Cicerone
import com.github.terrakok.cicerone.NavigatorHolder
import com.github.terrakok.cicerone.Router
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class AppModule(private val app: Application) {

    @Provides @Singleton
    fun provideApplication(): Application = app

    @Provides @Singleton
    fun provideCicerone(): Cicerone<Router> = Cicerone.create()

    @Provides @Singleton
    fun provideRouter(cicerone: Cicerone<Router>): Router = cicerone.router

    @Provides @Singleton
    fun provideNavigatorHolder(cicerone: Cicerone<Router>): NavigatorHolder = cicerone.getNavigatorHolder()

    @Provides @Singleton
    fun provideContext(): Context = app.applicationContext

    @Provides @Singleton
    fun provideAppPreferences(): AppPreferences = AppPreferences(app)

    /**
     * Logcat-only logger для DI-компонентов (VkCredentialProvider и т.д.).
     * Сервисный лог с UI соединяется отдельно в ProxyService через AndroidProxyLogger.
     */
    @Provides @Singleton
    fun provideProxyLogger(): ProxyLogger = AndroidProxyLogger(onUiLog = {})
}
