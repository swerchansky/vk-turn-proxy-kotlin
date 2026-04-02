package com.github.swerchansky.vkturnproxy.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.ActivityMainBinding
import com.github.swerchansky.vkturnproxy.navigation.Screens
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.github.terrakok.cicerone.NavigatorHolder
import com.github.terrakok.cicerone.Router
import com.github.terrakok.cicerone.androidx.AppNavigator
import com.github.terrakok.cicerone.androidx.FragmentScreen
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainActivity : AppCompatActivity() {

    @Inject lateinit var navigatorHolder: NavigatorHolder
    @Inject lateinit var router: Router

    private lateinit var binding: ActivityMainBinding

    private val navigator by lazy {
        object : AppNavigator(this, R.id.container) {
            override fun setupFragmentTransaction(
                screen: FragmentScreen,
                fragmentTransaction: FragmentTransaction,
                currentFragment: Fragment?,
                nextFragment: Fragment,
            ) {
                fragmentTransaction.setCustomAnimations(
                    R.anim.fade_in,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.fade_out,
                )
                
                // Hide bottom nav on onboarding
                val isOnboarding = nextFragment is com.github.swerchansky.vkturnproxy.ui.onboarding.OnboardingFragment
                binding.bottomNav.isVisible = !isOnboarding
            }
        }
    }

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this, (application as App).appComponent.viewModelFactory())[MainViewModel::class.java]
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // Link received from share intent — passed to ConnectFragment after navigation
    var pendingSharedLink: String? = null
    // (link, peer?) received from deep link — passed to ConnectFragment after navigation
    var pendingDeepLink: Pair<String, String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).appComponent.inject(this)
        super.onCreate(savedInstanceState)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        applySystemBarAppearance()

        if (savedInstanceState == null) {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("onboarding_finished", false)) {
                router.replaceScreen(Screens.connect())
            } else {
                router.replaceScreen(Screens.onboarding())
            }
        }

        setupBottomNav()
        setupBadge()
        handleShareIntent(intent)
        handleDeepLink(intent)
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "vkturnproxy" || uri.host != "connect") return
        val link = uri.getQueryParameter("link") ?: return
        val peer = uri.getQueryParameter("peer")
        pendingDeepLink = Pair(link, peer)
        binding.bottomNav.selectedItemId = R.id.nav_connect
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            pendingSharedLink = text
            binding.bottomNav.selectedItemId = R.id.nav_connect
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> router.replaceScreen(Screens.connect())
                R.id.nav_logs -> {
                    clearLogsBadge()
                    router.replaceScreen(Screens.logs())
                }
                R.id.nav_settings -> router.replaceScreen(Screens.settings())
            }
            true
        }
    }

    private fun setupBadge() {
        var prevLineCount = 0
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logLineCount.collect { lineCount ->
                    val newLines = (lineCount - prevLineCount).coerceAtLeast(0)
                    if (newLines > 0 && binding.bottomNav.selectedItemId != R.id.nav_logs) {
                        val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_logs)
                        badge.isVisible = true
                        badge.number = badge.number + newLines
                    }
                    prevLineCount = lineCount
                }
            }
        }
    }

    private fun clearLogsBadge() {
        binding.bottomNav.getBadge(R.id.nav_logs)?.let {
            it.isVisible = false
            it.clearNumber()
        }
    }

    private fun applySystemBarAppearance() {
        val isLight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_NO
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Container gets top inset (status bar), bottom nav gets bottom inset (nav bar)
            binding.container.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        navigatorHolder.setNavigator(navigator)
    }

    override fun onPause() {
        navigatorHolder.removeNavigator()
        super.onPause()
    }
}
