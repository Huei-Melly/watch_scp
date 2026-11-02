package com.jay.watch_scp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.jay.watch_scp.R
import com.jay.watch_scp.data.model.TransferRecord
import com.jay.watch_scp.data.model.TransferStatus
import com.jay.watch_scp.util.FileUtils
import com.jay.watch_scp.util.FontSizeUtils

class TransferRecordAdapter(
    private var records: List<TransferRecord> = emptyList()
) : RecyclerView.Adapter<TransferRecordAdapter.RecordViewHolder>() {

    fun updateRecords(newRecords: List<TransferRecord>) {
        val oldRecords = records
        if (oldRecords == newRecords) return
        records = newRecords
        val oldSize = oldRecords.size
        if (newRecords.size < oldSize) {
            // 记录减少（被清空），整体刷新
            notifyDataSetChanged()
            return
        }
        if (newRecords.size > oldSize) {
            // 新增记录：只插入新增部分
            notifyItemRangeInserted(oldSize, newRecords.size - oldSize)
        }
        // 仅刷新内容真正发生变化的条目，避免进度更新时全列表重绑
        val common = minOf(oldSize, newRecords.size)
        for (i in 0 until common) {
            if (oldRecords[i] != newRecords[i]) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_record, parent, false)
        return RecordViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        holder.bind(records[position])
    }

    override fun getItemCount() = records.size

    class RecordViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDirection: TextView = itemView.findViewById(R.id.tv_direction)
        private val tvFileName: TextView = itemView.findViewById(R.id.tv_file_name)
        private val tvFileInfo: TextView = itemView.findViewById(R.id.tv_file_info)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_status)

        init {
            FontSizeUtils.applyToTextView(tvFileName)
            FontSizeUtils.applyToTextView(tvFileInfo)
            FontSizeUtils.applyToTextView(tvStatus)
            FontSizeUtils.applyToTextView(tvDirection)
        }

        fun bind(record: TransferRecord) {
            tvDirection.text = if (record.isUpload) "\u2191" else "\u2193"
            tvDirection.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (record.isUpload) R.color.md_primary else R.color.status_success
                )
            )
            tvFileName.text = record.fileName
            tvFileInfo.text = "${FileUtils.formatFileSize(record.size)} \u00B7 ${FileUtils.formatDate(record.timestamp)}"

            val (statusText, statusColor) = when (record.status) {
                TransferStatus.COMPLETED -> "完成" to R.color.status_success
                TransferStatus.FAILED -> "失败" to R.color.status_error
                TransferStatus.PAUSED -> "暂停" to R.color.status_warning
                TransferStatus.PENDING -> "等待" to R.color.status_info
                TransferStatus.TRANSFERRING -> "${record.progress}%" to R.color.md_primary
            }
            tvStatus.text = statusText
            tvStatus.setTextColor(ContextCompat.getColor(itemView.context, statusColor))
        }
    }
}