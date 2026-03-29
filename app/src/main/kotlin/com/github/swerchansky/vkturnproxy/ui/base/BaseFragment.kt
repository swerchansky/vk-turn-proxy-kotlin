package com.github.swerchansky.vkturnproxy.ui.base

import androidx.fragment.app.Fragment
import com.github.swerchansky.vkturnproxy.App

abstract class BaseFragment : Fragment() {
    protected val appComponent get() = (requireActivity().application as App).appComponent
}
