package com.adshield.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that toggles the ad-blocking VPN straight from the
 * notification shade.
 *
 * First-time use needs the system VPN-consent dialog, which can only be raised
 * from an Activity, so in that case we bounce to [MainActivity] with an
 * auto-enable flag. Once consent is granted the tile toggles directly.
 */
class AdBlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (VpnState.running.value) {
            AdVpnService.stop(this)
            updateTile()
            return
        }

        val consent: Intent? = VpnService.prepare(this)
        if (consent == null) {
            // Already authorised → start right away.
            AdVpnService.start(this)
            updateTile()
        } else {
            // Need the consent dialog: open the app and ask it to enable.
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AUTO_ENABLE, true)
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val on = VpnState.running.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
