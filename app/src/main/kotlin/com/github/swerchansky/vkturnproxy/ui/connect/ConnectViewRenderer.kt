package com.github.swerchansky.vkturnproxy.ui.connect

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentConnectBinding
import com.github.swerchansky.vkturnproxy.ui.base.BaseViewRenderer

class ConnectViewRenderer(
    binding: FragmentConnectBinding,
) : BaseViewRenderer<FragmentConnectBinding, ConnectIntent, ConnectUiState>(binding) {

    private val context get() = binding.root.context

    private var cardColorAnimator: ValueAnimator? = null
    private var circleColorAnimator: ValueAnimator? = null
    private var ringAnimatorSet: AnimatorSet? = null
    private var currentCardColor: Int = Color.parseColor("#1A1E2E")
    private var currentCircleColor: Int = 0x33FFFFFF.toInt()

    private var lastState: ConnectUiState = ConnectUiState()
    private var lastStatusColor: StatusColor? = null
    private var lastStepperIndex: Int = -1

    override fun setup(onIntent: (ConnectIntent) -> Unit) {
        binding.statusCard.setOnClickListener {
            onIntent(ConnectIntent.DetailCardTapped)
        }
        binding.tilLink.setEndIconOnClickListener {
            onIntent(ConnectIntent.PasteFromClipboard)
        }
        binding.tilPeer.setEndIconOnClickListener {
            onIntent(ConnectIntent.StarButtonClicked)
        }
        binding.etLink.doOnTextChanged { text, _, _, _ ->
            binding.tilLink.error = null
            onIntent(ConnectIntent.LinkChanged(text.toString()))
        }
        binding.etPeer.doOnTextChanged { text, _, _, _ ->
            binding.tilPeer.error = null
            onIntent(ConnectIntent.PeerChanged(text.toString()))
        }
        binding.etPeer.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.etPeer.showDropDown()
        }
        binding.etPeer.setOnClickListener {
            binding.etPeer.showDropDown()
        }
        binding.etPeer.setOnItemClickListener { parent, _, position, _ ->
            val selected = parent.getItemAtPosition(position) as String
            val addr = if (selected.contains(" (") && selected.endsWith(")")) {
                selected.substringAfterLast(" (").substringBeforeLast(")")
            } else selected
            onIntent(ConnectIntent.FavoriteSelected(addr))
        }
        binding.fabConnect.setOnClickListener {
            val isActiveOrConnecting = lastState.statusColor == StatusColor.CONNECTED ||
                lastState.statusColor == StatusColor.CONNECTING
            if (isActiveOrConnecting) {
                binding.fabConnect.performHapticFeedback(HapticFeedbackConstants.REJECT)
                onIntent(ConnectIntent.DisconnectClicked)
            } else {
                onIntent(ConnectIntent.ConnectClicked)
            }
        }
        binding.fabConnect.setOnLongClickListener {
            onIntent(ConnectIntent.QuickOptionsRequested)
            true
        }
    }

    override fun render(state: ConnectUiState) {
        lastState = state
        renderStatusCard(state)
        renderStepper(state)
        renderInputFields(state)
        renderActionButton(state)
    }

    override fun clear() {
        cardColorAnimator?.cancel()
        circleColorAnimator?.cancel()
        ringAnimatorSet?.cancel()
    }

    // ── Status card ────────────────────────────────────────────────────────────

    private fun renderStatusCard(state: ConnectUiState) {
        setStatusLabelAnimated(state.statusLabel)

        binding.tvTimer.isVisible = state.isTimerVisible
        binding.tvTimer.text = state.sessionTimer
        binding.statsRow.isVisible = state.isStatsRowVisible
        binding.networkGraph.isVisible = state.isGraphVisible
        binding.tvTapDetails.isVisible = state.isTapDetailsVisible
        binding.activityIndicator.isVisible = state.isActivityIndicatorVisible

        if (state.isGraphVisible) {
            binding.networkGraph.addDataPoint(
                state.graphStats.toServerPps,
                state.graphStats.fromServerPps,
            )
        }
        if (!state.isGraphVisible) binding.networkGraph.clear()

        val circleTarget = when (state.statusColor) {
            StatusColor.IDLE -> 0x28FFFFFF.toInt()
            StatusColor.CONNECTING -> 0x55FB8C00.toInt()
            StatusColor.CONNECTED -> 0x5543A047.toInt()
            StatusColor.ERROR -> 0x55E53935.toInt()
        }
        animateCircleColor(circleTarget)

        val cardColorRes = when (state.statusColor) {
            StatusColor.IDLE -> R.color.card_idle
            StatusColor.CONNECTING -> R.color.card_connecting
            StatusColor.CONNECTED -> R.color.card_connected
            StatusColor.ERROR -> R.color.card_error
        }
        animateCardColor(cardColorRes)

        val statusChanged = state.statusColor != lastStatusColor
        lastStatusColor = state.statusColor

        if (statusChanged) {
            when (state.statusColor) {
                StatusColor.CONNECTED -> {
                    startRings()
                    animateStatusCardPop()
                    performHapticOnConnect()
                }
                StatusColor.CONNECTING -> {
                    startRings()
                    animateStatusCardPop()
                }
                else -> {
                    stopRings()
                    animateStatusCardPop()
                }
            }
        } else if (state.statusColor == StatusColor.CONNECTED ||
            state.statusColor == StatusColor.CONNECTING
        ) {
            if (ringAnimatorSet?.isRunning != true) startRings()
        }
    }

    // ── Stepper ────────────────────────────────────────────────────────────────

    private fun renderStepper(state: ConnectUiState) {
        binding.stepperCard.isVisible = state.isStepperVisible
        if (!state.isStepperVisible) {
            lastStepperIndex = -1
            return
        }

        val idx = state.stepperIndex
        val colorIdle = ContextCompat.getColor(context, R.color.status_idle)
        val colorActive = ContextCompat.getColor(context, R.color.status_connecting)
        val colorDone = ContextCompat.getColor(context, R.color.status_connected)

        for (i in 1..4) {
            val dot = stepDot(i)
            val check = stepCheck(i)
            when {
                i < idx -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorDone)
                    check.isVisible = true
                }
                i == idx -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorActive)
                    check.isVisible = false
                    // Bounce dot when it first becomes active
                    if (lastStepperIndex != idx) bounceDot(dot)
                }
                else -> {
                    dot.backgroundTintList = ColorStateList.valueOf(colorIdle)
                    check.isVisible = false
                }
            }
        }
        lastStepperIndex = idx

        if (state.stepperTotalConnections > 0) {
            binding.step4Label.text = context.getString(
                R.string.step_establishing_fmt,
                state.stepperConnectedCount,
                state.stepperTotalConnections,
            )
        } else {
            binding.step4Label.text = context.getString(R.string.step_establishing)
        }
    }

    private fun stepDot(i: Int): View = when (i) {
        1 -> binding.step1Dot
        2 -> binding.step2Dot
        3 -> binding.step3Dot
        else -> binding.step4Dot
    }

    private fun stepCheck(i: Int): View = when (i) {
        1 -> binding.step1Check
        2 -> binding.step2Check
        3 -> binding.step3Check
        else -> binding.step4Check
    }

    // ── Input fields ───────────────────────────────────────────────────────────

    private fun renderInputFields(state: ConnectUiState) {
        if (binding.etLink.text.toString() != state.link) {
            binding.etLink.setText(state.link)
            binding.etLink.setSelection(state.link.length)
        }
        if (binding.etPeer.text.toString() != state.peer) {
            binding.etPeer.setText(state.peer)
            binding.etPeer.setSelection(state.peer.length)
        }
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_dropdown_item_1line,
            state.serverHistoryItems,
        )
        binding.etPeer.setAdapter(adapter)
    }

    // ── Action button ──────────────────────────────────────────────────────────

    private fun renderActionButton(state: ConnectUiState) {
        when (state.statusColor) {
            StatusColor.IDLE, StatusColor.ERROR -> setButtonConnect(state.actionButtonLabel)
            StatusColor.CONNECTING -> setButtonConnecting()
            StatusColor.CONNECTED -> setButtonDisconnect(state.actionButtonLabel)
        }
        binding.fabConnect.isEnabled = state.actionButtonEnabled
    }

    private fun setButtonConnect(label: String) {
        binding.fabConnect.apply {
            text = label
            icon = ContextCompat.getDrawable(context, R.drawable.ic_connect_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.primary)
            )
        }
    }

    private fun setButtonConnecting() {
        binding.fabConnect.apply {
            text = context.getString(R.string.disconnect)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.status_connecting)
            )
        }
    }

    private fun setButtonDisconnect(label: String) {
        binding.fabConnect.apply {
            text = label
            icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_fab)
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.status_error)
            )
        }
    }

    // ── Animations ─────────────────────────────────────────────────────────────

    /** Crossfade the status label when text changes. */
    private fun setStatusLabelAnimated(newText: String) {
        if (binding.tvStatusLabel.text == newText) return
        binding.tvStatusLabel.animate().cancel()
        binding.tvStatusLabel.animate()
            .alpha(0f)
            .translationY(-6f)
            .setDuration(120)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                binding.tvStatusLabel.text = newText
                binding.tvStatusLabel.translationY = 8f
                binding.tvStatusLabel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Bounce animation when a stepper dot first becomes active. */
    private fun bounceDot(dot: View) {
        dot.animate().cancel()
        dot.scaleX = 1f
        dot.scaleY = 1f
        dot.animate()
            .scaleX(1.6f)
            .scaleY(1.6f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                dot.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
            .start()
    }

    /** Animate card background color via ValueAnimator.ofArgb. */
    private fun animateCardColor(colorRes: Int) {
        val target = ContextCompat.getColor(context, colorRes)
        if (target == currentCardColor) return
        cardColorAnimator?.cancel()
        cardColorAnimator = ValueAnimator.ofArgb(currentCardColor, target).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val color = anim.animatedValue as Int
                binding.statusCard.setCardBackgroundColor(color)
                currentCardColor = color
            }
            start()
        }
    }

    /** Animate status circle fill color via GradientDrawable.setColor(). */
    private fun animateCircleColor(targetColor: Int) {
        if (targetColor == currentCircleColor) return
        circleColorAnimator?.cancel()
        val drawable = binding.statusCircle.background as? GradientDrawable
        circleColorAnimator = ValueAnimator.ofArgb(currentCircleColor, targetColor).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                currentCircleColor = anim.animatedValue as Int
                drawable?.setColor(currentCircleColor)
            }
            start()
        }
    }

    /** Pop + overshoot on status card when state changes. */
    private fun animateStatusCardPop() {
        binding.statusCard.animate().cancel()
        binding.statusCard.alpha = 0.7f
        binding.statusCard.scaleX = 0.96f
        binding.statusCard.scaleY = 0.96f
        binding.statusCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    /**
     * Pulsing rings: DecelerateInterpolator on scale (fast start, natural slow-down like ripple),
     * AccelerateInterpolator on alpha (gradual fade that speeds up). Each ring gets decreasing
     * starting alpha for depth.
     */
    private fun startRings() {
        ringAnimatorSet?.cancel()

        fun ringPulse(ring: View, delay: Long, peakAlpha: Float): Animator {
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 0f

            val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.45f).apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = DecelerateInterpolator(1.8f)
            }
            val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.45f).apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = DecelerateInterpolator(1.8f)
            }
            val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, peakAlpha, 0f).apply {
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                interpolator = AccelerateInterpolator(1.4f)
            }
            return AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 2400
                startDelay = delay
            }
        }

        ringAnimatorSet = AnimatorSet().apply {
            playTogether(
                ringPulse(binding.statusRing1, 0L, 0.70f),
                ringPulse(binding.statusRing2, 700L, 0.48f),
                ringPulse(binding.statusRing3, 1400L, 0.28f),
            )
            start()
        }
    }

    private fun stopRings() {
        ringAnimatorSet?.cancel()
        ringAnimatorSet = null
        listOf(binding.statusRing1, binding.statusRing2, binding.statusRing3).forEach { ring ->
            ring.animate().cancel()
            ring.animate().alpha(0f).setDuration(300).start()
        }
    }

    private fun performHapticOnConnect() {
        val btn = binding.fabConnect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            btn.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            @Suppress("DEPRECATION")
            btn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
