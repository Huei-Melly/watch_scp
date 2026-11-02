package com.jay.watch_scp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.jay.watch_scp.data.local.PreferencesManager
import com.jay.watch_scp.data.network.SftpClient
import com.jay.watch_scp.data.transfer.TransferManager
import com.jay.watch_scp.util.FontSizeUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class WatchScpApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    val sftpClient: SftpClient by lazy { SftpClient(cacheDir) }

    /** 应用级传输队列，界面切换时任务不中断 */
    val transferManager: TransferManager by lazy {
        TransferManager(this, sftpClient, preferencesManager)
    }

    /** 启动时同步加载的DPI值，供MainActivity同步使用 */
    var cachedDpi: Int = 160
        private set

    /** 用户手动断开连接后，再次进入远程连接页必须重新输入服务器信息 */
    var requiresFreshCredentials: Boolean = false
        private set

    fun updateCachedDpi(dpi: Int) {
        cachedDpi = dpi
    }

    /** 连接成功后清除「需重新输入」标记 */
    fun markConnected() {
        requiresFreshCredentials = false
    }

    /** 手动断开连接，下次连接页不预填旧服务器信息 */
    fun markManualDisconnect() {
        requiresFreshCredentials = true
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)

        // 同步加载初始设置，确保UI渲染前已有正确值
        // runBlocking在Application.onCreate中调用（Activity尚未创建）不会阻塞UI
        runBlocking {
            val prefs = preferencesManager

            // 1. 加载字体大小到缓存
            val fontSize = prefs.fontSize.first()
            FontSizeUtils.updateCachedSize(fontSize)

            // 2. 加载DPI
            cachedDpi = prefs.dpi.first()

            // 3. 加载并应用主题（全局生效，重启后保持）
            val savedTheme = prefs.themeMode.first()
            AppCompatDelegate.setDefaultNightMode(
                when (savedTheme) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    companion object {
        lateinit var instance: WatchScpApp
            private set
    }

    /**
     * 应用被系统回收或网络切换后恢复连接：
     * 使用最近一次成功连接的服务器凭据静默重连。
     * 用户手动断开后不再自动恢复。
     */
    suspend fun restoreConnectionIfPossible(): Boolean {
        if (sftpClient.isConnected) return true
        if (requiresFreshCredentials) return false
        val servers = preferencesManager.savedServers.first()
        val last = servers.maxByOrNull { it.lastConnected } ?: return false
        if (last.address.isBlank() || last.username.isBlank() || last.password.isEmpty()) return false
        return sftpClient.connect(last.address, last.port, last.username, last.password).isSuccess
    }
}
