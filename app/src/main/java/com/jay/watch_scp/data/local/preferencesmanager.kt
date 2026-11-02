package com.jay.watch_scp.data.local

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jay.watch_scp.data.model.ServerInfo
import com.jay.watch_scp.data.model.TransferRecord
import com.jay.watch_scp.data.model.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val FONT_SIZE = intPreferencesKey("font_size")
        private val DPI = intPreferencesKey("dpi")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DOWNLOAD_PATH = stringPreferencesKey("download_path")
        private val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        private val SAVED_SERVERS = stringPreferencesKey("saved_servers")
        private val TRANSFER_HISTORY = stringPreferencesKey("transfer_history")
    }

    /** 数据类：一次性保存所有设置快照，避免多次DataStore读取 */
    data class SettingsSnapshot(
        val fontSize: Int,
        val dpi: Int,
        val themeMode: String,
        val downloadPath: String,
        val maxConcurrent: Int
    )

    /** 单次DataStore读取获取所有设置值，显著减少首次加载延迟 */
    suspend fun getSnapshot(): SettingsSnapshot {
        val prefs = context.dataStore.data.first()
        return SettingsSnapshot(
            fontSize = prefs[FONT_SIZE] ?: 14,
            dpi = prefs[DPI] ?: 160,
            themeMode = prefs[THEME_MODE] ?: "dark",
            downloadPath = prefs[DOWNLOAD_PATH] ?: defaultDownloadPath(),
            maxConcurrent = prefs[MAX_CONCURRENT] ?: 1
        )
    }

    val fontSize: Flow<Int> = context.dataStore.data.map { it[FONT_SIZE] ?: 14 }
    val dpi: Flow<Int> = context.dataStore.data.map { it[DPI] ?: 160 }
    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "dark" }
    val downloadPath: Flow<String> = context.dataStore.data.map {
        it[DOWNLOAD_PATH] ?: defaultDownloadPath()
    }
    val maxConcurrent: Flow<Int> = context.dataStore.data.map { it[MAX_CONCURRENT] ?: 1 }
    val savedServers: Flow<List<ServerInfo>> = context.dataStore.data.map { prefs ->
        val json = prefs[SAVED_SERVERS] ?: "[]"
        parseServerList(json)
    }

    val transferHistory: Flow<List<TransferRecord>> = context.dataStore.data.map { prefs ->
        val json = prefs[TRANSFER_HISTORY] ?: "[]"
        parseTransferHistory(json)
    }

    suspend fun updateFontSize(size: Int) {
        context.dataStore.edit { it[FONT_SIZE] = size }
    }

    suspend fun updateDpi(dpi: Int) {
        context.dataStore.edit { it[DPI] = dpi }
    }

    suspend fun updateThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun updateDownloadPath(path: String) {
        context.dataStore.edit { it[DOWNLOAD_PATH] = path }
    }

    suspend fun updateMaxConcurrent(count: Int) {
        context.dataStore.edit { it[MAX_CONCURRENT] = count }
    }

    suspend fun saveServer(server: ServerInfo) {
        context.dataStore.edit { prefs ->
            val servers = parseServerList(prefs[SAVED_SERVERS] ?: "[]")
            val updated = servers.filter { it.address != server.address || it.username != server.username } + server
            prefs[SAVED_SERVERS] = toJson(updated)
        }
    }

    private fun parseServerList(json: String): List<ServerInfo> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ServerInfo(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    address = obj.optString("address", ""),
                    port = obj.optInt("port", 22),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    lastConnected = obj.optLong("lastConnected", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toJson(servers: List<ServerInfo>): String {
        val arr = JSONArray()
        servers.forEach { server ->
            val obj = JSONObject()
            obj.put("id", server.id)
            obj.put("address", server.address)
            obj.put("port", server.port)
            obj.put("username", server.username)
            obj.put("password", server.password)
            obj.put("lastConnected", server.lastConnected)
            arr.put(obj)
        }
        return arr.toString()
    }

    suspend fun saveTransferRecord(record: TransferRecord) {
        context.dataStore.edit { prefs ->
            val history = parseTransferHistory(prefs[TRANSFER_HISTORY] ?: "[]")
            val updated = listOf(record) + history
            val limited = if (updated.size > 100) updated.take(100) else updated
            prefs[TRANSFER_HISTORY] = toTransferHistoryJson(limited)
        }
    }

    suspend fun clearTransferHistory() {
        context.dataStore.edit { prefs ->
            prefs[TRANSFER_HISTORY] = "[]"
        }
    }

    /** 通用字符串存储（用于 SAF URI 等） */
    suspend fun putString(key: String, value: String) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = value
        }
    }

    suspend fun getString(key: String, default: String? = null): String? {
        return context.dataStore.data.first()[stringPreferencesKey(key)] ?: default
    }

    private fun parseTransferHistory(json: String): List<TransferRecord> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TransferRecord(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    fileName = obj.optString("fileName", ""),
                    remotePath = obj.optString("remotePath", ""),
                    localPath = obj.optString("localPath", ""),
                    isUpload = obj.optBoolean("isUpload", false),
                    size = obj.optLong("size", 0),
                    progress = obj.optInt("progress", 0),
                    status = try { TransferStatus.valueOf(obj.optString("status", "PENDING")) } catch (_: Exception) { TransferStatus.PENDING },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    errorMessage = obj.optString("errorMessage", "").ifEmpty { null }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toTransferHistoryJson(records: List<TransferRecord>): String {
        val arr = JSONArray()
        records.forEach { r ->
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("fileName", r.fileName)
            obj.put("remotePath", r.remotePath)
            obj.put("localPath", r.localPath)
            obj.put("isUpload", r.isUpload)
            obj.put("size", r.size)
            obj.put("progress", r.progress)
            obj.put("status", r.status.name)
            obj.put("timestamp", r.timestamp)
            obj.put("errorMessage", r.errorMessage ?: "")
            arr.put(obj)
        }
        return arr.toString()
    }

    /** 默认下载路径：外部存储的 Download/watch_scp_install 目录 */
    private fun defaultDownloadPath(): String {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = publicDir?.absolutePath ?: "/storage/emulated/0/Download"
        val target = java.io.File(base, "watch_scp_install")
        if (!target.exists()) target.mkdirs()
        return target.absolutePath
    }
}