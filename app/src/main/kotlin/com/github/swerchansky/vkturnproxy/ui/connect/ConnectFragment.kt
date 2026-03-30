package com.github.swerchansky.vkturnproxy.ui.connect

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentConnectBinding
import com.github.swerchansky.vkturnproxy.service.ProxyService
import com.github.swerchansky.vkturnproxy.ui.base.BaseFragment
import com.github.swerchansky.vkturnproxy.ui.detail.ConnectionDetailSheet
import com.github.swerchansky.vkturnproxy.ui.main.MainViewModel
import com.github.swerchansky.vkturnproxy.ui.main.ProxyState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
class ConnectFragment : BaseFragment() {

    companion object {
        fun newInstance() = ConnectFragment()
    }

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels { appComponent.viewModelFactory() }

    private val prefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)
    }

    private var cardColorAnimator: ValueAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var timerJob: Job? = null
    private var currentCardColor: Int = Color.parseColor("#1A1E2E")
    private var lastClipboardLink: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupViews()
        setupServerHistory()
        observeState()
        observeStats()
    }

    override fun onResume() {
        super.onResume()
        checkClipboard()
        consumeSharedLink()
    }

    private fun consumeSharedLink() {
        val activity =
            requireActivity() as? com.github.swerchansky.vkturnproxy.ui.main.MainActivity ?: return
        val link = activity.pendingSharedLink ?: return
        activity.pendingSharedLink = null
        binding.etLink.setText(link)
        Snackbar.make(
            binding.root,
            R.string.share_received_link,
            Snackbar.LENGTH_SHORT,
        ).show()
    }

    private fun loadSettings() {
        binding.etLink.setText(prefs.getString("link", ""))
        binding.etPeer.setText(prefs.getString("peer", ""))
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("link", binding.etLink.text.toString())
            .putString("peer", binding.etPeer.text.toString())
            .apply()
    }

    // ── Server history dropdown ──

    private fun setupServerHistory() {
        refreshServerHistoryAdapter()
        binding.etPeer.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.etPeer.showDropDown()
        }
        binding.etPeer.setOnClickListener {
            binding.etPeer.showDropDown()
        }
        binding.etPeer.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            if (selected.contains(" (") && selected.endsWith(")")) {
                val addr = selected.substringAfterLast(" (").substringBeforeLast(")")
                binding.etPeer.setText(addr)
                binding.etPeer.setSelection(addr.length)
            }
        }
    }

    private fun refreshServerHistoryAdapter() {
        val favorites = loadFavorites().map { "${it.first} (${it.second})" }
        val history = loadServerHistory()
        val all = (favorites + history).distinct()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            all,
        )
        binding.etPeer.setAdapter(adapter)
    }

    private fun loadServerHistory(): List<String> {
        val raw = prefs.getString("server_history", "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }
    }

    private fun saveToServerHistory(server: String) {
        if (server.isBlank()) return
        val history = loadServerHistory().toMutableList()
        history.remove(server)
        history.add(0, server)
        prefs.edit().putString("server_history", history.take(5).joinToString(",")).apply()
        refreshServerHistoryAdapter()
    }

    private fun saveToFavorites(name: String, server: String) {
        val favorites = loadFavorites().toMutableList()
        favorites.removeAll { it.second == server }
        favorites.add(0, name to server)
        val raw = favorites.joinToString(",") { "${it.first}|${it.second}" }
        prefs.edit().putString("favorites", raw).apply()
        refreshServerHistoryAdapter()
    }

    private fun loadFavorites(): List<Pair<String, String>> {
        val raw = prefs.getString("favorites", "") ?: ""
        return raw.split(",").filter { it.isNotBlank() }.map {
            val parts = it.split("|")
            (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
        }
    }

    private fun showSaveFavoriteDialog() {
        val server = binding.etPeer.text.toString().trim()
        if (server.isEmpty()) {
            binding.tilPeer.error = getString(R.string.error_required)
            return
        }

        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_favorite_name, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etFavoriteName)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_favorite)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveToFavorites(name, server)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupViews() {
        // Status card → open detail sheet
        binding.statusCard.setOnClickListener {
            if (viewModel.state.value is ProxyState.Connected) {
                ConnectionDetailSheet.newInstance()
                    .show(parentFragmentManager, "detail")
            }
        }

        // Paste end icon on link field
        binding.tilLink.setEndIconOnClickListener {
            pasteFromClipboard()
        }

        // Star end icon on peer field
        binding.tilPeer.setEndIconOnClickListener {
            showSaveFavoriteDialog()
        }

        // Real-time validation: clear error once user types
        binding.etLink.addTextChangedListener {
            if (!it.isNullOrBlank()) binding.tilLink.error = null
        }
        binding.etPeer.addTextChangedListener {
            if (!it.isNullOrBlank()) binding.tilPeer.error = null
        }

        // FAB
        binding.fabConnect.setOnClickListener {
            val state = viewModel.state.value
            if (state is ProxyState.Connected || state is ProxyState.Connecting) {
                binding.fabConnect.performHapticFeedback(HapticFeedbackConstants.REJECT)
                viewModel.disconnect()
            } else {
                attemptConnect()
            }
        }
    }

    private fun attemptConnect() {
        val link = binding.etLink.text.toString().trim()
        val peer = binding.etPeer.text.toString().trim()

        var valid = true
        if (link.isEmpty()) {
            binding.tilLink.error = getString(R.string.error_required)
            shakeView(binding.tilLink)
            valid = false
        }
        if (peer.isEmpty()) {
            binding.tilPeer.error = getString(R.string.error_required)
            shakeView(binding.tilPeer)
            valid = false
        }
        if (!valid) {
            Snackbar.make(binding.root, R.string.fill_all_fields, Snackbar.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        val rawLink = link.split("join/").lastOrNull()
            ?.substringBefore("?")?.substringBefore("/")?.substringBefore("#") ?: link

        val port = prefs.getString("port", "9000")?.toIntOrNull() ?: 9000
        val n = prefs.getString("connections", "0")?.toIntOrNull() ?: 0

        viewModel.connect(rawLink, peer, port, n)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    updateStateUI(state)
                }
            }
        }
    }

    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stats.collect { stats ->
                    if (stats.connectedSince > 0) {
                        val upFmt = ProxyService.formatPackets(stats.toServerPkts)
                        val downFmt = ProxyService.formatPackets(stats.fromServerPkts)
                        binding.tvPktsUp.text = getString(R.string.packets_up_fmt, upFmt)
                        binding.tvPktsDown.text = getString(R.string.packets_down_fmt, downFmt)
                        binding.activityIndicator.isVisible =
                            viewModel.state.value is ProxyState.Connected &&
                                    (stats.toServerPps > 0f || stats.fromServerPps > 0f)
                        
                        binding.networkGraph.addDataPoint(stats.toServerPps, stats.fromServerPps)
                    }
                }
            }
        }
    }

    private fun updateStateUI(state: ProxyState) {
        when (state) {
            is ProxyState.Idle -> {
                animateCardColor(R.color.card_idle)
                stopPulse()
                timerJob?.cancel()
                binding.tvStatusLabel.text = getString(R.string.status_idle)
                binding.tvTimer.isVisible = false
                binding.statsRow.isVisible = false
                binding.networkGraph.isVisible = false
                binding.networkGraph.clear()
                binding.tvConnectingStep.isVisible = false
                binding.tvTapDetails.isVisible = false
                binding.progressBar.isVisible = false
                setFabConnect()
            }

            is ProxyState.Connecting -> {
                animateCardColor(R.color.card_connecting)
                stopPulse()
                timerJob?.cancel()
                binding.tvStatusLabel.text = state.step
                binding.tvConnectingStep.text = state.step
                binding.tvConnectingStep.isVisible = true
                binding.tvTimer.isVisible = false
                binding.statsRow.isVisible = false
                binding.networkGraph.isVisible = false
                binding.tvTapDetails.isVisible = false
                binding.progressBar.isVisible = true
                setFabConnecting()
            }

            is ProxyState.Connected -> {
                animateCardColor(R.color.card_connected)
                startPulse()
                startTimer(viewModel.stats.value.connectedSince)
                saveToServerHistory(binding.etPeer.text.toString().trim())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    binding.fabConnect.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    binding.fabConnect.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                binding.tvStatusLabel.text = getString(R.string.status_connected)
                binding.tvConnectingStep.isVisible = false
                binding.tvTimer.isVisible = true
                binding.statsRow.isVisible = true
                binding.networkGraph.isVisible = true
                binding.tvTapDetails.isVisible = true
                binding.progressBar.isVisible = false
                setFabDisconnect()
                showRipple()
            }

            is ProxyState.Error -> {
                animateCardColor(R.color.card_error)
                stopPulse()
                timerJob?.cancel()
                binding.tvStatusLabel.text = getString(R.string.status_error)
                binding.tvTimer.isVisible = false
                binding.statsRow.isVisible = false
                binding.networkGraph.isVisible = false
                binding.tvConnectingStep.isVisible = false
                binding.tvTapDetails.isVisible = false
                binding.progressBar.isVisible = false
                setFabConnect()
            }
        }
        animateStatusCard()

        val dotColorRes = when (state) {
            is ProxyState.Idle -> R.color.status_idle
            is ProxyState.Connecting -> R.color.status_connecting
            is ProxyState.Connected -> R.color.status_connected
            is ProxyState.Error -> R.color.status_error
        }
        val dotColor = ContextCompat.getColor(requireContext(), dotColorRes)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
    }

    private fun animateStatusCard() {
        binding.statusCard.alpha = 0f
        binding.statusCard.scaleX = 0.9f
        binding.statusCard.scaleY = 0.9f
        binding.statusCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(AnticipateOvershootInterpolator())
            .start()
    }

    // ── FAB state transitions ──

    private fun setFabConnect() {
        binding.fabConnect.apply {
            extend()
            text = getString(R.string.connect)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_connect_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.primary)
            )
            isEnabled = true
        }
    }

    private fun setFabConnecting() {
        binding.fabConnect.apply {
            shrink()
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_stop_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_connecting)
            )
            isEnabled = true
        }
    }

    private fun setFabDisconnect() {
        binding.fabConnect.apply {
            extend()
            text = getString(R.string.disconnect)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_stop_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.status_error)
            )
            isEnabled = true
        }
    }

    // ── Animated card background ──

    private fun animateCardColor(colorRes: Int) {
        val target = ContextCompat.getColor(requireContext(), colorRes)
        if (target == currentCardColor) return
        cardColorAnimator?.cancel()
        cardColorAnimator = ValueAnimator.ofArgb(currentCardColor, target).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val color = anim.animatedValue as Int
                binding.statusCard.setCardBackgroundColor(color)
                currentCardColor = color
            }
            start()
        }
    }

    // ── Pulsing dot animation ──

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.statusDot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.8f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.8f, 1f),
        ).apply {
            duration = 1600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.statusDot.scaleX = 1f
        binding.statusDot.scaleY = 1f
    }

    // ── Connect success ripple ──

    private fun showRipple() {
        val v = binding.rippleView
        v.visibility = View.VISIBLE
        v.alpha = 0.8f
        v.scaleX = 0.2f
        v.scaleY = 0.2f
        v.animate()
            .scaleX(4f).scaleY(4f)
            .alpha(0f)
            .setDuration(700)
            .withEndAction { v.visibility = View.GONE }
            .start()
    }

    // ── Session timer ──

    private fun startTimer(connectedSince: Long) {
        timerJob?.cancel()
        if (connectedSince <= 0) return
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - connectedSince
                val h = TimeUnit.MILLISECONDS.toHours(elapsed)
                val m = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                val s = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
                binding.tvTimer.text = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }

    // ── Shake animation for validation ──

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(
            view, "translationX",
            0f, -18f, 18f, -14f, 14f, -10f, 10f, -6f, 6f, 0f,
        ).apply {
            duration = 450
            start()
        }
    }

    // ── Clipboard detection ──

    private fun checkClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text == lastClipboardLink) return
        if (text.contains("vk.com/call/join") || text.contains("vk.me/") || text.contains("vk.com/video_call")) {
            lastClipboardLink = text
            Snackbar.make(binding.root, R.string.clipboard_link_detected, Snackbar.LENGTH_LONG)
                .setAction(R.string.paste) { binding.etLink.setText(text) }
                .show()
        }
    }

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        binding.etLink.setText(text)
    }

    override fun onDestroyView() {
        cardColorAnimator?.cancel()
        pulseAnimator?.cancel()
        timerJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
