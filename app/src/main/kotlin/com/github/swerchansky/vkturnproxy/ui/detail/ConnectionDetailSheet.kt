package com.github.swerchansky.vkturnproxy.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.BottomSheetConnectionDetailBinding
import com.github.swerchansky.vkturnproxy.ui.main.MainViewModel
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionDetailSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = ConnectionDetailSheet()
    }

    private var _binding: BottomSheetConnectionDetailBinding? = null
    private val binding get() = _binding!!

    private val appComponent get() = (requireActivity().application as App).appComponent
    private val viewModel: MainViewModel by activityViewModels { appComponent.viewModelFactory() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetConnectionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCopyButton()
        observeStats()
        observeState()
    }

    private fun setupCopyButton() {
        binding.btnCopyRelay.setOnClickListener {
            val addr = binding.tvRelayAddr.text.toString()
            if (addr == "—") return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("relay_addr", addr))
            Snackbar.make(binding.root, getString(R.string.copied), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val colorRes = when (state) {
                        is ProxyState.Connected -> R.color.status_connected
                        is ProxyState.Connecting -> R.color.status_connecting
                        is ProxyState.Error -> R.color.status_error
                        else -> R.color.status_idle
                    }
                    val color = ContextCompat.getColor(requireContext(), colorRes)
                    binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
                }
            }
        }
    }

    @Suppress("ImplicitDefaultLocale")
    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stats.collect { stats ->
                    binding.tvRelayAddr.text = stats.relayAddr.ifEmpty { "—" }
                    binding.tvPktsSent.text = stats.toServerPkts.toString()
                    binding.tvPktsRecv.text = stats.fromServerPkts.toString()
                    binding.tvPps.text = String.format(
                        "%.0f pps up / %.0f pps down",
                        stats.toServerPps,
                        stats.fromServerPps,
                    )
                    binding.tvConnectedSince.text = if (stats.connectedSince > 0) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(stats.connectedSince))
                    } else "—"

                    binding.networkGraph.addDataPoint(stats.toServerPps, stats.fromServerPps)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
