package com.jay.watch_scp.ui.home

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.jay.watch_scp.BuildConfig
import com.jay.watch_scp.R
import com.jay.watch_scp.databinding.FragmentHomeBinding
import com.jay.watch_scp.util.FontSizeUtils

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    var onNavigateToRemote: (() -> Unit)? = null
    var onNavigateToLocal: (() -> Unit)? = null
    var onNavigateToTransfer: (() -> Unit)? = null
    var onNavigateToHistory: (() -> Unit)? = null
    var onNavigateToSettings: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRemote.setOnClickListener { onNavigateToRemote?.invoke() }
        binding.btnLocal.setOnClickListener { onNavigateToLocal?.invoke() }
        binding.btnTransfer.setOnClickListener { onNavigateToTransfer?.invoke() }
        binding.btnHistory.setOnClickListener { onNavigateToHistory?.invoke() }
        binding.btnSettings.setOnClickListener { onNavigateToSettings?.invoke() }
        binding.tvVersion.text = getString(R.string.home_version, BuildConfig.VERSION_NAME)
        // 首次设置固定字体大小
        applyFixedFontSizes()
    }

    override fun onResume() {
        super.onResume()
        // 字体大小已在Application.onCreate中同步加载到缓存，直接应用
        FontSizeUtils.applyToViewTree(binding.root)
        // 每次返回时重新应用固定字体大小，确保设置按钮⚙始终为50sp不受字体设置影响
        applyFixedFontSizes()
    }

    /** 应用固定字体大小（标题、设置按钮、功能按钮均不受全局字体设置影响） */
    private fun applyFixedFontSizes() {
        // 设置按钮⚙图标使用固定大字号，不随全局字体缩放变化
        binding.btnSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        // 标题使用固定大字体
        binding.tvAppTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        // 4个功能按钮使用固定字号
        binding.btnRemote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        binding.btnLocal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        binding.btnTransfer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        binding.btnHistory.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        // 底部信息固定字号，避免全局字体缩放撑破圆形屏幕
        binding.tvAuthor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        binding.tvVersion.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        binding.tvDeclaration.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}