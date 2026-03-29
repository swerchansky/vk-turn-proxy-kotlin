package com.github.swerchansky.vkturnproxy.ui.main

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.databinding.FragmentMainBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import kotlinx.coroutines.launch

class MainFragment : BaseFragment() {

    companion object {
        fun newInstance() = MainFragment()
    }

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels { appComponent.viewModelFactory() }

    private val prefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("proxy_settings", android.content.Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        loadSettings()
        observeState()
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("link", binding.etLink.text.toString())
            .putString("peer", binding.etPeer.text.toString())
            .putString("port", binding.etPort.text.toString())
            .putString("connections", binding.etConnections.text.toString())
            .putBoolean("udp", binding.switchUdp.isChecked)
            .putBoolean("is_vk", binding.rbVk.isChecked)
            .apply()
    }

    private fun loadSettings() {
        binding.etLink.setText(prefs.getString("link", ""))
        binding.etPeer.setText(prefs.getString("peer", ""))
        binding.etPort.setText(prefs.getString("port", ""))
        binding.etConnections.setText(prefs.getString("connections", "0"))
        binding.switchUdp.isChecked = prefs.getBoolean("udp", false)
        val isVk = prefs.getBoolean("is_vk", true)
        binding.rbVk.isChecked = isVk
        binding.rbYandex.isChecked = !isVk
    }

    private fun setupViews() {
        binding.btnConnect.setOnClickListener {
            val link = binding.etLink.text.toString().trim()
            val peer = binding.etPeer.text.toString().trim()
            val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 9000
            val n = binding.etConnections.text.toString().trim().toIntOrNull() ?: 0
            val useUdp = binding.switchUdp.isChecked
            val isVk = binding.rbVk.isChecked

            if (link.isEmpty() || peer.isEmpty()) {
                binding.tvStatusLabel.text = "Fill in all fields"
                return@setOnClickListener
            }

            saveSettings()

            val rawLink = if (isVk) {
                link.split("join/").lastOrNull()
                    ?.substringBefore("?")?.substringBefore("/")?.substringBefore("#") ?: link
            } else {
                link.split("j/").lastOrNull()
                    ?.substringBefore("?")?.substringBefore("/")?.substringBefore("#") ?: link
            }

            viewModel.connect(rawLink, peer, port, useUdp, isVk, n)
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.btnClearLog.setOnClickListener {
            viewModel.clearLog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is ProxyState.Idle -> {
                            binding.tvStatusLabel.text = "Idle"
                            binding.btnConnect.isEnabled = true
                            binding.btnDisconnect.isVisible = false
                            binding.progressBar.isVisible = false
                        }
                        is ProxyState.Connecting -> {
                            binding.tvStatusLabel.text = state.step
                            binding.btnConnect.isEnabled = false
                            binding.btnDisconnect.isVisible = true
                            binding.progressBar.isVisible = true
                        }
                        is ProxyState.Connected -> {
                            binding.tvStatusLabel.text = "Connected"
                            binding.btnConnect.isEnabled = false
                            binding.btnDisconnect.isVisible = true
                            binding.progressBar.isVisible = false
                        }
                        is ProxyState.Error -> {
                            binding.tvStatusLabel.text = "Error"
                            binding.btnConnect.isEnabled = true
                            binding.btnDisconnect.isVisible = false
                            binding.progressBar.isVisible = false
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.log.collect { logText ->
                    binding.tvStatus.text = logText
                    binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
