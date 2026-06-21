package com.adshield.vpn

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adshield.vpn.databinding.ActivityLogBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows recently blocked domains with a one-tap "allow" (whitelist) action. */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply {
            title = getString(R.string.log_title)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.buttonClear.setOnClickListener {
            VpnState.clearLog()
            renderList()
        }
        renderList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderList() {
        val domains = VpnState.recentBlocked()
        binding.textEmpty.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE
        binding.listContainer.removeAllViews()
        for (domain in domains) binding.listContainer.addView(buildRow(domain))
    }

    private fun buildRow(domain: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val name = TextView(this).apply {
            text = domain
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val allow = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.log_allow)
            setOnClickListener { allowDomain(domain) }
        }
        row.addView(name)
        row.addView(allow)
        return row
    }

    private fun allowDomain(domain: String) {
        Prefs.addToWhitelist(this, domain)
        Toast.makeText(this, getString(R.string.log_allowed, domain), Toast.LENGTH_SHORT).show()
        // Apply immediately if protection is running.
        if (VpnState.running.value) {
            lifecycleScope.launch {
                AdVpnService.stop(this@LogActivity)
                delay(400)
                AdVpnService.start(this@LogActivity)
            }
        }
        renderList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
