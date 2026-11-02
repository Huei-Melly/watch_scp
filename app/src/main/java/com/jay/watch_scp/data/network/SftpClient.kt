package com.jay.watch_scp.data.network

import com.jcraft.jsch.*
import com.jay.watch_scp.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Vector

/**
 * SFTP 客户端：缓存登录凭据，连接失效时自动重连一次，
 * 避免网络切换/服务器保活失败后每次操作都直接报「未连接」。
 */
class SftpClient(private val tempDir: java.io.File? = null) {

    private data class Credentials(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelSftp? = null

    @Volatile
    private var credentials: Credentials? = null

    /** 连接状态以底层 Session/Channel 的真实状态为准 */
    val isConnected: Boolean
        get() = channel?.isConnected == true && session?.isConnected == true

    /** 是否已缓存登录凭据（用于自动恢复连接） */
    val hasCredentials: Boolean
        get() = credentials != null

    private val connectLock = Mutex()

    suspend fun connect(host: String, port: Int, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            connectLock.withLock {
                try {
                    closeQuietly()
                    openSession(Credentials(host, port, username, password))
                    credentials = Credentials(host, port, username, password)
                    Result.success(Unit)
                } catch (e: Exception) {
                    closeQuietly()
                    Result.failure(e)
                }
            }
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectLock.withLock {
            closeQuietly()
            credentials = null
        }
    }

    // ─────────── 连接管理 ───────────

    private fun openSession(c: Credentials) {
        val jsch = JSch()
        val newSession = jsch.getSession(c.username, c.host, c.port)
        newSession.setPassword(c.password)
        newSession.setConfig("StrictHostKeyChecking", "no")
        // 保活，避免长时间空闲后连接被服务器断开
        newSession.setServerAliveInterval(30000)
        newSession.setServerAliveCountMax(3)
        newSession.connect(10000)
        val newChannel = newSession.openChannel("sftp") as ChannelSftp
        newChannel.connect(5000)
        session = newSession
        channel = newChannel
    }

    private fun closeQuietly() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
    }

    /** 获取可用的 Channel；失效且有凭据时自动重连 */
    private suspend fun acquireChannel(): ChannelSftp? = connectLock.withLock {
        val ch = channel
        val s = session
        if (ch != null && ch.isConnected && s != null && s.isConnected) {
            return@withLock ch
        }
        val saved = credentials ?: return@withLock null
        try {
            closeQuietly()
            openSession(saved)
            channel
        } catch (_: Exception) {
            closeQuietly()
            null
        }
    }

    /** 强制重连（操作过程中连接被中断时使用） */
    private suspend fun forceReconnect(): ChannelSftp? = connectLock.withLock {
        val saved = credentials ?: return@withLock null
        try {
            closeQuietly()
            openSession(saved)
            channel
        } catch (_: Exception) {
            closeQuietly()
            null
        }
    }

    /**
     * 执行一次操作；若失败（通常是连接已失效），自动重连后重试一次。
     * 传输类操作重试时会从头重新传，保证文件内容完整。
     */
    private suspend fun <T> withChannel(block: (ChannelSftp) -> T): Result<T> {
        val first = acquireChannel()
            ?: return Result.failure(IllegalStateException("未连接服务器，请先连接"))
        return try {
            Result.success(block(first))
        } catch (e: Exception) {
            val retry = forceReconnect() ?: return Result.failure(e)
            try {
                Result.success(block(retry))
            } catch (e2: Exception) {
                Result.failure(e2)
            }
        }
    }

    // ─────────── 文件操作 ───────────

    suspend fun listFiles(path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        withChannel { ch ->
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(path) as Vector<ChannelSftp.LsEntry>
            entries.toList()
                .filter { !it.filename.startsWith(".") }
                .map { entry ->
                    FileItem(
                        name = entry.filename,
                        path = if (path.endsWith("/")) "$path${entry.filename}" else "$path/${entry.filename}",
                        isDirectory = entry.attrs.isDir(),
                        size = entry.attrs.getSize(),
                        lastModified = entry.attrs.getMTime() * 1000L,
                        permissions = entry.attrs.getPermissionsString()
                    )
                }
                .sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
        }
    }

