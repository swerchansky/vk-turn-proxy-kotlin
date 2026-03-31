package com.github.swerchansky.vkturnproxy.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.ui.connect.ConnectViewModel
import com.github.swerchansky.vkturnproxy.ui.logs.LogsViewModel
import com.github.swerchansky.vkturnproxy.ui.main.MainViewModel
import com.github.swerchansky.vkturnproxy.ui.settings.SettingsViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap

@Module
abstract class ViewModelModule {

    @Binds @IntoMap @ViewModelKey(MainViewModel::class)
    abstract fun bindMainViewModel(vm: MainViewModel): ViewModel

    @Binds @IntoMap @ViewModelKey(ConnectViewModel::class)
    abstract fun bindConnectViewModel(vm: ConnectViewModel): ViewModel

    @Binds @IntoMap @ViewModelKey(LogsViewModel::class)
    abstract fun bindLogsViewModel(vm: LogsViewModel): ViewModel

    @Binds @IntoMap @ViewModelKey(SettingsViewModel::class)
    abstract fun bindSettingsViewModel(vm: SettingsViewModel): ViewModel

    @Binds
    abstract fun bindViewModelFactory(factory: ViewModelFactory): ViewModelProvider.Factory
}
