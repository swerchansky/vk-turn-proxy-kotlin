package com.github.swerchansky.vkturnproxy.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentLogsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import javax.inject.Inject

class LogsFragment : BaseFragment() {

    companion object {
        fun newInstance() = LogsFragment()
    }

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    private lateinit var viewModel: LogsViewModel

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private var renderer: LogsViewRenderer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appComponent.inject(this)
        viewModel = ViewModelProvider(this, viewModelFactory)[LogsViewModel::class.java]

        renderer = LogsViewRenderer(binding).also { it.setup { intent -> viewModel.handleIntent(intent) } }

        viewModel.uiState.collectWithLifecycle { renderer?.render(it) }
        observeSideEffects()
    }

    override fun onResume() {
        super.onResume()
        viewModel.handleIntent(LogsIntent.TabResumed)
    }

    // ── Side effects ───────────────────────────────────────────────────────────

    private fun observeSideEffects() {
        viewModel.sideEffects.collectWithLifecycle { effect ->
            when (effect) {
                is LogsSideEffect.ShareText -> shareText(effect.text)
                is LogsSideEffect.CopyToClipboard -> copyToClipboard(effect.text)
                LogsSideEffect.ScrollToBottom -> renderer?.scrollToBottom()
                is LogsSideEffect.ShowLineContextMenu -> showLineContextMenu(effect.line)
            }
        }
    }

    private fun showLineContextMenu(line: String) {
        val options = arrayOf(
            getString(R.string.log_line_copy),
            getString(R.string.log_line_search),
            getString(R.string.log_line_share),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(line)
                    1 -> {
                        // Extract content after timestamp for search, or use raw
                        val query = if (line.contains("] ")) line.substringAfter("] ") else line
                        viewModel.handleIntent(LogsIntent.SearchToggled)
                        viewModel.handleIntent(LogsIntent.SearchQueryChanged(query.take(60)))
                    }
                    2 -> shareText(line)
                }
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log line", text))
        Snackbar.make(binding.root, R.string.copied_to_clipboard, Snackbar.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "vk-turn-proxy logs")
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
    }

    override fun onDestroyView() {
        renderer?.clear()
        renderer = null
        super.onDestroyView()
        _binding = null
    }
}
