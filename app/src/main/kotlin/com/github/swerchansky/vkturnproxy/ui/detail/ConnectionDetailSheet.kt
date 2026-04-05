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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.data.repository.ProxyRepository
import com.github.swerchansky.vkturnproxy.databinding.BottomSheetConnectionDetailBinding
import com.github.swerchansky.vkturnproxy.domain.ProxyConnectionState
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class ConnectionDetailSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = ConnectionDetailSheet()
    }

    @Inject lateinit var proxyRepository: ProxyRepository

    private var _binding: BottomSheetConnectionDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetConnectionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity().application as App).appComponent.inject(this)
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
                proxyRepository.connectionState.collect { state ->
                    val colorRes = when (state) {
                        is ProxyConnectionState.Connected -> R.color.status_connected
                        is ProxyConnectionState.Connecting -> R.color.status_connecting
                        is ProxyConnectionState.Error -> R.color.status_error
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
                proxyRepository.stats.collect { stats ->
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
