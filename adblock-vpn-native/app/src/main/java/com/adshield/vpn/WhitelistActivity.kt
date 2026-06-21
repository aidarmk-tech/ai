package com.adshield.vpn

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adshield.vpn.databinding.ActivityWhitelistBinding
import com.google.android.material.button.MaterialButton

/** Manage the list of always-allowed (never blocked) domains. */
class WhitelistActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhitelistBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.whitelist_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.buttonAdd.setOnClickListener { addCurrent() }
        renderList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun addCurrent() {
        val domain = Prefs.normalize(binding.editDomain.text.toString())
        if (domain == null) {
            Toast.makeText(this, R.string.whitelist_bad, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.addToWhitelist(this, domain)
        binding.editDomain.text?.clear()
        renderList()
    }

    private fun renderList() {
        val domains = Prefs.whitelist(this).sorted()
        binding.textEmpty.visibility = if (domains.isEmpty()) View.VISIBLE else View.GONE
        binding.listContainer.removeAllViews()
        for (domain in domains) {
            binding.listContainer.addView(buildRow(domain))
        }
    }

    private fun buildRow(domain: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val name = TextView(this).apply {
            text = domain
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        val remove = MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.whitelist_remove)
            setOnClickListener {
                Prefs.removeFromWhitelist(this@WhitelistActivity, domain)
                renderList()
            }
        }
        row.addView(name)
        row.addView(remove)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
