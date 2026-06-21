package com.adshield.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adshield.vpn.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Set while we programmatically change a control, to ignore its callback. */
    private var suppressCallbacks = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                AdVpnService.start(this)
            } else {
                Toast.makeText(this, R.string.consent_denied, Toast.LENGTH_SHORT).show()
                refreshSwitch(false)
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchProtection.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            if (checked) enableProtection() else AdVpnService.stop(this)
        }
        binding.buttonUpdate.setOnClickListener { updateBlocklist() }
        binding.buttonWhitelist.setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }

        buildCategoryCheckboxes()
        setupDnsSpinner()
        setupExtraControls()
        observeState()
        loadBlocklistSize()
        renderLastUpdate()

        if (intent?.getBooleanExtra(EXTRA_AUTO_ENABLE, false) == true) {
            enableProtection()
        }
        // Silent check for a newer app version on launch.
        checkAppUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // Whitelist may have changed in the other screen → reflect new count.
        loadBlocklistSize()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_ENABLE, false)) enableProtection()
    }

    // ---------------------------------------------------------------------
    // Protection toggle
    // ---------------------------------------------------------------------

    private fun enableProtection() {
        maybeRequestNotificationPermission()
        val consent = VpnService.prepare(this)
        if (consent == null) AdVpnService.start(this) else vpnPermissionLauncher.launch(consent)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Restart the tunnel so a settings change takes effect immediately. */
    private fun applyIfRunning() {
        if (!VpnState.running.value) return
        lifecycleScope.launch {
            AdVpnService.stop(this@MainActivity)
            delay(400)
            AdVpnService.start(this@MainActivity)
        }
    }

    // ---------------------------------------------------------------------
    // Categories
    // ---------------------------------------------------------------------

    private fun buildCategoryCheckboxes() {
        val container = binding.categoryContainer
        container.removeAllViews()
        val enabled = Prefs.enabledSources(this)
        for (source in BlocklistCatalog.sources) {
            val check = CheckBox(this).apply {
                text = source.title
                textSize = 16f
                isChecked = enabled.contains(source.id)
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressCallbacks) return@setOnCheckedChangeListener
                    Prefs.setSourceEnabled(this@MainActivity, source.id, isChecked)
                    applyIfRunning()
                }
            }
            val desc = TextView(this).apply {
                text = source.description
                textSize = 12f
                setTextColor(0xFF777777.toInt())
                setPadding(dp(40), 0, 0, dp(8))
            }
            container.addView(
                check,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            container.addView(desc)
        }
    }

    // ---------------------------------------------------------------------
    // Upstream DNS
    // ---------------------------------------------------------------------

    private fun setupDnsSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            DnsServers.all.map { it.title },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerDns.adapter = adapter

        suppressCallbacks = true
        binding.spinnerDns.setSelection(DnsServers.indexOfKey(Prefs.upstreamDns(this)))
        suppressCallbacks = false

        binding.spinnerDns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressCallbacks) return
                val key = DnsServers.all[pos].key
                if (key == Prefs.upstreamDns(this@MainActivity)) return // no real change
                Prefs.setUpstreamDns(this@MainActivity, key)
                applyIfRunning()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------------
    // Auto-update + extra screens + app self-update
    // ---------------------------------------------------------------------

    private fun setupExtraControls() {
        suppressCallbacks = true
        binding.switchAutoUpdate.isChecked = Prefs.autoUpdate(this)
        suppressCallbacks = false
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            Prefs.setAutoUpdate(this, checked)
            BlocklistUpdateWorker.reschedule(this)
        }
        binding.buttonCustomLists.setOnClickListener {
            startActivity(Intent(this, CustomListsActivity::class.java))
        }
        binding.buttonLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.buttonUpdateApp.setOnClickListener { checkAppUpdate(manual = true) }

        BlocklistUpdateWorker.reschedule(this)
    }

    private fun checkAppUpdate(manual: Boolean) {
        if (manual) Toast.makeText(this, R.string.update_app_checking, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = AppUpdater.check()
            if (info == null) {
                if (manual) Toast.makeText(this@MainActivity, R.string.update_app_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_app_button)
                .setMessage(getString(R.string.update_app_available, info.versionName) + "\n\n" + info.notes)
                .setPositiveButton(R.string.dialog_update) { _, _ -> downloadAndInstall(info) }
                .setNegativeButton(R.string.dialog_later, null)
                .show()
        }
    }

    private fun downloadAndInstall(info: AppUpdater.Info) {
        Toast.makeText(this, R.string.update_app_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val apk = AppUpdater.download(this@MainActivity, info)
            if (apk == null) {
                Toast.makeText(this@MainActivity, R.string.update_app_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!AppUpdater.install(this@MainActivity, apk)) {
                Toast.makeText(this@MainActivity, R.string.update_app_grant, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------------
    // State + stats
    // ---------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            VpnState.running.collectLatest { running ->
                refreshSwitch(running)
                binding.textStatus.setText(if (running) R.string.status_on else R.string.status_off)
            }
        }
        lifecycleScope.launch {
            VpnState.blockListSize.collectLatest { size ->
                binding.textListSize.text = getString(R.string.list_size, size)
            }
        }
        lifecycleScope.launch {
            while (true) {
                binding.textBlocked.text = getString(
                    R.string.blocked_stat,
                    VpnState.blockedCount.get(),
                    VpnState.totalCount.get(),
                )
                delay(1000)
            }
        }
    }

    private fun refreshSwitch(on: Boolean) {
        suppressCallbacks = true
        binding.switchProtection.isChecked = on
        suppressCallbacks = false
    }

    private fun loadBlocklistSize() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { BlockList.load(this@MainActivity).size }
            VpnState.blockListSize.value = size
        }
    }

    private fun renderLastUpdate() {
        val last = Prefs.lastUpdate(this)
        binding.textLastUpdate.text = if (last == 0L) {
            getString(R.string.last_update_never)
        } else {
            getString(R.string.last_update, DateUtils.getRelativeTimeSpanString(last).toString())
        }
    }

    private fun updateBlocklist() {
        binding.buttonUpdate.isEnabled = false
        binding.progressUpdate.visibility = View.VISIBLE
        lifecycleScope.launch {
            when (val r = BlocklistUpdater.update(this@MainActivity)) {
                is BlocklistUpdater.Result.Success -> {
                    val msg = if (r.failed.isEmpty()) {
                        getString(R.string.update_done, r.domains)
                    } else {
                        getString(R.string.update_done_partial, r.domains, r.failed.joinToString(", "))
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    renderLastUpdate()
                    applyIfRunning()
                }
                is BlocklistUpdater.Result.Error ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_failed, r.message),
                        Toast.LENGTH_LONG,
                    ).show()
            }
            binding.progressUpdate.visibility = View.GONE
            binding.buttonUpdate.isEnabled = true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AUTO_ENABLE = "auto_enable"
    }
}
