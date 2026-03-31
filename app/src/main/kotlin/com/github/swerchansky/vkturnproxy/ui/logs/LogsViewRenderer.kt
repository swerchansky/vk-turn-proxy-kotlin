package com.github.swerchansky.vkturnproxy.ui.logs

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentLogsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewRenderer
import com.github.swerchansky.vkturnproxy.ui.connect.StatusColor

class LogsViewRenderer(
    binding: FragmentLogsBinding,
) : BaseViewRenderer<FragmentLogsBinding, LogsIntent, LogsUiState>(binding) {

    private val context get() = binding.root.context

    private val adapter = LogLineAdapter(context) { line ->
        // long press callback fires intent via stored lambda
        intentDispatcher?.invoke(LogsIntent.LineLongPressed(line))
    }
    private var intentDispatcher: ((LogsIntent) -> Unit)? = null
    private var liveAnimator: ObjectAnimator? = null
    private var lastLiveState: Boolean? = null

    override fun setup(onIntent: (LogsIntent) -> Unit) {
        intentDispatcher = onIntent

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context).also { it.stackFromEnd = false }
            this.adapter = this@LogsViewRenderer.adapter
            setHasFixedSize(false)
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    val lm = recyclerView.layoutManager as LinearLayoutManager
                    val last = lm.findLastCompletelyVisibleItemPosition()
                    val count = recyclerView.adapter?.itemCount ?: 0
                    val atBottom = count == 0 || last >= count - 1
                    if (atBottom) onIntent(LogsIntent.ScrolledToBottom)
                    else if (dy < 0) onIntent(LogsIntent.ScrolledUp)
                }
            })
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    onIntent(LogsIntent.SearchToggled)
                    if (!binding.searchLayout.isVisible) binding.etSearch.requestFocus()
                    true
                }
                R.id.action_share -> { onIntent(LogsIntent.ShareLogsClicked); true }
                R.id.action_copy_all -> { onIntent(LogsIntent.CopyAllClicked); true }
                R.id.action_clear -> { onIntent(LogsIntent.ClearLogsClicked); true }
                R.id.action_toggle_timestamps -> { onIntent(LogsIntent.TimestampToggled); true }
                R.id.action_toggle_word_wrap -> { onIntent(LogsIntent.WordWrapToggled); true }
                else -> false
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onIntent(LogsIntent.SearchQueryChanged(s.toString()))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val level = when (checkedIds.firstOrNull()) {
                R.id.chipInfo -> LogLevel.INFO
                R.id.chipWarn -> LogLevel.WARN
                R.id.chipError -> LogLevel.ERROR
                R.id.chipSuccess -> LogLevel.SUCCESS
                else -> LogLevel.ALL
            }
            onIntent(LogsIntent.LevelFilterChanged(level))
        }

        binding.fabScrollDown.setOnClickListener {
            scrollToBottom()
            onIntent(LogsIntent.ScrolledToBottom)
        }
    }

    override fun render(state: LogsUiState) {
        adapter.updateDisplayOptions(state.showTimestamps, state.wordWrap)
        adapter.submitList(state.lines)

        renderStatus(state)
        renderLiveIndicator(state.isLive)
        renderSearchBar(state)
        renderLevelFilter(state)
        renderToolbarCheckedItems(state)

        binding.fabScrollDown.isVisible = state.isFabVisible
        binding.tvLineCount.text = state.lineCountLabel
    }

    fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) binding.recyclerView.scrollToPosition(count - 1)
    }

    override fun clear() {
        liveAnimator?.cancel()
        liveAnimator = null
        intentDispatcher = null
        super.clear()
    }

    // ── Private render helpers ─────────────────────────────────────────────────

    private fun renderStatus(state: LogsUiState) {
        binding.tvStatus.text = state.statusLabel
        val colorRes = when (state.statusDotColor) {
            StatusColor.IDLE -> R.color.status_idle
            StatusColor.CONNECTING -> R.color.status_connecting
            StatusColor.CONNECTED -> R.color.status_connected
            StatusColor.ERROR -> R.color.status_error
        }
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
    }

    private fun renderLiveIndicator(isLive: Boolean) {
        if (lastLiveState == isLive) return
        lastLiveState = isLive
        if (isLive) {
            binding.tvLive.isVisible = true
            liveAnimator?.cancel()
            liveAnimator = ObjectAnimator.ofFloat(binding.tvLive, "alpha", 1f, 0.15f).apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                duration = 900
                start()
            }
        } else {
            liveAnimator?.cancel()
            liveAnimator = null
            binding.tvLive.animate().alpha(0f).setDuration(200).withEndAction {
                binding.tvLive.isVisible = false
                binding.tvLive.alpha = 1f
            }.start()
        }
    }

    private fun renderSearchBar(state: LogsUiState) {
        binding.searchLayout.isVisible = state.isSearchBarVisible
        if (state.searchQuery != binding.etSearch.text.toString()) {
            binding.etSearch.setText(state.searchQuery)
        }
    }

    private fun renderLevelFilter(state: LogsUiState) {
        val targetChipId = when (state.levelFilter) {
            LogLevel.ALL -> R.id.chipAll
            LogLevel.INFO -> R.id.chipInfo
            LogLevel.WARN -> R.id.chipWarn
            LogLevel.ERROR -> R.id.chipError
            LogLevel.SUCCESS -> R.id.chipSuccess
        }
        if (binding.chipGroupFilter.checkedChipId != targetChipId) {
            binding.chipGroupFilter.check(targetChipId)
        }
    }

    private fun renderToolbarCheckedItems(state: LogsUiState) {
        binding.toolbar.menu.findItem(R.id.action_toggle_timestamps)?.isChecked = state.showTimestamps
        binding.toolbar.menu.findItem(R.id.action_toggle_word_wrap)?.isChecked = state.wordWrap
    }
}
