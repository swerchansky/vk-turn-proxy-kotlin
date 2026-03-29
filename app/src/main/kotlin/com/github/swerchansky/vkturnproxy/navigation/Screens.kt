package com.github.swerchansky.vkturnproxy.navigation

import com.github.swerchansky.vkturnproxy.ui.main.MainFragment
import com.github.terrakok.cicerone.androidx.FragmentScreen

object Screens {
    fun main() = FragmentScreen { MainFragment.newInstance() }
}
