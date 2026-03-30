package com.github.swerchansky.vkturnproxy.ui.onboarding

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.swerchansky.vkturnproxy.App
import com.github.swerchansky.vkturnproxy.R
import com.github.swerchansky.vkturnproxy.databinding.FragmentOnboardingBinding
import com.github.swerchansky.vkturnproxy.navigation.Screens
import com.github.terrakok.cicerone.Router
import com.google.android.material.tabs.TabLayoutMediator
import javax.inject.Inject

class OnboardingFragment : Fragment() {

    companion object {
        fun newInstance() = OnboardingFragment()
    }

    @Inject
    lateinit var router: Router

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        (requireActivity().application as App).appComponent.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pages = listOf(
            OnboardingPage(
                R.string.onboarding_title_1,
                R.string.onboarding_desc_1,
                R.drawable.ic_nav_connect
            ),
            OnboardingPage(
                R.string.onboarding_title_2,
                R.string.onboarding_desc_2,
                R.drawable.ic_paste
            ),
            OnboardingPage(
                R.string.onboarding_title_3,
                R.string.onboarding_desc_3,
                R.drawable.ic_star
            )
        )

        val adapter = OnboardingAdapter(pages)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { _, _ -> }.attach()

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.size - 1) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener {
            finishOnboarding()
        }

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == pages.size - 1) {
                    binding.btnNext.setText(R.string.get_started)
                    binding.btnSkip.visibility = View.GONE
                } else {
                    binding.btnNext.setText(R.string.next)
                    binding.btnSkip.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun finishOnboarding() {
        requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_finished", true)
            .apply()
        router.newRootScreen(Screens.connect())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
