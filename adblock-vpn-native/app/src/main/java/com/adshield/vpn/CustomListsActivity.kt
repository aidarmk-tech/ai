package com.adshield.vpn

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.adshield.vpn.databinding.ActivityCustomListsBinding
import com.google.android.material.button.MaterialButton

/** Manage user-added custom blocklist URLs. */
class CustomListsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomListsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomListsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply {
            title = getString(R.string.custom_title)
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
        val url = binding.editUrl.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.custom_bad, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.addCustomUrl(this, url)
        binding.editUrl.text?.clear()
        renderList()
    }

    private fun renderList() {
        val urls = Prefs.customUrls(this).sorted()
        binding.textEmpty.visibility = if (urls.isEmpty()) View.VISIBLE else View.GONE
        binding.listContainer.removeAllViews()
        for (url in urls) binding.listContainer.addView(buildRow(url))
    }

    private fun buildRow(url: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val name = TextView(this).apply {
            text = url
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val remove = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.whitelist_remove)
            setOnClickListener {
                Prefs.removeCustomUrl(this@CustomListsActivity, url)
                BlocklistCatalog.fileForCustom(this@CustomListsActivity, url).delete()
                renderList()
            }
        }
        row.addView(name)
        row.addView(remove)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
