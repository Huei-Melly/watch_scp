package com.jay.watch_scp.ui.transfer

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
import com.jay.watch_scp.data.model.TransferStatus
import com.jay.watch_scp.databinding.FragmentTransferBinding
import com.jay.watch_scp.util.CenteredDialogs
import com.jay.watch_scp.util.FontSizeUtils
import kotlinx.coroutines.launch

class TransferFragment : Fragment() {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!

    private lateinit var queueAdapter: TransferRecordAdapter

    var onBack: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        binding.btnBack.setOnClickListener { onBack?.invoke() }
        binding.btnMenu.setOnClickListener { showMenuDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            WatchScpApp.instance.transferManager.records.collect { renderRecords(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        FontSizeUtils.applyToViewTree(binding.root)
    }

    private fun setupViews() {
        queueAdapter = TransferRecordAdapter()
        binding.recyclerQueue.layoutManager = LinearLayoutManager(context)
        binding.recyclerQueue.adapter = queueAdapter
        binding.recyclerQueue.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(context, R.anim.recycler_item_fade_in)
    }

    /** 加入下载任务（兼容旧调用，任务由应用级队列执行） */
    fun addDownload(remotePath: String, fileName: String) {
        WatchScpApp.instance.transferManager.enqueueDownload(remotePath, fileName)
    }

    /** 加入上传任务（兼容旧调用，任务由应用级队列执行） */
    fun addUpload(localPath: String, remotePath: String, fileName: String): Boolean {
        return WatchScpApp.instance.transferManager.enqueueUpload(localPath, remotePath, fileName)
    }

    private fun renderRecords(records: List<TransferRecord>) {
        if (!isAdded) return
        // 完成/失败提示统一由 TransferManager 在任意界面弹出，这里只刷新队列展示

        val active = records.firstOrNull { it.status == TransferStatus.TRANSFERRING }
            ?: records.firstOrNull { it.status == TransferStatus.PENDING }
        updateActiveTransfer(active)

        if (records.isNotEmpty()) {
            binding.tvQueueLabel.visibility = View.VISIBLE
            binding.recyclerQueue.visibility = View.VISIBLE
            queueAdapter.updateRecords(records)
        } else {
            binding.tvQueueLabel.visibility = View.GONE
            binding.recyclerQueue.visibility = View.GONE
        }
    }

    private fun updateActiveTransfer(record: TransferRecord?) {
        if (!isAdded) return
        if (record == null) {
            binding.layoutActiveTransfer.visibility = View.GONE
            binding.tvNoActive.visibility = View.VISIBLE
            binding.tvError.visibility = View.GONE
        } else {
            binding.layoutActiveTransfer.visibility = View.VISIBLE
            binding.tvNoActive.visibility = View.GONE
            binding.progressActive.progress = record.progress
            binding.tvProgressPercent.text = "${record.progress}%"
            binding.tvActiveStatus.text = if (record.isUpload) {
                getString(R.string.transfer_uploading)
            } else {
                getString(R.string.transfer_downloading)
            }
            binding.tvActiveFilename.text = record.fileName
            if (record.errorMessage != null) {
                binding.tvError.text = record.errorMessage
                binding.tvError.visibility = View.VISIBLE
            } else {
                binding.tvError.visibility = View.GONE
            }
        }
    }

    private fun showMenuDialog() {
        if (!isAdded) return
        val items = arrayOf(
            getString(R.string.transfer_menu_delete_all),
            getString(R.string.file_op_cancel)
        )
        CenteredDialogs.showOptions(
            requireContext(),
            getString(R.string.file_op_menu),
            items
        ) { which ->
            when (items[which]) {
                getString(R.string.transfer_menu_delete_all) -> confirmDeleteAll()
            }
        }
    }

    private fun confirmDeleteAll() {
        if (!isAdded) return
        CenteredDialogs.showConfirm(
            requireContext(),
            getString(R.string.transfer_confirm_delete_all),
            getString(R.string.transfer_confirm_delete_all),
            getString(R.string.file_op_delete),
            getString(R.string.file_op_cancel)
        ) {
            WatchScpApp.instance.transferManager.clearAll()
            if (isAdded) Toast.makeText(context, R.string.file_op_success, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
