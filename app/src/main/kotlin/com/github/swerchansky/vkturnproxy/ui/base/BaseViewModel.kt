package com.github.swerchansky.vkturnproxy.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel<I : Any, S : Any> : ViewModel() {

    private val _sideEffects = Channel<S>(Channel.BUFFERED)
    val sideEffects: Flow<S> = _sideEffects.receiveAsFlow()

    abstract fun handleIntent(intent: I)

    protected fun emitSideEffect(effect: S) {
        viewModelScope.launch { _sideEffects.send(effect) }
    }
}
