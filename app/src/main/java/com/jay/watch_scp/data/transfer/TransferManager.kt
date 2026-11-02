package com.jay.watch_scp.data.transfer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jay.watch_scp.R
import com.jay.watch_scp.data.local.PreferencesManager
import com.jay.watch_scp.data.model.TransferRecord
import com.jay.watch_scp.data.model.TransferStatus
import com.jay.watch_scp.data.network.SftpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 应用级传输管理器：队列独立于界面生命周期，
 * 切换页面时传输不会中断，批量任务也能按顺序全部执行。
 */
class TransferManager(
    private val context: Context,
    private val sftpClient: SftpClient,
    private val preferences: PreferencesManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<TransferRecord>>(emptyList())
    val records: StateFlow<List<TransferRecord>> = _records

    private val pumpLock = Mutex()
    private var pumpJob: Job? = null

    /** 用于在任何界面都能给出传输结果提示 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 下载：加入队列 */
    fun enqueueDownload(remotePath: String, fileName: String) {
        val record = TransferRecord(
            fileName = fileName.substringAfterLast('/'),
            remotePath = remotePath,
            localPath = "",
            isUpload = false
        )
        addRecord(record)
        pump()
    }

    /** 上传：加入队列。支持普通文件路径和 content:// URI。 */
    fun enqueueUpload(localPath: String, remotePath: String, fileName: String): Boolean {
        if (!localPath.startsWith("content://")) {
            val file = File(localPath)
            if (!file.exists() || !file.isFile) return false
        }
        val record = TransferRecord(
            fileName = fileName,
            remotePath = remotePath,
            localPath = localPath,
            isUpload = true,
            size = if (localPath.startsWith("content://")) 0 else File(localPath).length()
        )
        addRecord(record)
        pump()
        return true
    }

    /** 清空所有任务并取消正在进行中的传输 */
    fun clearAll() {
        pumpJob?.cancel()
        pumpJob = null
        _records.value = emptyList()
    }

    private fun addRecord(record: TransferRecord) {
        _records.update { it + record }
    }

    private fun updateRecord(id: String, transform: (TransferRecord) -> TransferRecord) {
        _records.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun updateProgress(id: String, progress: Int) {
        updateRecord(id) {
            if (it.status == TransferStatus.TRANSFERRING) {
                it.copy(progress = progress.coerceIn(0, 100))
            } else it
        }
    }

    /** 顺序处理队列中所有 PENDING 任务 */
    private fun pump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            pumpLock.withLock {
                while (currentCoroutineContext().isActive) {
                    val next = _records.value.firstOrNull { it.status == TransferStatus.PENDING }
                        ?: break
                    updateRecord(next.id) { it.copy(status = TransferStatus.TRANSFERRING, progress = 0) }

                    val result = try {
                        perform(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }

                    ensureActive()

                    // 任务在传输期间被清空（clearAll）时，不再写回进度或弹出结果提示
                    if (_records.value.none { it.id == next.id }) continue

                    if (result.isSuccess) {
                        val completed = _records.value.firstOrNull { it.id == next.id } ?: next
                        val record = completed.copy(status = TransferStatus.COMPLETED, progress = 100)
                        updateRecord(next.id) { record }
                        preferences.saveTransferRecord(record)
                        notifyResult(record)
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "传输失败"
                        val failed = _records.value.firstOrNull { it.id == next.id } ?: next
                        val record = failed.copy(status = TransferStatus.FAILED, errorMessage = error)
                        updateRecord(next.id) { record }
                        preferences.saveTransferRecord(record)
                        notifyResult(record)
                    }
                }
            }
        }
    }

    /** 在任意界面提示传输结果，用户离开传输页也能知道完成/失败 */
    private fun notifyResult(record: TransferRecord) {
        val ctx = context.applicationContext
        val message = when {
            record.status == TransferStatus.COMPLETED && record.isUpload ->
                "${ctx.getString(R.string.transfer_upload_complete)}: ${record.fileName}"
            record.status == TransferStatus.COMPLETED ->
                ctx.getString(
                    R.string.transfer_download_complete_with_path,
                    record.localPath.ifEmpty { record.fileName }
                )
            else -> ctx.getString(
                R.string.transfer_failed_with_reason,
                record.errorMessage ?: ctx.getString(R.string.transfer_failed)
            )
        }
        mainHandler.post { Toast.makeText(ctx, message, Toast.LENGTH_LONG).show() }
    }

    private suspend fun perform(record: TransferRecord): Result<Unit> {
        return if (record.isUpload) {
            performUpload(record)
        } else {
            performDownload(record)
        }
    }

    private suspend fun performDownload(record: TransferRecord): Result<Unit> {
        val dir = File(preferences.downloadPath.first())
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建下载目录: ${dir.absolutePath}"))
        }
        // 同名文件自动改名，避免静默覆盖已有文件
        val localFile = uniqueTargetFile(dir, record.fileName)
        updateRecord(record.id) { it.copy(localPath = localFile.absolutePath) }
        return sftpClient.downloadFile(record.remotePath, localFile.absolutePath) { progress ->
            updateProgress(record.id, progress)
        }
    }

    /** 同名时生成 “名称 (1).扩展名”，与本地复制逻辑保持一致 */
    private fun uniqueTargetFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (index < 1000) {
            candidate = File(dir, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
        return File(dir, "$base-${System.currentTimeMillis()}$ext")
    }

    private suspend fun performUpload(record: TransferRecord): Result<Unit> {
        var tempFile: File? = null
        return try {
            val source: File
            if (record.localPath.startsWith("content://")) {
                val fileName = record.fileName.ifBlank { "upload_${System.currentTimeMillis()}" }
                val tmp = File(context.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                val input = context.contentResolver.openInputStream(Uri.parse(record.localPath))
                    ?: throw IllegalArgumentException("无法读取所选文件")
                input.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                }
                tempFile = tmp
                source = tmp
            } else {
                source = File(record.localPath)
            }
            if (!source.exists() || !source.isFile) {
                throw IllegalArgumentException("本地文件不存在")
            }
            updateRecord(record.id) {
                it.copy(localPath = source.absolutePath, size = source.length())
            }
            sftpClient.uploadFile(source.absolutePath, record.remotePath) { progress ->
                updateProgress(record.id, progress)
            }
        } finally {
            tempFile?.delete()
        }
    }
}
