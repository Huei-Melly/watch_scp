package com.jay.watch_scp.ui.settings

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jay.watch_scp.R
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.databinding.FragmentSettingsBinding
import com.jay.watch_scp.util.CenteredDialogs
import com.jay.watch_scp.util.FontSizeUtils
import com.jay.watch_scp.util.StorageUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    var onBack: (() -> Unit)? = null
    var onNavigateToDirectoryPicker: (() -> Unit)? = null

    /** 运行时存储权限申请（替代已废弃的 requestPermissions / onRequestPermissionsResult） */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (isAdded) {
            val granted = StorageUtils.hasAllFilesAccess(requireContext())
            Toast.makeText(
                context,
                if (granted) R.string.storage_permission_granted else R.string.storage_permission_required,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupButtons()
    }

    private fun loadSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = WatchScpApp.instance.preferencesManager
            // 单次读取所有设置值，避免多次DataStore读取造成延迟
            val snapshot = prefs.getSnapshot()
            val currentFontSize = snapshot.fontSize
            FontSizeUtils.updateCachedSize(currentFontSize)
            binding.tvFontSizeValue.text = "${currentFontSize}sp"
            FontSizeUtils.applyToViewTree(binding.root)
            // 标题使用固定大字体，不受字体大小设置影响
            binding.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            binding.tvConcurrentValue.text = "${snapshot.maxConcurrent}"
            binding.tvDpiValue.text = "${snapshot.dpi}"
            binding.tvDownloadPath.text = snapshot.downloadPath

            updateThemeButtons(snapshot.themeMode == "dark")
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { onBack?.invoke() }

        binding.btnMenu.setOnClickListener { showMenuDialog() }

        binding.cardDownloadPath.setOnClickListener {
            if (isAdded) {
                onNavigateToDirectoryPicker?.invoke()
            }
        }

        binding.btnFontSizeDecrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.fontSize.first()
                if (current > 10) {
                    val newSize = current - 1
                    prefs.updateFontSize(newSize)
                    FontSizeUtils.updateCachedSize(newSize)
                    binding.tvFontSizeValue.text = "${newSize}sp"
                    // 立即应用到整个设置页面的视图树
                    FontSizeUtils.applyToViewTreeForce(binding.root)
                    // 保持标题固定大字体
                    binding.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                }
            }
        }

        binding.btnFontSizeIncrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.fontSize.first()
                if (current < 24) {
                    val newSize = current + 1
                    prefs.updateFontSize(newSize)
                    FontSizeUtils.updateCachedSize(newSize)
                    binding.tvFontSizeValue.text = "${newSize}sp"
                    // 立即应用到整个设置页面的视图树
                    FontSizeUtils.applyToViewTreeForce(binding.root)
                    // 保持标题固定大字体
                    binding.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                }
            }
        }

        binding.btnThemeDark.setOnClickListener { applyTheme(dark = true) }

        binding.btnThemeLight.setOnClickListener { applyTheme(dark = false) }

        binding.btnConcurrentDecrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.maxConcurrent.first()
                if (current > 1) {
                    prefs.updateMaxConcurrent(current - 1)
                    binding.tvConcurrentValue.text = "${current - 1}"
                }
            }
        }

        binding.btnConcurrentIncrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.maxConcurrent.first()
                if (current < 5) {
                    prefs.updateMaxConcurrent(current + 1)
                    binding.tvConcurrentValue.text = "${current + 1}"
                }
            }
        }

        // DPI按钮
        binding.btnDpiDecrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.dpi.first()
                if (current > 120) {
                    val newDpi = current - 20
                    prefs.updateDpi(newDpi)
                    applyDpiAndRecreate(newDpi)
                }
            }
        }

        binding.btnDpiIncrease.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val prefs = WatchScpApp.instance.preferencesManager
                val current = prefs.dpi.first()
                if (current < 320) {
                    val newDpi = current + 20
                    prefs.updateDpi(newDpi)
                    applyDpiAndRecreate(newDpi)
                }
            }
        }
    }

    /**
     * 切换主题：持久化保存并立即通过 AppCompatDelegate 全局生效（重启后依然保持）
     */
    private fun applyTheme(dark: Boolean) {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            WatchScpApp.instance.preferencesManager.updateThemeMode(if (dark) "dark" else "light")
            updateThemeButtons(dark)
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun showMenuDialog() {
        if (!isAdded) return
        val items = mutableListOf(getString(R.string.storage_permission_menu))
        val hasAccess = StorageUtils.hasAllFilesAccess(requireContext())
        if (hasAccess) items[0] = getString(R.string.storage_permission_granted)
        items.add(getString(R.string.settings_reset_default))
        items.add(getString(R.string.file_op_cancel))
        CenteredDialogs.showOptions(
            requireContext(),
            getString(R.string.file_op_menu),
            items.toTypedArray()
        ) { which ->
            when (items[which]) {
                getString(R.string.storage_permission_menu),
                getString(R.string.storage_permission_granted) -> requestStoragePermission()
                getString(R.string.settings_reset_default) -> resetToDefaults()
                // "取消"不做任何操作
            }
        }
    }

    /** 打开「所有文件访问权限」设置（低版本/旧版存储模式使用运行时权限申请） */
    private fun requestStoragePermission() {
        if (!isAdded) return
        if (StorageUtils.isLegacyStorageMode()) {
            val pending = StorageUtils.pendingRuntimeStoragePermissions(requireContext())
            if (pending.isNotEmpty()) {
                storagePermissionLauncher.launch(pending)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!StorageUtils.openAllFilesAccessSettings(requireContext())) {
                Toast.makeText(context, R.string.storage_permission_failed, Toast.LENGTH_LONG).show()
                StorageUtils.showAdbGrantDialog(requireContext())
            }
        } else {
            val permissions = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            storagePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun resetToDefaults() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = WatchScpApp.instance.preferencesManager
            // 重置字体大小
            prefs.updateFontSize(14)
            FontSizeUtils.updateCachedSize(14)
            // 重置主题
            prefs.updateThemeMode("dark")
            // 重置最大并发数
            prefs.updateMaxConcurrent(1)
            // 重置DPI
            prefs.updateDpi(160)
            WatchScpApp.instance.updateCachedDpi(160)
            // 刷新UI
            loadSettings()
            // 使主题与DPI全局生效
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            activity?.recreate()
        }
    }

    private fun updateThemeButtons(isDark: Boolean) {
        if (!isAdded) return
        if (isDark) {
            binding.btnThemeDark.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.md_primary)
                )
            )
            binding.btnThemeLight.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.md_surface_variant)
                )
            )
        } else {
            binding.btnThemeLight.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.md_primary)
                )
            )
            binding.btnThemeDark.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.md_surface_variant)
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次页面可见时重新加载设置，确保从其他页面返回后显示最新值
        loadSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyDpiAndRecreate(dpi: Int) {
        if (!isAdded) return
        WatchScpApp.instance.updateCachedDpi(dpi)
        try {
            val config = Configuration(requireContext().resources.configuration)
            config.densityDpi = dpi
            @Suppress("DEPRECATION")
            requireContext().resources.updateConfiguration(config, requireContext().resources.displayMetrics)
        } catch (_: Exception) {}
        // 重建Activity使所有页面生效
        activity?.recreate()
    }
}
