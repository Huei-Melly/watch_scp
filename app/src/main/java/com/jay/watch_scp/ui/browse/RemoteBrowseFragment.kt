package com.jay.watch_scp.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jay.watch_scp.R
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.adapter.FileListAdapter
import com.jay.watch_scp.data.model.FileItem
import com.jay.watch_scp.databinding.FragmentRemoteBrowseBinding
import com.jay.watch_scp.util.CenteredDialogs
import com.jay.watch_scp.util.FontSizeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RemoteBrowseFragment : Fragment() {

    private var _binding: FragmentRemoteBrowseBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FileListAdapter
    private var currentPath: String = "/"
    private var selectedFile: FileItem? = null
    private var clipboardFile: FileItem? = null
    private var isCutMode: Boolean = false

    /** 进入本页时要展示的远程目录（从本地文件选择页返回时沿用原目录） */
    var initialPath: String = "/"

    var onBack: (() -> Unit)? = null
    var onDownload: ((String, String) -> Unit)? = null
    var onUploadToRemote: ((String, String, String) -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null

    /** 上传时打开本应用内的本地文件浏览页，回调参数为当前远程目录 */
    var onPickLocalFileForUpload: ((String) -> Unit)? = null

    /** 目录滚动位置记忆：path -> scrollPosition */
    private val scrollPositions = HashMap<String, Int>()

    /** 当前目录加载任务，切换目录时取消上一次，避免慢加载结果覆盖新目录 */
    private var loadJob: Job? = null

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    /** 在目标目录中生成不重复的文件名 */
    private fun uniqueName(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$ext"
            index++
        } while (candidate in existing && index < 1000)
        return candidate
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRemoteBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentPath = initialPath
        setupRecyclerView()
        setupButtons()
        // 字体大小已全局缓存，直接应用
        FontSizeUtils.applyToViewTree(binding.root)
        ensureConnectedThenLoad()
    }

    /** 应用被回收或网络断开后回到本页：先用保存的服务器凭据静默重连，再加载目录 */
    private fun ensureConnectedThenLoad() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!WatchScpApp.instance.sftpClient.isConnected) {
                if (_binding != null) binding.progressLoading.visibility = View.VISIBLE
                WatchScpApp.instance.restoreConnectionIfPossible()
            }
            loadFiles(currentPath)
        }
    }

    private fun setupRecyclerView() {
        adapter = FileListAdapter(
            onItemClick = { file ->
                when {
                    file.name == ".." -> navigateTo(file.path)
                    file.isDirectory -> navigateTo(file.path)
                    else -> {
                        selectedFile = file
                        showFileOperationsDialog(file)
                    }
                }
            },
            onItemLongClick = { file ->
                // 长按进入多选模式，界面由 adapter 的选中回调同步
            }
        )
        adapter.onSelectionChanged = { updateSelectionUI() }
        binding.recyclerFiles.setHasFixedSize(true)
        binding.recyclerFiles.setItemViewCacheSize(40)
        binding.recyclerFiles.itemAnimator = null
        binding.recyclerFiles.layoutManager = LinearLayoutManager(context)
        binding.recyclerFiles.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { onBack?.invoke() }

        binding.btnMenu.setOnClickListener {
            showMenuDialog()
        }

        binding.btnUpload.setOnClickListener {
            pickLocalFileForUpload()
        }

        binding.btnDownloadSelected.setOnClickListener {
            if (adapter.isAllSelectedMode()) {
                batchDownload()
            } else {
                selectedFile?.let { file ->
                    if (!file.isDirectory) {
                        onDownload?.invoke(file.path, file.name)
                    } else {
                        Toast.makeText(context, "不支持下载目录", Toast.LENGTH_SHORT).show()
                    }
                } ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete.setOnClickListener {
            if (adapter.isAllSelectedMode()) {
                confirmBatchDelete()
            } else {
                selectedFile?.let { file -> confirmDeleteFile(file) }
                    ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRename.setOnClickListener {
            selectedFile?.let { file -> showRenameDialog(file) }
                ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenuDialog() {
        if (!isAdded) return
        val isMultiSelect = adapter.isAllSelectedMode()
        val items = mutableListOf(
            getString(R.string.file_op_refresh),
            getString(R.string.file_op_new_folder),
            getString(R.string.upload)
        )
        if (clipboardFile != null) {
            items.add(getString(R.string.file_op_paste))
        }
        if (isMultiSelect) {
            items.add(getString(R.string.file_op_deselect_all))
            items.add(getString(R.string.file_op_exit_multi_select))
        } else {
            items.add(getString(R.string.file_op_select_all))
        }
        items.add(getString(R.string.nav_disconnect))
        items.add(getString(R.string.file_op_cancel))

        CenteredDialogs.showOptions(
            requireContext(),
            getString(R.string.file_op_menu),
            items.toTypedArray()
        ) { which ->
            when (items[which]) {
                getString(R.string.file_op_refresh) -> loadFiles(currentPath)
                getString(R.string.file_op_new_folder) -> showNewFolderDialog()
                getString(R.string.upload) -> pickLocalFileForUpload()
                getString(R.string.file_op_paste) -> pasteFile()
                getString(R.string.file_op_select_all) -> {
                    adapter.selectAll()
                    updateSelectionUI()
                }
                getString(R.string.file_op_deselect_all) -> {
                    adapter.deselectAll()
                    updateSelectionUI()
                }
                getString(R.string.file_op_exit_multi_select) -> {
                    adapter.exitMultiSelectMode()
                    updateSelectionUI()
                }
                getString(R.string.nav_disconnect) -> onDisconnect?.invoke()
                // "取消"不做任何操作
            }
        }
    }

    /** 打开本应用内的本地文件浏览页选择要上传的文件 */
    private fun pickLocalFileForUpload() {
        onPickLocalFileForUpload?.invoke(currentPath)
    }

    private fun updateSelectionUI() {
        if (!isAdded) return
        val multi = adapter.isAllSelectedMode()
        binding.tvSelectionInfo.visibility = if (multi) View.VISIBLE else View.GONE
        if (multi) {
            binding.tvSelectionInfo.text =
                getString(R.string.file_op_selected_count, adapter.getSelectedCount())
            // 多选：仅保留下载与删除，避免一排按钮过挤
            binding.layoutOperations.visibility = View.VISIBLE
            binding.btnUpload.visibility = View.GONE
            binding.btnDownloadSelected.visibility = View.VISIBLE
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnRename.visibility = View.GONE
        } else {
            // 单选不显示底部操作栏：操作入口在文件面板与 ⋮ 菜单中
            binding.layoutOperations.visibility = View.GONE
        }
    }

    private fun batchDownload() {
        if (!isAdded) return
        val files = adapter.getSelectedFiles().filter { !it.isDirectory }
        if (files.isEmpty()) {
            Toast.makeText(context, "没有可下载的文件", Toast.LENGTH_SHORT).show()
            return
        }
        var started = 0
        for (file in files) {
            onDownload?.invoke(file.path, file.name)
            started++
        }
        adapter.deselectAll()
        updateSelectionUI()
        Toast.makeText(context, "已启动 $started 个文件的下载任务", Toast.LENGTH_SHORT).show()
    }

    private fun confirmBatchDelete() {
        if (!isAdded) return
        val files = adapter.getSelectedFiles()
        val count = files.size
        CenteredDialogs.showConfirm(
            requireContext(),
            getString(R.string.file_op_batch_delete_title),
            getString(R.string.file_op_batch_delete_confirm, count),
            getString(R.string.file_op_delete),
            getString(R.string.file_op_cancel)
        ) {
            if (!isAdded || _binding == null) return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                var successCount = 0
                var failCount = 0
                for (file in files) {
                    val result = WatchScpApp.instance.sftpClient.deleteFile(file.path)
                    result.onSuccess { successCount++ }.onFailure { failCount++ }
                }
                if (!isAdded || _binding == null) return@launch
                adapter.deselectAll()
                updateSelectionUI()
                Toast.makeText(context, "删除成功 $successCount 个，失败 $failCount 个", Toast.LENGTH_SHORT).show()
                loadFiles(currentPath)
            }
        }
    }

    private fun showNewFolderDialog() {
        if (!isAdded) return
        CenteredDialogs.showInput(
            requireContext(),
            getString(R.string.file_op_new_folder),
            null,
            getString(R.string.file_op_new_folder_hint),
            getString(R.string.file_op_new_folder)
        ) { folderNameInput ->
            if (!isAdded || _binding == null) return@showInput
            val folderName = folderNameInput.trim()
            val validationError = validateFileName(folderName)
            if (validationError != null) {
                if (isAdded) Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val newPath = joinPath(currentPath, folderName)
                    val result = WatchScpApp.instance.sftpClient.makeDirectory(newPath)
                    if (!isAdded || _binding == null) return@launch
                    result.onSuccess {
                        Toast.makeText(context, R.string.file_op_new_folder_created, Toast.LENGTH_SHORT).show()
                        loadFiles(currentPath)
                    }.onFailure { e ->
                        Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun validateFileName(name: String): String? {
        if (name.isEmpty()) return getString(R.string.file_op_error_empty_name)
        if (name.contains("/") || name.contains("\\")) return getString(R.string.file_op_error_invalid_chars)
        if (name.length > 255) return getString(R.string.file_op_error_name_too_long)
        if (name == "." || name == "..") return getString(R.string.file_op_error_reserved_name)
        return null
    }

    private fun showFileOperationsDialog(file: FileItem) {
        if (!isAdded) return
        selectedFile = file

        val items = mutableListOf(
            getString(R.string.download),
            getString(R.string.file_op_copy),
            getString(R.string.file_op_cut),
            getString(R.string.file_op_rename),
            getString(R.string.file_op_delete)
        )
        // APK/AAB 文件增加下载并安装选项
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if ((ext == "apk" || ext == "aab") && !file.isDirectory) {
            items.add(1, getString(R.string.file_op_download_install))
        }
        if (clipboardFile != null) {
            items.add(getString(R.string.file_op_paste))
        }
        // 添加取消选项
        items.add(getString(R.string.file_op_cancel))

        CenteredDialogs.showOptions(requireContext(), file.name, items.toTypedArray()) { which ->
            when (items[which]) {
                getString(R.string.download) -> {
                    if (!file.isDirectory) {
                        onDownload?.invoke(file.path, file.name)
                    } else {
                        Toast.makeText(context, "不支持下载目录", Toast.LENGTH_SHORT).show()
                    }
                }
                getString(R.string.file_op_download_install) -> {
                    downloadAndInstallApk(file)
                }
                getString(R.string.file_op_copy) -> {
                    clipboardFile = file
                    isCutMode = false
                    Toast.makeText(context, getString(R.string.file_op_copied), Toast.LENGTH_SHORT).show()
                }
                getString(R.string.file_op_cut) -> {
                    clipboardFile = file
                    isCutMode = true
                    Toast.makeText(context, getString(R.string.file_op_cutted), Toast.LENGTH_SHORT).show()
                }
                getString(R.string.file_op_rename) -> showRenameDialog(file)
                getString(R.string.file_op_delete) -> confirmDeleteFile(file)
                getString(R.string.file_op_paste) -> pasteFile()
                // "取消"不做任何操作
            }
            // 面板关闭后同步底部操作栏状态
            updateSelectionUI()
        }
    }

    private fun showRenameDialog(file: FileItem) {
        if (!isAdded) return
        CenteredDialogs.showInput(
            requireContext(),
            getString(R.string.file_op_rename),
            file.name,
            null,
            getString(R.string.file_op_rename)
        ) { newNameInput ->
            if (!isAdded || _binding == null) return@showInput
            val newName = newNameInput.trim()
            val validationError = validateFileName(newName)
            if (validationError != null) {
                if (isAdded) Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            } else if (newName.isNotEmpty() && newName != file.name) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val parentPath = file.path.substringBeforeLast("/")
                    val newPath = "$parentPath/$newName"
                    val result = WatchScpApp.instance.sftpClient.rename(file.path, newPath)
                    if (!isAdded || _binding == null) return@launch
                    result.onSuccess {
                        Toast.makeText(context, getString(R.string.file_op_success), Toast.LENGTH_SHORT).show()
                        loadFiles(currentPath)
                    }.onFailure { e ->
                        Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun confirmDeleteFile(file: FileItem) {
        if (!isAdded) return
        CenteredDialogs.showConfirm(
            requireContext(),
            getString(R.string.file_op_confirm_delete),
            getString(R.string.file_op_confirm_delete_msg, file.name),
            getString(R.string.file_op_delete),
            getString(R.string.file_op_cancel)
        ) {
            if (!isAdded || _binding == null) return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                binding.progressLoading.visibility = View.VISIBLE
                val result = WatchScpApp.instance.sftpClient.deleteFile(file.path)
                if (!isAdded || _binding == null) return@launch
                binding.progressLoading.visibility = View.GONE
                result.onSuccess {
                    selectedFile = null
                    binding.layoutOperations.visibility = View.GONE
                    Toast.makeText(context, getString(R.string.file_op_success), Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                }.onFailure { e ->
                    Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pasteFile() {
        if (!isAdded || _binding == null) return
        val source = clipboardFile ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressLoading.visibility = View.VISIBLE
            try {
                val sourceParent = source.path.trimEnd('/').substringBeforeLast('/', "")
                // 同一目录剪切粘贴视为无操作
                if (isCutMode && sourceParent == currentPath) {
                    binding.progressLoading.visibility = View.GONE
                    if (isAdded && _binding != null) {
                        Toast.makeText(context, "文件已在当前目录", Toast.LENGTH_SHORT).show()
                    }
                    clipboardFile = null
                    return@launch
                }
                if (!isCutMode && source.isDirectory) {
                    binding.progressLoading.visibility = View.GONE
                    if (isAdded && _binding != null) {
                        Toast.makeText(context, "暂不支持复制目录", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                // 粘贴到当前目录；存在同名文件时自动改名
                val existing = WatchScpApp.instance.sftpClient.listFiles(currentPath)
                    .getOrNull()?.map { it.name }?.toSet() ?: emptySet()
                val targetPath = joinPath(currentPath, uniqueName(source.name, existing))

                if (isCutMode) {
                    WatchScpApp.instance.sftpClient.rename(source.path, targetPath)
                        .onSuccess {
                            if (isAdded && _binding != null) {
                                Toast.makeText(context, getString(R.string.file_op_success), Toast.LENGTH_SHORT).show()
                            }
                        }
                        .onFailure { e ->
                            if (isAdded && _binding != null) {
                                Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                } else {
                    val result = WatchScpApp.instance.sftpClient.copyFile(source.path, targetPath)
                    if (!isAdded || _binding == null) return@launch
                    result.onSuccess {
                        Toast.makeText(context, getString(R.string.file_op_success), Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                clipboardFile = null
                if (!isAdded || _binding == null) return@launch
                binding.progressLoading.visibility = View.GONE
                loadFiles(currentPath)
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(context, "${getString(R.string.file_op_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadAndInstallApk(file: FileItem) {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressLoading.visibility = View.VISIBLE
                val ctx = requireContext()
                val tempFile = java.io.File(ctx.cacheDir, file.name)
                val result = WatchScpApp.instance.sftpClient.downloadFile(file.path, tempFile.absolutePath)
                if (!isAdded || _binding == null) return@launch
                binding.progressLoading.visibility = View.GONE
                result.onSuccess {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", tempFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }.onFailure { e ->
                    Toast.makeText(ctx, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded && _binding != null) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateTo(path: String) {
        // 保存当前目录的滚动位置
        if (currentPath.isNotEmpty()) {
            scrollPositions[currentPath] = (binding.recyclerFiles.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        }
        currentPath = path
        selectedFile = null
        loadFiles(path)
    }

    private fun getParentPath(path: String): String? {
        if (path == "/") return null
        val normalized = path.trimEnd('/')
        if (normalized.isEmpty()) return null
        val lastSep = normalized.lastIndexOf('/')
        if (lastSep <= 0) return "/"
        return normalized.substring(0, lastSep)
    }

    private fun loadFiles(path: String) {
        if (!isAdded || _binding == null) return
        // 切换目录时取消上一次加载，避免慢请求的结果覆盖当前目录
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.tvCurrentPath.text = path
            binding.tvCurrentPath.isSelected = true
            binding.progressLoading.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
            binding.layoutOperations.visibility = View.GONE
            binding.tvSelectionInfo.visibility = View.GONE

            val result = WatchScpApp.instance.sftpClient.listFiles(path)
            if (!isAdded || _binding == null) return@launch
            binding.progressLoading.visibility = View.GONE

            result.onSuccess { files ->
                val displayFiles = mutableListOf<FileItem>()

                val parentPath = getParentPath(path)
                if (parentPath != null) {
                    displayFiles.add(FileItem(name = "..", path = parentPath, isDirectory = true))
                }

                displayFiles.addAll(files)

                adapter.updateFiles(displayFiles)
                // 恢复滚动位置
                val savedPos = scrollPositions[path]
                if (savedPos != null && savedPos > 0) {
                    binding.recyclerFiles.post { binding.recyclerFiles.scrollToPosition(savedPos) }
                } else {
                    binding.recyclerFiles.scrollToPosition(0)
                }
                val hasEntries = displayFiles.any { it.name != ".." }
                if (!hasEntries) {
                    val guide = if (parentPath != null) {
                        getString(R.string.directory_empty_remote_guide)
                    } else {
                        getString(R.string.directory_empty)
                    }
                    binding.tvEmpty.text = guide
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                }
            }.onFailure {
                binding.tvEmpty.text = getString(R.string.directory_load_failed, it.message ?: "未知错误")
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FontSizeUtils.applyToViewTree(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
