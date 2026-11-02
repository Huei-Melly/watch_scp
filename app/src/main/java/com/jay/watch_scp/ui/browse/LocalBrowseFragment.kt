package com.jay.watch_scp.ui.browse

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.R
import com.jay.watch_scp.adapter.FileListAdapter
import com.jay.watch_scp.data.model.FileItem
import com.jay.watch_scp.databinding.FragmentLocalBrowseBinding
import com.jay.watch_scp.util.CenteredDialogs
import com.jay.watch_scp.util.FileUtils
import com.jay.watch_scp.util.FontSizeUtils
import com.jay.watch_scp.util.StorageUtils
import java.io.File

class LocalBrowseFragment : Fragment() {

    private var _binding: FragmentLocalBrowseBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: FileListAdapter
    private var currentPath: String = ""
    private var selectedFile: FileItem? = null

    var onBack: (() -> Unit)? = null
    var onUpload: ((String, String) -> Unit)? = null
    var onDirectorySelected: ((String) -> Unit)? = null
    var isDirectoryPickerMode: Boolean = false

    /** 文件选择模式：从远程浏览页进入，点选本地文件后回调上传 */
    var isFilePickerMode: Boolean = false

    /** 文件选择模式下要上传到的远程目录（用于返回远程浏览页时恢复目录） */
    var remotePathForUpload: String = "/"

    /** 选中要上传的文件回调：(本地路径或 content:// URI, 文件名) */
    var onFileSelected: ((String, String) -> Unit)? = null

    /** 下载目录路径，用于快速跳转 */
    private var downloadPath: String = "/storage/emulated/0/Download"

    /** 目录滚动位置记忆：path -> scrollPosition */
    private val scrollPositions = HashMap<String, Int>()

    /** 当前目录加载任务，切换目录时取消上一次，避免慢扫描结果覆盖新目录 */
    private var loadJob: Job? = null

