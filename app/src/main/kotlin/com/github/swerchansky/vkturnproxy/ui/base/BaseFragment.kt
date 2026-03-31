package com.github.swerchansky.vkturnproxy.ui.base

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.di.AppComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

abstract class BaseFragment : Fragment() {

    protected val appComponent: AppComponent
        get() = (requireActivity().application as App).appComponent

    protected fun <T> Flow<T>.collectWithLifecycle(
        minState: Lifecycle.State = Lifecycle.State.STARTED,
        action: suspend (T) -> Unit,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(minState) {
                collect(action)
            }
        }
    }
}
