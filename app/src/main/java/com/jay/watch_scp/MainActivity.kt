package com.jay.watch_scp

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jay.watch_scp.databinding.ActivityMainBinding
import com.jay.watch_scp.util.StorageUtils
import kotlinx.coroutines.launch
import com.jay.watch_scp.ui.browse.LocalBrowseFragment
import com.jay.watch_scp.ui.browse.RemoteBrowseFragment
import com.jay.watch_scp.ui.connect.ConnectFragment
import com.jay.watch_scp.ui.history.HistoryFragment
import com.jay.watch_scp.ui.home.HomeFragment
import com.jay.watch_scp.ui.settings.SettingsFragment
import com.jay.watch_scp.ui.transfer.TransferFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // 左滑返回手势检测
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwiping = false          // 是否处于可能的左滑状态（从左侧边缘起始）
    private var isSwipingConsumed = false  // 是否已确认是左滑手势（MOVE超过阈值）
    private val swipeThresholdDp = 64f     // 最小滑动距离（dp）
    private val edgeSwipeZone = 40f        // 左边缘触发区域宽度（dp→px在运行时计算，避免误触发）
    private var edgeZonePx = 0f
    private var swipeThresholdPx = 0f

    /** 传输提示节流，避免批量任务弹出大量 Toast */
    private var lastTransferToastAt = 0L

    /** 跳转传输页节流，避免批量任务连续触发页面切换 */
    private var lastTransferNavigateAt = 0L

    /** 导航防抖：标记是否有pending的fragment事务 */
    private var isNavigationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WatchScp)

        // 计算边缘触发区域 / 滑动阈值像素值
        edgeZonePx = edgeSwipeZone * resources.displayMetrics.density
        swipeThresholdPx = swipeThresholdDp * resources.displayMetrics.density

        // 在setContentView之前应用DPI，确保UI渲染时已使用正确DPI
        try {
            val savedDpi = WatchScpApp.instance.cachedDpi
            val config = Configuration(resources.configuration)
            config.densityDpi = savedDpi
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        } catch (_: Exception) {}

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Fullscreen setup: edge-to-edge content
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        checkPermissions()

        if (savedInstanceState == null) {
            showHomeScreen(backAnim = false)
        } else {
            // 重新绑定回调，确保Activity重建后所有导航回调正常工作
            reattachFragmentCallbacks()
        }
    }

    private fun reattachFragmentCallbacks() {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        when (fragment) {
            is HomeFragment -> {
                fragment.onNavigateToRemote = { openRemoteEntry() }
                fragment.onNavigateToLocal = { showLocalBrowse() }
                fragment.onNavigateToTransfer = { showTransfer() }
                fragment.onNavigateToHistory = { showHistory() }
                fragment.onNavigateToSettings = { showSettings() }
            }
            is ConnectFragment -> {
                fragment.startFresh = WatchScpApp.instance.requiresFreshCredentials
                fragment.onBack = { showHomeScreen(backAnim = true) }
                fragment.onConnected = { showRemoteBrowse() }
            }
            is RemoteBrowseFragment -> {
                fragment.onBack = { showHomeScreen(backAnim = true) }
                fragment.onDownload = { path, name -> startDownload(path, name) }
                fragment.onUploadToRemote = { localPath, remotePath, fileName ->
                    startUpload(localPath, remotePath, fileName)
                }
                fragment.onDisconnect = { handleRemoteDisconnect() }
                fragment.onPickLocalFileForUpload = { remotePath -> showLocalFilePicker(remotePath) }
            }
            is LocalBrowseFragment -> {
                when {
                    fragment.isDirectoryPickerMode -> {
                        fragment.onBack = { showSettings(backAnim = true) }
                        fragment.onDirectorySelected = { path ->
                            lifecycleScope.launch {
                                WatchScpApp.instance.preferencesManager.updateDownloadPath(path)
                            }
                            showSettings()
                        }
                    }
                    fragment.isFilePickerMode -> {
                        val remotePath = fragment.remotePathForUpload
                        fragment.onBack = { showRemoteBrowse(remotePath) }
                        fragment.onFileSelected = { localPath, fileName ->
                            showRemoteBrowse(remotePath)
                            startUpload(localPath, joinPath(remotePath, fileName), fileName)
                        }
                    }
                    else -> {
                        fragment.onBack = { showHomeScreen(backAnim = true) }
                        fragment.onUpload = { path, name -> startUpload(path, "/$name", name) }
                    }
                }
            }
            is SettingsFragment -> {
                setupSettingsCallbacks(fragment)
            }
            is TransferFragment -> {
                fragment.onBack = { showHomeScreen(backAnim = true) }
            }
            is HistoryFragment -> {
                fragment.onBack = { showHomeScreen(backAnim = true) }
            }
        }
    }

    private fun showHomeScreen(backAnim: Boolean = false) {
        val fragment = HomeFragment().apply {
            onNavigateToRemote = { openRemoteEntry() }
            onNavigateToLocal = { showLocalBrowse() }
            onNavigateToTransfer = { showTransfer() }
            onNavigateToHistory = { showHistory() }
            onNavigateToSettings = { showSettings() }
        }
        if (backAnim) replaceFragmentBack(fragment) else replaceFragment(fragment)
    }

    /** 已连接时直接进入远程文件列表，避免每次都要重新填写连接信息 */
    private fun openRemoteEntry() {
        if (WatchScpApp.instance.sftpClient.isConnected) {
            showRemoteBrowse()
        } else {
            showRemoteConnect()
        }
    }

    private fun showRemoteConnect(backAnim: Boolean = false, fresh: Boolean = false) {
        val fragment = ConnectFragment().apply {
            startFresh = fresh || WatchScpApp.instance.requiresFreshCredentials
            onBack = { showHomeScreen(backAnim = true) }
            onConnected = { showRemoteBrowse() }
        }
        if (backAnim) replaceFragmentBack(fragment) else replaceFragment(fragment)
    }

    private fun showRemoteBrowse(initialPath: String = "/") {
        val fragment = RemoteBrowseFragment().apply {
            this.initialPath = initialPath
            onBack = { showHomeScreen(backAnim = true) }
            onDownload = { path, name -> startDownload(path, name) }
            onUploadToRemote = { localPath, remotePath, fileName ->
                startUpload(localPath, remotePath, fileName)
            }
            onDisconnect = { handleRemoteDisconnect() }
            onPickLocalFileForUpload = { remotePath -> showLocalFilePicker(remotePath) }
        }
        replaceFragment(fragment)
    }

    /**
     * 远程文件上传时进入本应用内的本地文件浏览页，保持整个应用统一的浏览体验。
     * 选好文件后回到原远程目录并启动上传任务。
     */
    private fun showLocalFilePicker(remotePath: String) {
        val fragment = LocalBrowseFragment().apply {
            isFilePickerMode = true
            remotePathForUpload = remotePath
            onBack = { showRemoteBrowse(remotePath) }
            onFileSelected = { localPath, fileName ->
                showRemoteBrowse(remotePath)
                startUpload(localPath, joinPath(remotePath, fileName), fileName)
            }
        }
        replaceFragment(fragment)
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    /** 断开 SFTP 连接并回到连接页，下次连接需重新输入服务器信息 */
    private fun handleRemoteDisconnect() {
        lifecycleScope.launch {
            WatchScpApp.instance.sftpClient.disconnect()
            WatchScpApp.instance.markManualDisconnect()
            if (isFinishing) return@launch
            showRemoteConnect(fresh = true)
        }
    }

    private fun showLocalBrowse() {
        val fragment = LocalBrowseFragment().apply {
            isDirectoryPickerMode = false
            onBack = { showHomeScreen(backAnim = true) }
            onDirectorySelected = null
            onUpload = { path, name -> startUpload(path, "/$name", name) }
        }
        replaceFragment(fragment)
    }

    /** 下载任务交给应用级传输队列，界面切换不会中断 */
    private fun startDownload(remotePath: String, fileName: String) {
        WatchScpApp.instance.transferManager.enqueueDownload(remotePath, fileName)
        showTransferToast()
        openTransferOnce()
    }

    /** 上传任务交给应用级传输队列，支持普通路径和 content:// URI */
    private fun startUpload(localPath: String, remotePath: String, fileName: String) {
        val queued = WatchScpApp.instance.transferManager.enqueueUpload(localPath, remotePath, fileName)
        if (queued) {
            showTransferToast()
            openTransferOnce()
        } else {
            Toast.makeText(this, "文件不存在或不是普通文件", Toast.LENGTH_SHORT).show()
        }
    }

    /** 批量任务只触发一次页面切换，避免多次 replace 造成闪烁 */
    private fun openTransferOnce() {
        val now = System.currentTimeMillis()
        if (now - lastTransferNavigateAt < 1200) return
        lastTransferNavigateAt = now
        showTransfer()
    }

    /** 批量传输时避免连续弹出大量重复提示 */
    private fun showTransferToast() {
        val now = System.currentTimeMillis()
        if (now - lastTransferToastAt < 1200) return
        lastTransferToastAt = now
        Toast.makeText(this, R.string.transfer_added_to_queue, Toast.LENGTH_SHORT).show()
    }

    private fun showDirectoryPicker() {
        val fragment = LocalBrowseFragment().apply {
            isDirectoryPickerMode = true
            onBack = { showSettings() }
            onDirectorySelected = { path ->
                lifecycleScope.launch {
                    WatchScpApp.instance.preferencesManager.updateDownloadPath(path)
                }
                showSettings()
            }
        }
        replaceFragment(fragment)
    }

    private fun showTransfer() {
        replaceFragment(TransferFragment().apply {
            onBack = { showHomeScreen(backAnim = true) }
        })
    }

    private fun showHistory() {
        replaceFragment(HistoryFragment().apply {
            onBack = { showHomeScreen(backAnim = true) }
        })
    }

    private fun showSettings(backAnim: Boolean = false) {
        val fragment = SettingsFragment()
        setupSettingsCallbacks(fragment, backAnim)
        if (backAnim) replaceFragmentBack(fragment) else replaceFragment(fragment)
    }

    private fun setupSettingsCallbacks(fragment: SettingsFragment, backAnim: Boolean = false) {
        fragment.onBack = {
            if (backAnim) showHomeScreen(backAnim = true) else showHomeScreen()
        }
        fragment.onNavigateToDirectoryPicker = { showDirectoryPicker() }
    }

    private fun replaceFragment(fragment: Fragment) {
        // 防重复导航：有pending事务或已是目标Fragment则跳过
        if (isNavigationPending) return
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.javaClass == fragment.javaClass) return
        isNavigationPending = true
        // 前进导航：新页面从右侧滑入，旧页面向左侧滑出
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
            .replace(R.id.fragment_container, fragment)
            .commit()
        // 延迟释放pending标志（略大于动画时长确保事务已提交）
        binding.root.postDelayed({ isNavigationPending = false }, 150)
    }

    /** 后退导航：新页面从左侧滑入，旧页面向右侧滑出 */
    private fun replaceFragmentBack(fragment: Fragment) {
        if (isNavigationPending) return
        if (supportFragmentManager.findFragmentById(R.id.fragment_container)?.javaClass == fragment.javaClass) return
        isNavigationPending = true
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
            .replace(R.id.fragment_container, fragment)
            .commit()
        binding.root.postDelayed({ isNavigationPending = false }, 150)
    }

    private fun checkPermissions() {
        // 旧版存储模式（targetSdk<=29）下只要运行时读权限就能浏览全部文件，
        // 不需要也不应该再去找系统里不存在的「所有文件访问」设置页
        if (StorageUtils.isLegacyStorageMode()) {
            val pending = StorageUtils.pendingRuntimeStoragePermissions(this)
            if (pending.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, pending, PERMISSION_REQUEST_CODE)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!StorageUtils.hasAllFilesAccess(this)) {
                showAllFilesPermissionDialog()
            }
            return
        }
        // Android 10 及以下：申请传统存储权限
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    /** 引导用户授予「所有文件访问权限」，手表设置无该入口时提供 ADB 方案 */
    private fun showAllFilesPermissionDialog() {
        if (permissionDialogShown || isFinishing) return
        permissionDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_permission_title)
            .setMessage(R.string.storage_permission_message)
            .setPositiveButton(R.string.storage_permission_grant) { _, _ ->
                if (!StorageUtils.openAllFilesAccessSettings(this)) {
                    Toast.makeText(this, R.string.storage_permission_failed, Toast.LENGTH_LONG).show()
                    StorageUtils.showAdbGrantDialog(this)
                }
            }
            .setNeutralButton(R.string.storage_permission_copy_adb) { _, _ ->
                StorageUtils.showAdbGrantDialog(this)
            }
            .setNegativeButton(R.string.storage_permission_later, null)
            .show()
    }

    /**
     * 左滑返回手势检测（仅左边缘触发，实时跟手滑动）
     * 在触摸事件分发到子视图之前先检测滑动手势
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isSwipingConsumed = false
                // 从左侧边缘开始才可能触发左滑
                isSwiping = event.x < edgeZonePx
                // 清除上次可能残留的translation
                if (binding.contentContainer.translationX != 0f) {
                    binding.contentContainer.animate().cancel()
                    binding.contentContainer.translationX = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSwiping && !isNavigationPending) {
                    val dx = event.x - touchStartX
                    val dy = Math.abs(event.y - touchStartY)
                    // 水平向右滑动明显大于垂直位移 → 确认是右滑返回手势
                    if (dx > 0 && dx > dy * 1.5f) {
                        if (!isSwipingConsumed) {
                            // 手势确认后立即给子视图发送 CANCEL，避免列表在返回动画期间继续滚动/fling
                            val cancel = MotionEvent.obtain(event)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                        isSwipingConsumed = true
                        // 实时跟手：内容层向右平移，根布局固定不动以露出纯色背景，避免残影
                        val maxTranslation = binding.contentContainer.width * 0.75f
                        binding.contentContainer.translationX = dx.coerceAtMost(maxTranslation)
                        // 手势已被拦截，后续 MOVE 不再下发给子视图，避免列表抖动
                        return true
                    } else if (dy > dx && !isSwipingConsumed) {
                        // 垂直滑动为主，退出左滑状态（允许RecyclerView正常滚动）
                        isSwiping = false
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指落下时取消手势，避免第二根手指导致画面卡在位移状态
                if (isSwipingConsumed) {
                    isSwiping = false
                    isSwipingConsumed = false
                    binding.contentContainer.animate()
                        .translationX(0f)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipingConsumed) {
                    val dx = event.x - touchStartX
                    isSwiping = false
                    isSwipingConsumed = false
                    val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
                    if (dx > swipeThresholdPx && !isNavigationPending) {
                        if (current is HomeFragment) {
                            // 首页右滑退出：内容继续滑出后结束
                            binding.contentContainer.animate()
                                .translationX(binding.contentContainer.width.toFloat())
                                .setDuration(150)
                                .setInterpolator(DecelerateInterpolator())
                                .withEndAction {
                                    finish()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        overridePendingTransition(0, 0)
                                    }
                                }
                                .start()
                        } else {
                            // 关键：内容层归位与 Fragment 后退转场同时进行，
                            // 不再先复位再翻页，消除右滑返回的回弹跳变与重影
                            binding.contentContainer.animate()
                                .translationX(0f)
                                .setDuration(150)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                            handleSwipeBack()
                        }
                    } else {
                        // 未超过阈值 → 平滑回弹复位
                        binding.contentContainer.animate()
                            .translationX(0f)
                            .setDuration(140)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    return true
                }
                isSwiping = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isSwipingConsumed) {
                    // 触摸被取消（如收到来电通知）→ 复位
                    binding.contentContainer.animate()
                        .translationX(0f)
                        .setDuration(140)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    isSwiping = false
                    isSwipingConsumed = false
                    return true
                }
                isSwiping = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * 根据当前页面执行返回操作
     * 主界面 → 退出应用，其他页面 → 返回上一页
     * 使用后退导航动画（从左侧滑入/向右侧滑出）
     */
    private fun handleSwipeBack() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        when (currentFragment) {
            is HomeFragment -> {
                // 退出应用时手动滑动动画（与Fragment过渡动画时长统一为120ms）
                binding.contentContainer.animate()
                    .translationX(binding.contentContainer.width.toFloat())
                    .setDuration(120)
                    .withEndAction {
                        finish()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                        } else {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }
                    }
                    .start()
                return
            }
            is ConnectFragment -> showHomeScreen(backAnim = true)
            is RemoteBrowseFragment -> showRemoteConnect(backAnim = true)
            is LocalBrowseFragment -> {
                when {
                    currentFragment.isDirectoryPickerMode -> showSettings(backAnim = true)
                    currentFragment.isFilePickerMode ->
                        showRemoteBrowse(currentFragment.remotePathForUpload)
                    else -> showHomeScreen(backAnim = true)
                }
            }
            is TransferFragment -> showHomeScreen(backAnim = true)
            is HistoryFragment -> showHomeScreen(backAnim = true)
            is SettingsFragment -> showHomeScreen(backAnim = true)
        }
        // Fragment 事务提交后不阻塞主线程，动画由系统异步播放
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001

        /** 每次应用启动只自动弹出一次授权提示 */
        private var permissionDialogShown = false
    }
}