    /** 运行时存储权限申请（替代已废弃的 requestPermissions / onRequestPermissionsResult） */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (isAdded) {
            if (StorageUtils.hasAllFilesAccess(requireContext())) {
                Toast.makeText(context, R.string.storage_permission_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.storage_permission_required, Toast.LENGTH_LONG).show()
            }
            loadFiles(currentPath)
        }
    }

    companion object {
        private data class ClipboardData(
            val files: List<FileItem>,
            val isCut: Boolean
        )
        private var clipboard: ClipboardData? = null
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        FontSizeUtils.applyToViewTree(binding.root)

        if (isDirectoryPickerMode) {
            binding.tvTitle.text = getString(R.string.dir_picker_title)
            updateDirectoryPickerUI()
        } else if (isFilePickerMode) {
            binding.tvTitle.text = getString(R.string.file_picker_title)
            updateFilePickerUI()
        }

        currentPath = resolveRootPath()
        loadFiles(currentPath)

        // 异步读取下载路径
        viewLifecycleOwner.lifecycleScope.launch {
            downloadPath = WatchScpApp.instance.preferencesManager.downloadPath.first()
        }
    }

    /** 解析可用的根路径 */
    private fun resolveRootPath(): String {
        val candidates = listOf(
            try { Environment.getExternalStorageDirectory().absolutePath } catch (_: Exception) { null },
            "/storage/emulated/0",
            "/sdcard",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
        )
        for (path in candidates) {
            if (path != null) {
                val f = File(path)
                if (f.exists() && f.isDirectory && f.canRead()) {
                    return path
                }
            }
        }
        return "/storage/emulated/0"
    }

    override fun onResume() {
        super.onResume()
        FontSizeUtils.applyToViewTree(binding.root)
        // 注意：不要在这里重新扫描目录。
        // 打开/关闭对话框都会触发 onPause/onResume，全量重扫会造成明显卡顿；
        // 目录内容在 onViewCreated 及每次文件操作后已主动刷新。
    }

    // ─────────── 界面设置 ───────────

    private fun setupRecyclerView() {
        adapter = FileListAdapter(
            onItemClick = { file ->
                when {
                    file.name == ".." -> navigateTo(file.path)
                    file.isDirectory -> {
                        if (isDirectoryPickerMode) {
                            CenteredDialogs.showOptions(
                                requireContext(),
                                file.name,
                                arrayOf(
                                    getString(R.string.file_op_select_this_folder),
                                    getString(R.string.file_op_enter_folder),
                                    getString(R.string.file_op_cancel)
                                )
                            ) { which ->
                                when (which) {
                                    0 -> onDirectorySelected?.invoke(file.path)
                                    1 -> navigateTo(file.path)
                                }
                            }
                        } else {
                            navigateTo(file.path)
                        }
                    }
                    else -> {
                        if (isFilePickerMode) {
                            // 选择要上传的文件：本地路径优先，必要时回退 content:// URI
                            onFileSelected?.invoke(file.contentUri ?: file.path, file.name)
                        } else if (isDirectoryPickerMode) {
                            CenteredDialogs.showOptions(
                                requireContext(),
                                file.name,
                                arrayOf(
                                    getString(R.string.file_op_select_this_file),
                                    getString(R.string.file_op_cancel)
                                )
                            ) { which ->
                                if (which == 0) onDirectorySelected?.invoke(file.path)
                            }
                        } else {
                            selectedFile = file
                            showFileOperationsDialog(file)
                        }
                    }
                }
            },
            onItemLongClick = { file ->
                // 长按进入多选模式，选中变化由 adapter 回调同步界面
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
        binding.btnSelectDirectory.setOnClickListener { onDirectorySelected?.invoke(currentPath) }
        binding.btnMenu.setOnClickListener { showMenuDialog() }

        binding.btnCopy.setOnClickListener {
            if (adapter.isAllSelectedMode()) batchCopyToClipboard(false)
            else selectedFile?.let { clipboard = ClipboardData(listOf(it), false); Toast.makeText(context, getString(R.string.file_op_copied), Toast.LENGTH_SHORT).show(); updatePasteButtonVisibility() }
                ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
        binding.btnCut.setOnClickListener {
            if (adapter.isAllSelectedMode()) batchCopyToClipboard(true)
            else selectedFile?.let { clipboard = ClipboardData(listOf(it), true); Toast.makeText(context, getString(R.string.file_op_cutted), Toast.LENGTH_SHORT).show(); updatePasteButtonVisibility() }
                ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnDelete.setOnClickListener {
            if (adapter.isAllSelectedMode()) confirmBatchDelete()
            else selectedFile?.let { confirmDeleteFile(it) } ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
        binding.btnRename.setOnClickListener {
            selectedFile?.let { showRenameDialog(it) } ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
        binding.btnUpload.setOnClickListener {
            if (adapter.isAllSelectedMode()) batchUpload()
            else selectedFile?.let { if (!it.isDirectory) onUpload?.invoke(it.path, it.name) else Toast.makeText(context, "不支持上传目录", Toast.LENGTH_SHORT).show() }
                ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
        binding.btnInfo.setOnClickListener {
            selectedFile?.let { showFileInfoDialog(it) } ?: Toast.makeText(context, "请先选择一个文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePasteButtonVisibility() {
        binding.btnPaste.visibility = if (clipboard != null) View.VISIBLE else View.GONE
    }

    /** 申请本机全部文件访问权限 */
    private fun requestStoragePermission() {
        if (!isAdded) return
        // 旧版存储模式：只需运行时读权限，无需系统里并不存在的「所有文件访问」页
        if (StorageUtils.isLegacyStorageMode()) {
            val pending = StorageUtils.pendingRuntimeStoragePermissions(requireContext())
            if (pending.isNotEmpty()) {
                storagePermissionLauncher.launch(pending)
            } else {
                loadFiles(currentPath)
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

    private fun batchCopyToClipboard(isCut: Boolean) {
        if (!isAdded) return
        val files = adapter.getSelectedFiles()
        if (files.isEmpty()) { Toast.makeText(context, "没有选中的文件", Toast.LENGTH_SHORT).show(); return }
        clipboard = ClipboardData(files, isCut)
        Toast.makeText(context, "${if (isCut) getString(R.string.file_op_cutted) else getString(R.string.file_op_copied)} ${files.size} 项", Toast.LENGTH_SHORT).show()
        updatePasteButtonVisibility()
        adapter.deselectAll(); updateSelectionUI()
    }

    /** 在同名文件已存在时自动生成 “名称 (1).扩展名” 形式的新文件名 */
    private fun buildUniqueTargetFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val baseName = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists() && index < 1000) {
            candidate = File(dir, "$baseName ($index)$extension")
            index++
        }
        return candidate
    }

    private fun pasteFromClipboard() {
        if (!isAdded) return
        val data = clipboard ?: return
        if (data.files.isEmpty()) { clipboard = null; updatePasteButtonVisibility(); return }
        CenteredDialogs.showConfirm(
            requireContext(),
            getString(R.string.file_op_paste_confirm_title),
            getString(R.string.file_op_paste_confirm, data.files.size),
            getString(R.string.file_op_paste),
            getString(R.string.file_op_cancel)
        ) {
            if (!isAdded || _binding == null) return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                binding.progressLoading.visibility = View.VISIBLE
                // 使用 Application Context，避免在 IO 线程中访问已销毁的 Fragment
                val appCtx = WatchScpApp.instance
                val path = currentPath
                var success = 0; var fail = 0
                for (f in data.files) {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val targetDir = File(path).apply { mkdirs() }
                            val srcFile = File(f.path)
                            if (!f.contentUri.isNullOrEmpty()) {
                                // MediaStore / SAF 文件：通过 ContentResolver 读取后写入目标文件
                                val target = buildUniqueTargetFile(targetDir, f.name)
                                val srcUri = Uri.parse(f.contentUri)
                                appCtx.contentResolver.openInputStream(srcUri)?.use { ins ->
                                    target.outputStream().use { os -> ins.copyTo(os) }
                                } ?: throw IllegalStateException("无法读取源文件")
                                // 仅在复制成功后删除源，避免剪切导致数据丢失
                                if (data.isCut) {
                                    try { appCtx.contentResolver.delete(srcUri, null, null) } catch (_: Exception) { }
                                }
                                true
                            } else {
                                if (!srcFile.exists()) throw IllegalStateException("源文件不存在")
                                val sameDir = srcFile.parentFile?.absolutePath == targetDir.absolutePath
                                // 同一目录剪切粘贴视为无操作
                                if (data.isCut && sameDir) {
                                    true
                                } else {
                                    // 防止把目录复制到自身子目录导致无限递归
                                    if (srcFile.isDirectory &&
                                        targetDir.absolutePath.startsWith(srcFile.absolutePath + File.separator)
                                    ) throw IllegalStateException("不能复制到自身子目录")
                                    val target = buildUniqueTargetFile(targetDir, f.name)
                                    if (data.isCut) {
                                        if (srcFile.renameTo(target)) {
                                            true
                                        } else if (copyFile(srcFile, target)) {
                                            // 仅复制成功后才删除源文件
                                            deleteRecursive(srcFile)
                                        } else {
                                            false
                                        }
                                    } else {
                                        copyFile(srcFile, target)
                                    }
                                }
                            }
                        } catch (_: Exception) { false }
                    }
                    if (ok) success++ else fail++
                }
                if (!isAdded || _binding == null) return@launch
                binding.progressLoading.visibility = View.GONE
                clipboard = null
                updatePasteButtonVisibility()
                loadFiles(currentPath)
                Toast.makeText(context, "粘贴完成: 成功 $success 个，失败 $fail 个", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyFile(source: File, target: File): Boolean = try {
        if (source.isDirectory) { target.mkdirs(); source.listFiles()?.all { copyFile(it, File(target, it.name)) } ?: true }
        else { target.parentFile?.mkdirs(); source.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o) } }; true }
    } catch (_: Exception) { false }

    private fun confirmBatchDelete() {
        if (!isAdded) return
        val files = adapter.getSelectedFiles()
        CenteredDialogs.showConfirm(
            requireContext(),
            getString(R.string.file_op_batch_delete_title),
            getString(R.string.file_op_batch_delete_confirm, files.size),
            getString(R.string.file_op_delete),
            getString(R.string.file_op_cancel)
        ) {
            if (!isAdded || _binding == null) return@showConfirm
            viewLifecycleOwner.lifecycleScope.launch {
                var success = 0; var fail = 0
                for (f in files) {
                    val ok = withContext(Dispatchers.IO) { deleteFileItem(f) }
                    if (ok) success++ else fail++
                }
                if (!isAdded || _binding == null) return@launch
                adapter.deselectAll()
                updateSelectionUI()
                Toast.makeText(context, "删除成功 $success 个，失败 $fail 个", Toast.LENGTH_SHORT).show()
                loadFiles(currentPath)
            }
        }
    }

    /** 优先使用 File API 删除（拥有所有文件访问权限时最可靠），失败再回退 MediaStore */
    private fun deleteFileItem(file: FileItem): Boolean {
        val fileObj = File(file.path)
        if (fileObj.exists()) {
            try { if (deleteRecursive(fileObj)) return true } catch (_: Exception) { }
        }
        val uriStr = file.contentUri ?: return false
        return try {
            WatchScpApp.instance.contentResolver.delete(Uri.parse(uriStr), null, null) > 0
        } catch (_: Exception) { false }
    }

    private fun batchUpload() {
        if (!isAdded) return
        val files = adapter.getSelectedFiles().filter { !it.isDirectory }
        if (files.isEmpty()) { Toast.makeText(context, "没有可上传的文件", Toast.LENGTH_SHORT).show(); return }
        files.forEach { file ->
            val uploadPath = file.contentUri ?: file.path
            onUpload?.invoke(uploadPath, file.name)
        }
        adapter.deselectAll(); updateSelectionUI()
        Toast.makeText(context, "已启动 ${files.size} 个文件的上传任务", Toast.LENGTH_SHORT).show()
    }

    private fun updateSelectionUI() {
        if (!isAdded) return
        if (isFilePickerMode) {
            // 文件选择模式不提供任何文件操作栏
            binding.tvSelectionInfo.visibility = View.GONE
            binding.layoutOperations.visibility = View.GONE
            return
        }
        val multi = adapter.isAllSelectedMode()
        binding.tvSelectionInfo.visibility = if (multi) View.VISIBLE else View.GONE
        if (multi) {
            binding.tvSelectionInfo.text =
                getString(R.string.file_op_selected_count, adapter.getSelectedCount())
        }

        if (isDirectoryPickerMode) {
            // 目录选择模式：保留底部「选择当前目录」入口
            binding.layoutOperations.visibility = View.VISIBLE
            binding.btnSelectDirectory.visibility = View.VISIBLE
            binding.btnCopy.visibility = View.GONE
            binding.btnCut.visibility = View.GONE
            binding.btnPaste.visibility = View.GONE
            binding.btnDelete.visibility = View.GONE
            binding.btnRename.visibility = View.GONE
            binding.btnUpload.visibility = View.GONE
            binding.btnInfo.visibility = View.GONE
            return
        }

        if (multi) {
            // 多选：只保留批量操作，避免一排放不下的拥挤按钮
            binding.layoutOperations.visibility = View.VISIBLE
            binding.btnCopy.visibility = View.VISIBLE
            binding.btnCut.visibility = View.VISIBLE
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnPaste.visibility = View.GONE
            binding.btnRename.visibility = View.GONE
            binding.btnUpload.visibility = View.GONE
            binding.btnInfo.visibility = View.GONE
            binding.btnSelectDirectory.visibility = View.GONE
        } else {
            // 单选不显示底部操作栏：所有操作都在点击文件后的操作面板中
            binding.layoutOperations.visibility = View.GONE
        }
    }

    private fun showMenuDialog() {
        if (!isAdded) return
        val items = mutableListOf(
            getString(R.string.file_op_refresh),
            getString(R.string.file_op_new_folder),
            getString(R.string.file_op_new_file)
        )
        val hasStorageAccess = StorageUtils.hasAllFilesAccess(requireContext())
        if (!hasStorageAccess) {
            items.add(getString(R.string.storage_permission_menu))
        }
        if (clipboard != null) {
            // 单选的底部操作栏不再显示，粘贴入口放进菜单，保证仍可操作
            items.add(getString(R.string.file_op_paste))
        }
        if (adapter.isAllSelectedMode()) {
            items.add(getString(R.string.file_op_deselect_all))
            items.add(getString(R.string.file_op_exit_multi_select))
        } else {
            items.add(getString(R.string.file_op_select_all))
        }
        items.add(getString(R.string.file_op_cancel))

        CenteredDialogs.showOptions(
            requireContext(),
            getString(R.string.file_op_menu),
            items.toTypedArray()
        ) { which ->
            when (items[which]) {
                getString(R.string.file_op_refresh) -> loadFiles(currentPath)
                getString(R.string.file_op_new_folder) -> showNewFolderDialog()
                getString(R.string.file_op_new_file) -> showNewFileDialog()
                getString(R.string.storage_permission_menu) -> requestStoragePermission()
                getString(R.string.file_op_paste) -> pasteFromClipboard()
                getString(R.string.file_op_select_all) -> { adapter.selectAll(); updateSelectionUI() }
                getString(R.string.file_op_deselect_all) -> { adapter.deselectAll(); updateSelectionUI() }
                getString(R.string.file_op_exit_multi_select) -> { adapter.exitMultiSelectMode(); updateSelectionUI() }
            }
        }
    }

    // ─────────── 文件操作对话框 ───────────

    private fun showFileOperationsDialog(file: FileItem) {
        if (!isAdded) return
        selectedFile = file

        val items = mutableListOf(
            getString(R.string.upload),
            getString(R.string.file_op_copy),
            getString(R.string.file_op_cut),
            getString(R.string.file_op_rename),
            getString(R.string.file_op_delete),
            getString(R.string.file_op_info)
        )
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext == "apk" || ext == "aab") items.add(0, getString(R.string.file_op_install))
        if (clipboard != null) items.add(getString(R.string.file_op_paste))
        items.add(getString(R.string.file_op_cancel))

        CenteredDialogs.showOptions(requireContext(), file.name, items.toTypedArray()) { which ->
            when (items[which]) {
                getString(R.string.file_op_install) -> installApk(file)
                getString(R.string.upload) -> if (!file.isDirectory) {
                    val uploadPath = file.contentUri ?: file.path
                    onUpload?.invoke(uploadPath, file.name)
                } else Toast.makeText(context, "不支持上传目录", Toast.LENGTH_SHORT).show()
                getString(R.string.file_op_copy) -> { clipboard = ClipboardData(listOf(file), false); Toast.makeText(context, getString(R.string.file_op_copied), Toast.LENGTH_SHORT).show(); updatePasteButtonVisibility() }
                getString(R.string.file_op_cut) -> { clipboard = ClipboardData(listOf(file), true); Toast.makeText(context, getString(R.string.file_op_cutted), Toast.LENGTH_SHORT).show(); updatePasteButtonVisibility() }
                getString(R.string.file_op_rename) -> showRenameDialog(file)
                getString(R.string.file_op_delete) -> confirmDeleteFile(file)
                getString(R.string.file_op_info) -> showFileInfoDialog(file)
                getString(R.string.file_op_paste) -> pasteFromClipboard()
            }
        }
    }

    private fun showFileInfoDialog(file: FileItem) {
        if (!isAdded) return
        val info = buildString {
            append(getString(R.string.file_op_info_path)).append(": ").append(file.path).append("\n")
            if (file.contentUri != null) append("Content URI: ").append(file.contentUri).append("\n")
            if (!file.isDirectory) append(getString(R.string.file_op_info_size)).append(": ").append(FileUtils.formatFileSize(file.size)).append("\n")
            else append("类型: 文件夹\n")
            append(getString(R.string.file_op_info_date)).append(": ").append(FileUtils.formatDate(file.lastModified))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(file.name).setMessage(info.toString())
            .setPositiveButton(getString(R.string.file_op_cancel), null).create()
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun installApk(file: FileItem) {
        if (!isAdded) return
        try {
            val uri: Uri
            if (file.contentUri != null) {
                uri = Uri.parse(file.contentUri)
            } else {
                val apkFile = File(file.path)
                if (!apkFile.exists()) { Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show(); return }
                uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", apkFile)
                else Uri.fromFile(apkFile)
            }
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { Toast.makeText(context, "启动安装失败: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun showNewFileDialog() {
        if (!isAdded) return
        val path = currentPath
        CenteredDialogs.showInput(
            requireContext(),
            getString(R.string.file_op_new_file),
            null,
            getString(R.string.file_op_new_file_hint),
            getString(R.string.file_op_new_file)
        ) { value ->
            if (!isAdded || _binding == null) return@showInput
            val name = value.trim()
            val err = validateFileName(name)
            if (err != null) {
                if (isAdded) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                return@showInput
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try { File(path, name).createNewFile() } catch (_: Exception) { false }
                }
                if (!isAdded || _binding == null) return@launch
                Toast.makeText(context, if (ok) R.string.file_op_new_file_created else R.string.file_op_failed, Toast.LENGTH_SHORT).show()
                if (ok) loadFiles(path)
            }
        }
    }

    private fun showNewFolderDialog() {
        if (!isAdded) return
        val path = currentPath
        CenteredDialogs.showInput(
            requireContext(),
            getString(R.string.file_op_new_folder),
            null,
            getString(R.string.file_op_new_folder_hint),
            getString(R.string.file_op_new_folder)
        ) { value ->
            if (!isAdded || _binding == null) return@showInput
            val name = value.trim()
            val err = validateFileName(name)
            if (err != null) {
                if (isAdded) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                return@showInput
            }
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { File(path, name).mkdirs() }
                if (!isAdded || _binding == null) return@launch
                Toast.makeText(context, if (ok) R.string.file_op_new_folder_created else R.string.file_op_failed, Toast.LENGTH_SHORT).show()
                if (ok) loadFiles(path)
            }
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
        ) { value ->
            if (!isAdded || _binding == null) return@showInput
            val newName = value.trim()
            val err = validateFileName(newName)
            if (err != null) {
                if (isAdded) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                return@showInput
            }
            if (newName == file.name) return@showInput
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    // 优先使用 File API 重命名
                    val srcFile = File(file.path)
                    val newFile = File(file.path.substringBeforeLast(File.separator), newName)
                    var renamed = false
                    if (!newFile.exists()) {
                        try { renamed = srcFile.renameTo(newFile) } catch (_: Exception) { }
                    }
                    if (renamed) {
                        true
                    } else if (file.contentUri != null) {
                        // 回退 MediaStore：仅修改显示名（部分媒体文件支持）
                        try {
                            val values = android.content.ContentValues().apply {
                                put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName)
                            }
                            WatchScpApp.instance.contentResolver.update(
                                Uri.parse(file.contentUri), values, null, null
                            ) > 0
                        } catch (_: Exception) { false }
                    } else {
                        false
                    }
                }
                if (!isAdded || _binding == null) return@launch
                Toast.makeText(
                    context,
                    if (ok) getString(R.string.file_op_success) else getString(R.string.file_op_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) loadFiles(currentPath)
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
                val ok = withContext(Dispatchers.IO) { deleteFileItem(file) }
                if (!isAdded || _binding == null) return@launch
                if (ok) {
                    selectedFile = null
                    Toast.makeText(context, getString(R.string.file_op_success), Toast.LENGTH_SHORT).show()
                    loadFiles(currentPath)
                } else {
                    Toast.makeText(context, getString(R.string.file_op_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        return file.delete()
    }

    private fun validateFileName(name: String): String? = when {
        name.isEmpty() -> getString(R.string.file_op_error_empty_name)
        name.contains("/") || name.contains("\\") -> getString(R.string.file_op_error_invalid_chars)
        name.length > 255 -> getString(R.string.file_op_error_name_too_long)
        name == "." || name == ".." -> getString(R.string.file_op_error_reserved_name)
        else -> null
    }

    // ─────────── 导航与文件加载 ───────────

    private fun navigateTo(path: String) {
        // 保存当前目录的滚动位置
        if (currentPath.isNotEmpty()) {
            scrollPositions[currentPath] = (binding.recyclerFiles.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        }
        currentPath = path
        loadFiles(path)
    }

    private fun getParentPath(path: String): String? {
        val parentFile = File(path).parentFile
        if (parentFile != null) return parentFile.absolutePath
        val normalized = path.trimEnd(File.separatorChar)
        if (normalized.isEmpty() || normalized == File.separator) return null
        val lastSep = normalized.lastIndexOf(File.separator)
        if (lastSep <= 0) return File.separator
        return normalized.substring(0, lastSep)
    }

    private fun loadFiles(path: String) {
        if (!isAdded || _binding == null) return
        loadJob?.cancel()
        binding.tvCurrentPath.text = path
        binding.tvCurrentPath.isSelected = true
        binding.progressLoading.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.layoutOperations.visibility = View.GONE
        binding.tvSelectionInfo.visibility = View.GONE
        selectedFile = null

        if (isDirectoryPickerMode) {
            binding.btnSelectDirectory.visibility = View.VISIBLE
            binding.layoutOperations.visibility = View.VISIBLE
        }

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            // IO 线程中不访问 Fragment，统一使用 Application Context
            val appCtx = WatchScpApp.instance
            val result = withContext(Dispatchers.IO) {
                val dir = File(path)
                val items = mutableListOf<FileItem>()
                var errorMsg: String? = null
                val addedPaths = mutableSetOf<String>()

                val parentPath = getParentPath(path)
                if (parentPath != null) {
                    items.add(FileItem(name = "..", path = parentPath, isDirectory = true))
                }

                try {
                    if (dir.exists() && dir.isDirectory) {
                        val hasAccess = StorageUtils.hasAllFilesAccess(appCtx)
                        val files = if (dir.canRead()) dir.listFiles() else null
                        if (files != null) {
                            files.forEach { f ->
                                items.add(FileItem(
                                    name = f.name,
                                    path = f.absolutePath,
                                    isDirectory = f.isDirectory,
                                    size = if (f.isFile) f.length() else 0L,
                                    lastModified = f.lastModified()
                                ))
                                addedPaths.add(f.absolutePath)
                            }

                            // 已拥有全部文件权限时 File API 已完整，无需再全盘扫描 MediaStore
                            if (!hasAccess) {
                                val msFiles = queryMediaStoreFilesForDir(appCtx, path)
                                for (msf in msFiles) {
                                    if (!addedPaths.contains(msf.path)) {
                                        items.add(msf)
                                        addedPaths.add(msf.path)
                                    }
                                }
                            }

                            // 排序：目录优先，按名称排序
                            items.sortWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
                        } else {
                            // 无权限时回退 MediaStore：列出已索引的文件与子目录
                            val msFiles = queryMediaStoreFilesForDir(appCtx, path, includeDirectories = true)
                            if (msFiles.isNotEmpty()) {
                                items.addAll(msFiles)
                                items.sortWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
                            } else {
                                errorMsg = if (!hasAccess) {
                                    getString(R.string.storage_permission_required_guide)
                                } else {
                                    "目录不可读"
                                }
                            }
                        }
                    } else {
                        errorMsg = "目录不存在"
                    }
                } catch (e: Exception) {
                    errorMsg = getString(R.string.directory_load_failed_short)
                }
                Pair(items, errorMsg)
            }

            if (!isAdded || _binding == null) return@launch

            val (displayFiles, errorMsg) = result
            adapter.updateFiles(displayFiles)

            // 恢复滚动位置
            val savedPos = scrollPositions[path]
            if (savedPos != null && savedPos > 0) {
                binding.recyclerFiles.post { binding.recyclerFiles.scrollToPosition(savedPos) }
            } else {
                binding.recyclerFiles.scrollToPosition(0)
            }

            when {
                errorMsg != null -> {
                    binding.tvEmpty.text = getString(R.string.directory_load_failed, errorMsg)
                    binding.tvEmpty.visibility = View.VISIBLE
                }
                displayFiles.isEmpty() || (displayFiles.size == 1 && displayFiles[0].name == "..") -> {
                    binding.tvEmpty.text = getString(R.string.directory_empty_guide)
                    binding.tvEmpty.visibility = View.VISIBLE
                }
                else -> binding.tvEmpty.visibility = View.GONE
            }
            // 缺少存储权限时，点击提示文字可直接跳转授权
            val missingAccess = !StorageUtils.hasAllFilesAccess(appCtx)
            binding.tvEmpty.isClickable = missingAccess
            binding.tvEmpty.setOnClickListener(if (missingAccess) View.OnClickListener { requestStoragePermission() } else null)
            binding.progressLoading.visibility = View.GONE
        }
    }

    /**
     * 通过 MediaStore 查询指定目录下的内容。
     * 使用 DATA LIKE 前缀缩小查询范围，避免全盘扫描导致卡顿。
     * includeDirectories = true 时，从文件路径中推导出直接子目录（无权限时的回退方案）。
     */
    private fun queryMediaStoreFilesForDir(
        context: Context,
        dirPath: String,
        includeDirectories: Boolean = false
    ): List<FileItem> {
        val items = mutableListOf<FileItem>()
        val addedDirs = mutableSetOf<String>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            val dirSep = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
            val cursor = try {
                context.contentResolver.query(
                    uri,
                    projection,
                    "${MediaStore.Files.FileColumns.DATA} LIKE ?",
                    arrayOf("$dirSep%"),
                    null
                )
            } catch (_: Exception) { null }
            if (cursor == null) return items

            try {
                val idxId = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val idxDisplayName = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val idxData = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val idxMime = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val idxSize = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val idxDate = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                if (idxDisplayName < 0) return items

                var count = 0
                while (cursor.moveToNext() && count < 1000) {
                    val displayName = cursor.getString(idxDisplayName) ?: continue
                    if (displayName.startsWith(".")) continue

                    val filePath = if (idxData >= 0) cursor.getString(idxData) else null
                    if (filePath.isNullOrEmpty()) continue

                    val remainder = filePath.removePrefix(dirSep)
                    if (remainder.isEmpty()) continue

                    val slash = remainder.indexOf('/')
                    if (slash >= 0) {
                        // 属于更深层的文件：仅提取直接子目录
                        if (includeDirectories) {
                            val dirName = remainder.substring(0, slash)
                            if (dirName.isNotEmpty() && !dirName.startsWith(".")) {
                                val dirAbs = dirSep + dirName
                                if (addedDirs.add(dirAbs)) {
                                    items.add(FileItem(name = dirName, path = dirAbs, isDirectory = true))
                                }
                            }
                        }
                        continue
                    }

                    val contentUriStr = if (idxId >= 0) {
                        "${MediaStore.Files.getContentUri("external")}/${cursor.getLong(idxId)}"
                    } else null

                    items.add(FileItem(
                        name = displayName,
                        path = filePath,
                        isDirectory = false,
                        size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L,
                        lastModified = if (idxDate >= 0) cursor.getLong(idxDate) * 1000L else 0L,
                        contentUri = contentUriStr
                    ))
                    count++
                }
            } finally {
                cursor.close()
            }
        } catch (_: Exception) { }
        return items
    }

    private fun updateDirectoryPickerUI() {
        binding.btnMenu.visibility = View.GONE
        binding.btnDelete.visibility = View.GONE; binding.btnRename.visibility = View.GONE
        binding.btnUpload.visibility = View.GONE; binding.btnCopy.visibility = View.GONE
        binding.btnCut.visibility = View.GONE; binding.btnPaste.visibility = View.GONE
        binding.btnInfo.visibility = View.GONE
        binding.btnSelectDirectory.visibility = View.VISIBLE
        binding.layoutOperations.visibility = View.VISIBLE
    }

    private fun updateFilePickerUI() {
        binding.btnMenu.visibility = View.GONE
        binding.btnSelectDirectory.visibility = View.GONE
        binding.btnDelete.visibility = View.GONE; binding.btnRename.visibility = View.GONE
        binding.btnUpload.visibility = View.GONE; binding.btnCopy.visibility = View.GONE
        binding.btnCut.visibility = View.GONE; binding.btnPaste.visibility = View.GONE
        binding.btnInfo.visibility = View.GONE
        binding.layoutOperations.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}