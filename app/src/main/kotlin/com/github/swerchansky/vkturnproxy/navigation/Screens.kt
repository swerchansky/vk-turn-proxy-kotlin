package com.github.swerchansky.vkturnproxy.navigation

import com.github.swerchansky.vkturnproxy.ui.connect.ConnectFragment
import com.github.swerchansky.vkturnproxy.ui.logs.LogsFragment
import com.github.swerchansky.vkturnproxy.ui.settings.SettingsFragment
import com.github.terrakok.cicerone.androidx.FragmentScreen

object Screens {
    fun connect() = FragmentScreen { ConnectFragment.newInstance() }
    fun logs() = FragmentScreen { LogsFragment.newInstance() }
    fun settings() = FragmentScreen { SettingsFragment.newInstance() }
}
