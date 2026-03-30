package com.github.swerchansky.vkturnproxy.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatDelegate
import com.github.swerchansky.vkturnproxy.BuildConfig
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentSettingsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupViews()
    }

    private val themeLabels by lazy {
        listOf(
            getString(R.string.settings_theme_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark),
        )
    }

    private val themeModes = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
    )

    private fun loadSettings() {
        binding.etPort.setText(prefs.getString("port", "9000"))
        binding.etConnections.setText(prefs.getString("connections", "0"))
        binding.switchAutoScroll.isChecked = prefs.getBoolean("auto_scroll", true)
        binding.switchSaveLogs.isChecked = prefs.getBoolean("save_logs", false)
        binding.switchNotifications.isChecked = prefs.getBoolean("notifications", true)
        binding.tvVersion.text = BuildConfig.VERSION_NAME

        val savedMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val idx = themeModes.indexOf(savedMode).coerceAtLeast(0)
        binding.spinnerTheme.setText(themeLabels[idx], false)
    }

    private fun setupViews() {
        binding.etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) savePort()
        }
        binding.etConnections.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveConnections()
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_scroll", checked).apply()
        }
        binding.switchSaveLogs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("save_logs", checked).apply()
        }
        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notifications", checked).apply()
        }
        setupThemeSpinner()
        binding.rowGitHub.setOnClickListener {
            val url = "https://github.com/swerchansky/vk-turn-proxy-kotlin"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun setupThemeSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, themeLabels)
        binding.spinnerTheme.setAdapter(adapter)
        binding.spinnerTheme.setOnItemClickListener { _, _, position, _ ->
            val mode = themeModes[position]
            prefs.edit().putInt("theme_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun savePort() {
        val port = binding.etPort.text.toString().trim()
        if (port.isNotEmpty()) prefs.edit().putString("port", port).apply()
    }

    private fun saveConnections() {
        val n = binding.etConnections.text.toString().trim()
        if (n.isNotEmpty()) prefs.edit().putString("connections", n).apply()
    }

    override fun onPause() {
        super.onPause()
        savePort()
        saveConnections()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
