package com.github.swerchansky.vkturnproxy.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentLogsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import com.github.swerchansky.vkturnproxy.ui.main.MainViewModel
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class LogsFragment : BaseFragment() {

    companion object {
        fun newInstance() = LogsFragment()
    }

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels { appComponent.viewModelFactory() }

    private var autoScroll = true
    private var searchQuery = ""
    private var fullLogText = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupSearch()
        setupScroll()
        setupFab()
        observeState()
        observeLogs()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_search -> {
                    val visible = binding.searchLayout.isVisible
                    binding.searchLayout.isVisible = !visible
                    if (!visible) binding.etSearch.requestFocus()
                    else {
                        searchQuery = ""
                        binding.etSearch.setText("")
                        renderLog(fullLogText)
                    }
                    true
                }
                R.id.action_share -> {
                    shareLog()
                    true
                }
                R.id.action_copy_all -> {
                    copyAllLogs()
                    true
                }
                R.id.action_clear -> {
                    viewModel.clearLog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener { editable ->
            searchQuery = editable?.toString() ?: ""
            renderLog(fullLogText)
        }
    }

    private fun setupScroll() {
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val maxScroll = (binding.scrollView.getChildAt(0)?.height ?: 0) - binding.scrollView.height
            autoScroll = maxScroll <= 0 || scrollY >= maxScroll - 80
            binding.fabScrollDown.isVisible = !autoScroll && fullLogText.isNotEmpty()
        }
    }

    private fun setupFab() {
        binding.fabScrollDown.setOnClickListener {
            binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
            autoScroll = true
            binding.fabScrollDown.isVisible = false
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val (text, colorRes) = when (state) {
                        is ProxyState.Idle -> getString(R.string.status_idle) to R.color.status_idle
                        is ProxyState.Connecting -> getString(R.string.status_connecting) to R.color.status_connecting
                        is ProxyState.Connected -> getString(R.string.status_connected) to R.color.status_connected
                        is ProxyState.Error -> getString(R.string.status_error) to R.color.status_error
                    }
                    binding.tvStatus.text = text
                    val color = ContextCompat.getColor(requireContext(), colorRes)
                    binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
                }
            }
        }
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.log.collect { logText ->
                    fullLogText = logText
                    renderLog(logText)
                    val lines = if (logText.isEmpty()) 0 else logText.count { it == '\n' } + 1
                    val kb = logText.toByteArray().size / 1024f
                    binding.tvLineCount.text = if (lines > 0) "$lines lines · %.1f KB".format(kb) else ""
                    if (autoScroll && logText.isNotEmpty()) {
                        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun renderLog(text: String) {
        if (text.isEmpty()) {
            binding.tvLog.text = getString(R.string.logs_empty)
            binding.tvLog.setTextColor(ContextCompat.getColor(requireContext(), R.color.log_timestamp))
            return
        }

        val filtered = if (searchQuery.isBlank()) text
        else text.lines().filter { it.contains(searchQuery, ignoreCase = true) }.joinToString("\n")

        binding.tvLog.text = colorizeLog(filtered)
    }

    private fun colorizeLog(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val colorSuccess = ContextCompat.getColor(requireContext(), R.color.log_success)
        val colorError = ContextCompat.getColor(requireContext(), R.color.log_error)
        val colorInfo = ContextCompat.getColor(requireContext(), R.color.log_info)
        val colorWarning = ContextCompat.getColor(requireContext(), R.color.log_warning)
        val colorTimestamp = ContextCompat.getColor(requireContext(), R.color.log_timestamp)
        val colorDefault = ContextCompat.getColor(requireContext(), R.color.log_default)

        text.lines().forEachIndexed { idx, line ->
            val start = sb.length
            sb.append(line)
            if (idx < text.lines().size - 1) sb.append("\n")

            // Color timestamp part [HH:MM:SS]
            val timestampEnd = line.indexOf("] ")
            if (timestampEnd > 0 && line.startsWith("[")) {
                sb.setSpan(
                    ForegroundColorSpan(colorTimestamp),
                    start, start + timestampEnd + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            val lineColor = when {
                line.contains("error", ignoreCase = true) ||
                line.contains("failed", ignoreCase = true) ||
                line.contains("Error:") -> colorError

                line.contains("Connected!", ignoreCase = false) ||
                line.contains("handshake OK") ||
                line.contains("Credentials OK") -> colorSuccess

                line.contains("TURN connect") ||
                line.contains("DTLS handshake") ||
                line.contains("Listening on") ||
                line.contains("relay:") -> colorInfo

                line.contains("Disconnected") ||
                line.contains("ended") -> colorWarning

                else -> colorDefault
            }

            val contentStart = if (timestampEnd > 0 && line.startsWith("[")) start + timestampEnd + 2 else start
            sb.setSpan(
                ForegroundColorSpan(lineColor),
                contentStart, start + line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    private fun copyAllLogs() {
        if (fullLogText.isEmpty()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logs", fullLogText))
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, R.string.copied_to_clipboard, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
        ).show()
    }

    private fun shareLog() {
        if (fullLogText.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, fullLogText)
            putExtra(Intent.EXTRA_SUBJECT, "vk-turn-proxy logs")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
