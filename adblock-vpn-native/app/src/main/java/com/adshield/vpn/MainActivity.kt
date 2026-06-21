package com.adshield.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
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

    /** Set while we programmatically flip the switch, to ignore the callback. */
    private var suppressSwitch = false

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
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) enableProtection() else AdVpnService.stop(this)
        }

        binding.buttonUpdate.setOnClickListener { updateBlocklist() }

        observeState()
        loadBlocklistSize()
        renderLastUpdate()

        if (intent?.getBooleanExtra(EXTRA_AUTO_ENABLE, false) == true) {
            enableProtection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_ENABLE, false)) enableProtection()
    }

    private fun enableProtection() {
        maybeRequestNotificationPermission()
        val consent = VpnService.prepare(this)
        if (consent == null) {
            AdVpnService.start(this)
        } else {
            vpnPermissionLauncher.launch(consent)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun observeState() {
        // Switch + status text follow the real service state.
        lifecycleScope.launch {
            VpnState.running.collectLatest { running ->
                refreshSwitch(running)
                binding.textStatus.setText(
                    if (running) R.string.status_on else R.string.status_off,
                )
            }
        }
        lifecycleScope.launch {
            VpnState.blockListSize.collectLatest { size ->
                binding.textListSize.text = getString(R.string.list_size, size)
            }
        }
        // Live counters while the VPN is up.
        lifecycleScope.launch {
            while (true) {
                val blocked = VpnState.blockedCount.get()
                val total = VpnState.totalCount.get()
                binding.textBlocked.text = getString(R.string.blocked_stat, blocked, total)
                delay(1000)
            }
        }
    }

    private fun refreshSwitch(on: Boolean) {
        suppressSwitch = true
        binding.switchProtection.isChecked = on
        suppressSwitch = false
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
            getString(
                R.string.last_update,
                DateUtils.getRelativeTimeSpanString(last).toString(),
            )
        }
    }

    private fun updateBlocklist() {
        binding.buttonUpdate.isEnabled = false
        binding.progressUpdate.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            when (val r = BlocklistUpdater.update(this@MainActivity)) {
                is BlocklistUpdater.Result.Success -> {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_done, r.domains),
                        Toast.LENGTH_LONG,
                    ).show()
                    renderLastUpdate()
                    if (VpnState.running.value) {
                        // Apply the new list immediately by restarting the tunnel.
                        AdVpnService.stop(this@MainActivity)
                        delay(400)
                        AdVpnService.start(this@MainActivity)
                    }
                }
                is BlocklistUpdater.Result.Error ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_failed, r.message),
                        Toast.LENGTH_LONG,
                    ).show()
            }
            binding.progressUpdate.visibility = android.view.View.GONE
            binding.buttonUpdate.isEnabled = true
        }
    }

    companion object {
        const val EXTRA_AUTO_ENABLE = "auto_enable"
    }
}