    suspend fun downloadFile(
        remotePath: String,
        localPath: String,
        progressCallback: ((Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel<Unit> { ch ->
            val totalSize = ch.lstat(remotePath).size
            val rawStream = ch.get(remotePath, object : SftpProgressMonitor {
                private var lastReported = -1
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                override fun count(count: Long): Boolean {
                    val progress = if (totalSize > 0) ((count * 100) / totalSize).toInt() else 0
                    if (progress != lastReported) {
                        lastReported = progress
                        progressCallback?.invoke(progress)
                    }
                    return true
                }
                override fun end() {}
            })
            val inputStream = rawStream as InputStream
            val localFile = java.io.File(localPath)
            localFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            try {
                java.io.FileOutputStream(localFile).use { outputStream ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            } finally {
                inputStream.close()
            }
            progressCallback?.invoke(100)
        }
    }

    suspend fun uploadFile(
        localPath: String,
        remotePath: String,
        progressCallback: ((Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel<Unit> { ch ->
            val localFile = java.io.File(localPath)
            if (!localFile.exists() || !localFile.isFile) {
                throw IllegalStateException("本地文件不存在: $localPath")
            }
            val totalSize = localFile.length()
            ch.put(localPath, remotePath, object : SftpProgressMonitor {
                private var lastReported = -1
                override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                override fun count(count: Long): Boolean {
                    val progress = if (totalSize > 0) ((count * 100) / totalSize).toInt() else 0
                    if (progress != lastReported) {
                        lastReported = progress
                        progressCallback?.invoke(progress)
                    }
                    return true
                }
                override fun end() {}
            })
            progressCallback?.invoke(100)
        }
    }

    suspend fun makeDirectory(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel { ch -> ch.mkdir(path) }
    }

    suspend fun deleteFile(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel { ch ->
            val attrs = ch.stat(path)
            if (attrs.isDir) {
                deleteDirectory(ch, path)
            } else {
                ch.rm(path)
            }
        }
    }

    private fun deleteDirectory(ch: ChannelSftp, path: String) {
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(path) as Vector<ChannelSftp.LsEntry>
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            val entryPath = "$path/${entry.filename}"
            if (entry.attrs.isDir) {
                deleteDirectory(ch, entryPath)
            } else {
                ch.rm(entryPath)
            }
        }
        ch.rmdir(path)
    }

    suspend fun rename(oldPath: String, newPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel { ch -> ch.rename(oldPath, newPath) }
    }

    suspend fun copyFile(
        sourcePath: String,
        destPath: String,
        progressCallback: ((Int) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        withChannel<Unit> { ch ->
            val totalSize = ch.lstat(sourcePath).size
            val tempFile = if (tempDir != null) {
                java.io.File.createTempFile("sftp_copy", ".tmp", tempDir)
            } else {
                java.io.File.createTempFile("sftp_copy", ".tmp")
            }
            try {
                // 读取源文件到临时文件（前半程进度）
                ch.get(sourcePath, tempFile.absolutePath, object : SftpProgressMonitor {
                    private var lastReported = -1
                    override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                    override fun count(count: Long): Boolean {
                        val progress = if (totalSize > 0) ((count * 50) / totalSize).toInt() else 0
                        if (progress != lastReported) {
                            lastReported = progress
                            progressCallback?.invoke(progress)
                        }
                        return true
                    }
                    override fun end() {}
                })

                // 上传到目标位置（后半程进度）
                val tempSize = tempFile.length()
                ch.put(tempFile.absolutePath, destPath, object : SftpProgressMonitor {
                    private var lastReported = -1
                    override fun init(op: Int, src: String?, dest: String?, max: Long) {}
                    override fun count(count: Long): Boolean {
                        val progress = if (tempSize > 0) 50 + ((count * 50) / tempSize).toInt() else 50
                        if (progress != lastReported) {
                            lastReported = progress
                            progressCallback?.invoke(progress)
                        }
                        return true
                    }
                    override fun end() {}
                })
                progressCallback?.invoke(100)
            } finally {
                tempFile.delete()
            }
        }
    }
}
