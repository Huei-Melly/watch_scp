package com.jay.watch_scp.ui.history

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jay.watch_scp.R
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.adapter.TransferRecordAdapter
import com.jay.watch_scp.data.model.TransferRecord
import com.jay.watch_scp.databinding.FragmentHistoryBinding
import com.jay.watch_scp.util.FontSizeUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TransferRecordAdapter
    private val records = mutableListOf<TransferRecord>()

    var onBack: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        binding.btnBack.setOnClickListener { onBack?.invoke() }
        binding.btnMenu.setOnClickListener { showMenuDialog() }
    }

    private fun setupViews() {
        adapter = TransferRecordAdapter()
        binding.recyclerHistory.layoutManager = LinearLayoutManager(context)
        binding.recyclerHistory.adapter = adapter
        binding.recyclerHistory.layoutAnimation = AnimationUtils.loadLayoutAnimation(context, R.anim.recycler_item_fade_in)

        // 加载持久化历史记录
        lifecycleScope.launch {
            val history = WatchScpApp.instance.preferencesManager.transferHistory.first()
            records.clear()
            records.addAll(history)
            updateDisplay()
        }
    }

    fun addRecord(record: TransferRecord) {
        records.add(0, record)
        updateDisplay()
    }

    private fun updateDisplay() {
        if (records.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            adapter.updateRecords(records.toList())
        }
    }

    override fun onResume() {
        super.onResume()
        // 字体大小已全局缓存，无需异步读取DataStore
        FontSizeUtils.applyToViewTree(binding.root)
    }

    private fun showMenuDialog() {
        if (!isAdded) return
        val items = arrayOf(
            getString(R.string.history_menu_clear_all),
            getString(R.string.file_op_cancel)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.file_op_menu)
            .setItems(items) { _, which ->
                when (items[which]) {
                    getString(R.string.history_menu_clear_all) -> confirmClearAll()
                    // "取消"不做任何操作
                }
            }
            .show()
    }

    private fun confirmClearAll() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_confirm_clear_all)
            .setPositiveButton(R.string.file_op_delete) { _, _ ->
                records.clear()
                updateDisplay()
                lifecycleScope.launch {
                    WatchScpApp.instance.preferencesManager.clearTransferHistory()
                }
                Toast.makeText(context, R.string.file_op_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.file_op_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}