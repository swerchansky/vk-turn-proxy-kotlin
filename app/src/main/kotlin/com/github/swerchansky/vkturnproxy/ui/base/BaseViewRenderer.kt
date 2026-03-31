package com.github.swerchansky.vkturnproxy.ui.base

import androidx.viewbinding.ViewBinding

abstract class BaseViewRenderer<B : ViewBinding, I : Any, S : Any>(
    protected val binding: B,
) {
    abstract fun setup(onIntent: (I) -> Unit)
    abstract fun render(state: S)
    open fun clear() {}
}
