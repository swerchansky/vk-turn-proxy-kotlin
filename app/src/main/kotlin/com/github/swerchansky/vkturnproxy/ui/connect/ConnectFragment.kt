package com.github.swerchansky.vkturnproxy.ui.connect

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentConnectBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import com.github.swerchansky.vkturnproxy.ui.detail.ConnectionDetailSheet
import com.github.swerchansky.vkturnproxy.ui.main.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import javax.inject.Inject

class ConnectFragment : BaseFragment() {

    companion object {
        fun newInstance() = ConnectFragment()
    }

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    private lateinit var viewModel: ConnectViewModel

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private var renderer: ConnectViewRenderer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appComponent.inject(this)
        viewModel = ViewModelProvider(this, viewModelFactory)[ConnectViewModel::class.java]

        renderer = ConnectViewRenderer(binding).also { it.setup { intent -> viewModel.handleIntent(intent) } }

        viewModel.uiState.collectWithLifecycle { renderer?.render(it) }
        observeSideEffects()
    }

    override fun onResume() {
        super.onResume()
        checkClipboard()
        consumeSharedLink()
    }

    // ── Side effects ───────────────────────────────────────────────────────────

    private fun observeSideEffects() {
        viewModel.sideEffects.collectWithLifecycle { effect ->
            when (effect) {
                is ConnectSideEffect.ShowError ->
                    Snackbar.make(binding.root, effect.message, Snackbar.LENGTH_SHORT).show()
                is ConnectSideEffect.ShowFavoriteNameDialog ->
                    showFavoriteNameDialog(effect.address)
                is ConnectSideEffect.ShowClipboardDetected ->
                    showClipboardDetectedSnackbar(effect.link)
                ConnectSideEffect.RequestClipboardPaste ->
                    pasteFromClipboard()
                ConnectSideEffect.OpenConnectionDetail ->
                    ConnectionDetailSheet.newInstance().show(parentFragmentManager, "detail")
                ConnectSideEffect.RequestNotificationPermission ->
                    Unit // handled by MainActivity
                ConnectSideEffect.ShowQuickOptions ->
                    showQuickOptionsSheet()
            }
        }
    }

    // ── Clipboard ──────────────────────────────────────────────────────────────

    private fun checkClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text.contains("vk.com/call/join") ||
            text.contains("vk.me/") ||
            text.contains("vk.com/video_call")
        ) {
            viewModel.handleIntent(ConnectIntent.ClipboardLinkDetected(text))
        }
    }

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        viewModel.handleIntent(ConnectIntent.LinkChanged(text))
    }

    private fun consumeSharedLink() {
        val activity = requireActivity() as? MainActivity ?: return
        val link = activity.pendingSharedLink ?: return
        activity.pendingSharedLink = null
        viewModel.handleIntent(ConnectIntent.LinkChanged(link))
        Snackbar.make(binding.root, R.string.share_received_link, Snackbar.LENGTH_SHORT).show()
    }

    private fun showClipboardDetectedSnackbar(link: String) {
        Snackbar.make(binding.root, "Найдена ссылка VK — вставить?", Snackbar.LENGTH_LONG)
            .setAction("Вставить") {
                viewModel.handleIntent(ConnectIntent.ClipboardLinkAccepted(link))
            }
            .show()
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────

    private fun showFavoriteNameDialog(address: String) {
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_name, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etFavoriteName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_favorite)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: return@setPositiveButton
                if (name.isNotEmpty()) {
                    viewModel.handleIntent(ConnectIntent.AddFavoriteConfirmed(name, address))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showQuickOptionsSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_quick_options, null, false)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderConnections)
        val label = view.findViewById<android.widget.TextView>(R.id.tvConnectionsLabel)
        val btnApply = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnApplyQuickOptions)

        val initial = viewModel.uiState.value.nConnections.toFloat().coerceIn(1f, 32f)
        slider.value = initial
        label.text = getString(R.string.quick_options_connections, initial.toInt())
        slider.addOnChangeListener { _, value, _ ->
            label.text = getString(R.string.quick_options_connections, value.toInt())
        }
        btnApply.setOnClickListener {
            viewModel.handleIntent(ConnectIntent.QuickOptionsNConnectionsSet(slider.value.toInt()))
            sheet.dismiss()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    override fun onDestroyView() {
        renderer?.clear()
        renderer = null
        super.onDestroyView()
        _binding = null
    }
}
