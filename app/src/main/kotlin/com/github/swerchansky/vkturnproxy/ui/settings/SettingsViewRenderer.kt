package com.github.swerchansky.vkturnproxy.ui.settings

import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatDelegate
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentSettingsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewRenderer

class SettingsViewRenderer(
    binding: FragmentSettingsBinding,
) : BaseViewRenderer<FragmentSettingsBinding, SettingsIntent, SettingsUiState>(binding) {

    private val context get() = binding.root.context

    private val themeLabels = listOf(
        context.getString(R.string.settings_theme_system),
        context.getString(R.string.settings_theme_light),
        context.getString(R.string.settings_theme_dark),
    )

    private val themeModes = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
    )

    // Stored to call on Fragment.onPause
    private var onIntent: ((SettingsIntent) -> Unit)? = null

    override fun setup(onIntent: (SettingsIntent) -> Unit) {
        this.onIntent = onIntent
        setupThemeSpinner(onIntent)
        setupListeners(onIntent)
    }

    override fun render(state: SettingsUiState) {
        if (binding.etPort.text.toString() != state.listenPortText) {
            binding.etPort.setText(state.listenPortText)
        }
        if (binding.etConnections.text.toString() != state.nConnectionsText) {
            binding.etConnections.setText(state.nConnectionsText)
        }
        binding.switchAutoScroll.isChecked = state.autoScroll
        binding.switchSaveLogs.isChecked = state.saveLogs
        binding.switchNotifications.isChecked = state.notifications
        binding.spinnerTheme.setText(themeLabels.getOrElse(state.themeModeIndex) { themeLabels[0] }, false)
        binding.tvVersion.text = state.versionLabel
    }

    override fun clear() {
        onIntent = null
    }

    fun commitPort() {
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: return
        onIntent?.invoke(SettingsIntent.ListenPortChanged(port))
    }

    fun commitConnections() {
        val n = binding.etConnections.text.toString().trim().toIntOrNull() ?: return
        onIntent?.invoke(SettingsIntent.NConnectionsChanged(n))
    }

    private fun setupThemeSpinner(onIntent: (SettingsIntent) -> Unit) {
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            themeLabels,
        )
        binding.spinnerTheme.setAdapter(adapter)
        binding.spinnerTheme.setOnItemClickListener { _, _, position, _ ->
            onIntent(SettingsIntent.ThemeModeChanged(themeModes[position]))
        }
    }

    private fun setupListeners(onIntent: (SettingsIntent) -> Unit) {
        binding.etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitPort()
        }
        binding.etConnections.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitConnections()
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked ->
            onIntent(SettingsIntent.AutoScrollChanged(checked))
        }
        binding.switchSaveLogs.setOnCheckedChangeListener { _, checked ->
            onIntent(SettingsIntent.SaveLogsChanged(checked))
        }
        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            onIntent(SettingsIntent.NotificationsChanged(checked))
        }
        binding.rowGitHub.setOnClickListener {
            onIntent(SettingsIntent.GitHubClicked)
        }
    }
}
