package com.smartledger.service

import android.annotation.SuppressLint
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.smartledger.MainActivity
import com.smartledger.R
import com.smartledger.util.NotificationStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 仅展示监听状态；随控制中心可见性订阅状态，不主动重绑或保活。 */
class ListenerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionJob: Job? = null
    private var listening = false
    private val permissionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (listening) return
        listening = true
        // 不使用 ACTIVE_TILE，确保每次展开控制中心都重新读取状态。
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_notification_listeners"),
            false,
            permissionObserver
        )
        connectionJob = scope.launch {
            ListenerStatus.connectionState.collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        stopObserving()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopObserving()
        scope.cancel()
        super.onDestroy()
    }

    // 旧重载仅供 Android 13 及以下使用；Android 14+ 必须走 PendingIntent 分支。
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(NotificationStyle.openAppPendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    private fun refreshTile() {
        if (!listening) return
        val tile = qsTile ?: return
        val statusText = try {
            if (!ListenerStatus.isEnabledInSettings(this)) {
                // 撤权后不保留旧的正常状态，重新授权需等待新的连接证据。
                ListenerStatus.markConnectionUnknown()
                R.string.tile_listener_disabled
            } else {
                when (ListenerStatus.connectionState.value) {
                    true -> R.string.tile_listener_connected
                    false -> R.string.tile_listener_recovering
                    null -> R.string.tile_listener_unknown
                }
            }
        } catch (e: Exception) {
            Log.w("ListenerTile", "Cannot read listener status", e)
            R.string.tile_listener_unknown
        }
        val name = getString(R.string.app_name)
        val status = getString(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.label = name
            tile.subtitle = status
        } else {
            tile.label = getString(R.string.tile_listener_description, name, status)
        }
        tile.contentDescription = getString(R.string.tile_listener_description, name, status)
        // 异常态仍可点击打开 App，不能使用会禁用点击的 STATE_UNAVAILABLE。
        tile.state = if (statusText == R.string.tile_listener_connected) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    private fun stopObserving() {
        if (!listening) return
        listening = false
        connectionJob?.cancel()
        connectionJob = null
        contentResolver.unregisterContentObserver(permissionObserver)
    }
}
