package com.github.swerchansky.vkturnproxy.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.github.swerchansky.vkturnproxy.databinding.FragmentSettingsBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import javax.inject.Inject

class SettingsFragment : BaseFragment() {

    companion object {
        fun newInstance() = SettingsFragment()
    }

    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    private lateinit var viewModel: SettingsViewModel

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var renderer: SettingsViewRenderer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appComponent.inject(this)
        viewModel = ViewModelProvider(this, viewModelFactory)[SettingsViewModel::class.java]

        renderer = SettingsViewRenderer(binding).also { it.setup { intent -> viewModel.handleIntent(intent) } }

        viewModel.uiState.collectWithLifecycle { renderer?.render(it) }
        observeSideEffects()
    }

    override fun onPause() {
        super.onPause()
        renderer?.commitPort()
        renderer?.commitConnections()
    }

    // ── Side effects ───────────────────────────────────────────────────────────

    private fun observeSideEffects() {
        viewModel.sideEffects.collectWithLifecycle { effect ->
            when (effect) {
                is SettingsSideEffect.OpenUrl ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))
                is SettingsSideEffect.ApplyTheme ->
                    AppCompatDelegate.setDefaultNightMode(effect.mode)
            }
        }
    }

    override fun onDestroyView() {
        renderer?.clear()
        renderer = null
        super.onDestroyView()
        _binding = null
    }
}
