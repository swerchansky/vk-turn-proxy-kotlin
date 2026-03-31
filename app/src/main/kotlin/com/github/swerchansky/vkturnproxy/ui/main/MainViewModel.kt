package com.github.swerchansky.vkturnproxy.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.swerchansky.vkturnproxy.data.repository.ProxyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

class MainViewModel @Inject constructor(
    private val proxyRepository: ProxyRepository,
) : ViewModel() {

    // Used by MainActivity for Logs badge only
    val logLineCount: StateFlow<Int> = proxyRepository.log
        .map { if (it.isEmpty()) 0 else it.lines().size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
}
