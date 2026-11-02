package com.jay.watch_scp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.recyclerview.widget.RecyclerView
import com.jay.watch_scp.R
import com.jay.watch_scp.data.model.FileItem
import com.jay.watch_scp.util.FileUtils
import com.jay.watch_scp.util.FontSizeUtils
import com.jay.watch_scp.util.ThemeUtils

class FileListAdapter(
    private var files: List<FileItem> = emptyList(),
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: ((FileItem) -> Unit)? = null
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    /** 多选模式标志 */
    private var isMultiSelectMode: Boolean = false

    /** 已选中项的位置集合 */
    private val selectedPositions: MutableSet<Int> = mutableSetOf()

    /** 选中状态变化回调（用于界面同步计数与操作栏） */
    var onSelectionChanged: (() -> Unit)? = null

    init {
        // 稳定 id 让 RecyclerView 在滚动/刷新时更好地复用与避免整表重绑
        setHasStableIds(true)
    }

    /** 使用路径作为稳定 id */
    override fun getItemId(position: Int): Long = files[position].path.hashCode().toLong()

    /** 获取当前目录中排除 ".." 的所有文件列表 */
    fun getFiles(): List<FileItem> = files.filter { it.name != ".." }

    /** 获取选中的文件列表 */
    fun getSelectedFiles(): List<FileItem> =
        selectedPositions.map { files[it] }.filter { it.name != ".." }

    /** 是否处于多选模式 */
    fun isAllSelectedMode(): Boolean = isMultiSelectMode

    /** 获取当前选中位置集合 */
    fun getSelectedPositions(): Set<Int> = selectedPositions.toSet()

    /** 是否全部选中（排除..） */
    fun isAllSelected(): Boolean {
        val selectable = files.filter { it.name != ".." }
        return isMultiSelectMode && selectedPositions.size >= selectable.size && selectable.isNotEmpty()
    }

    /** 进入多选模式，选中指定位置 */
    fun enterMultiSelectMode(position: Int) {
        if (position < 0 || position >= files.size) return
        if (files[position].name == "..") return
        isMultiSelectMode = true
        selectedPositions.add(position)
        // 刷新所有可见项，确保所有条目都显示复选框
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged?.invoke()
    }

    /** 退出多选模式 */
    fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedPositions.clear()
        // 范围刷新替代全量刷新
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged?.invoke()
    }

    /** 切换指定位置的选中状态 */
    fun toggleSelection(position: Int) {
        if (position < 0 || position >= files.size) return
        if (files[position].name == "..") return
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
            if (selectedPositions.isEmpty()) {
                isMultiSelectMode = false
                // 退出多选模式，刷新所有项隐藏复选框
                notifyItemRangeChanged(0, itemCount)
            } else {
                notifyItemChanged(position)
            }
        } else {
            selectedPositions.add(position)
            notifyItemChanged(position)
        }
        onSelectionChanged?.invoke()
    }

    /** 全选所有文件 */
    fun selectAll() {
        isMultiSelectMode = true
        selectedPositions.clear()
        for (i in files.indices) {
            if (files[i].name != "..") {
                selectedPositions.add(i)
            }
        }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged?.invoke()
    }

    /** 取消全选（退出多选模式） */
    fun deselectAll() {
        exitMultiSelectMode()
    }

    fun updateFiles(newFiles: List<FileItem>) {
        files = newFiles
        isMultiSelectMode = false
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    /** 获取选中项数量 */
    fun getSelectedCount(): Int = getSelectedFiles().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position], position)
    }

    override fun getItemCount() = files.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cbSelect: AppCompatCheckBox = itemView.findViewById(R.id.cb_select)
        private val tvIcon: TextView = itemView.findViewById(R.id.tv_icon)
        private val tvFileName: TextView = itemView.findViewById(R.id.tv_file_name)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tv_file_size)
        private val tvFileDate: TextView = itemView.findViewById(R.id.tv_file_date)
        private val layoutFileInfo: View = itemView.findViewById(R.id.layout_file_info)

        init {
            FontSizeUtils.applyToTextView(tvFileName)
            FontSizeUtils.applyToTextView(tvFileSize)
            FontSizeUtils.applyToTextView(tvFileDate)

            // 主题文字色只需解析一次，避免每次 bind 都读取主题属性造成滚动卡顿
            val textColor = ThemeUtils.getEffectiveFontColor(itemView.context)
            tvFileName.setTextColor(textColor)
            tvFileSize.setTextColor(textColor)
            tvFileDate.setTextColor(textColor)
            tvIcon.setTextColor(textColor)

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (isMultiSelectMode && files[pos].name != "..") {
                    toggleSelection(pos)
                } else {
                    onItemClick(files[pos])
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (files[pos].name != "..") {
                    if (!isMultiSelectMode) {
                        enterMultiSelectMode(pos)
                    } else {
                        toggleSelection(pos)
                    }
                    onItemLongClick?.invoke(files[pos])
                }
                true
            }
        }

        fun bind(file: FileItem, position: Int) {
            if (file.name == "..") {
                tvIcon.text = "\u2190"
                tvFileName.text = ".. (返回上级)"
                layoutFileInfo.visibility = View.GONE
                cbSelect.visibility = View.GONE
                itemView.isActivated = false
            } else {
                tvIcon.text = if (file.isDirectory) "\uD83D\uDCC1" else "\uD83D\uDCC4"
                tvFileName.text = file.name
                if (file.isDirectory) {
                    layoutFileInfo.visibility = View.GONE
                } else {
                    layoutFileInfo.visibility = View.VISIBLE
                    tvFileSize.text = FileUtils.formatFileSize(file.size)
                    tvFileDate.text = FileUtils.formatDate(file.lastModified)
                }

                val isSelected = selectedPositions.contains(position)
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = isSelected
                itemView.isActivated = isSelected
            }
        }
    }
}
