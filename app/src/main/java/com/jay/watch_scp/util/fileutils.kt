package com.jay.watch_scp.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

/** 存储权限工具：Android 11+ 需要「所有文件访问权限」才能读取本机全部文件 */
object StorageUtils {

    /** 当前是否处于「旧版存储模式」：targetSdk<=29 且 requestLegacyExternalStorage 生效 */
    fun isLegacyStorageMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Environment.isExternalStorageLegacy()

    private fun hasRuntimeReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /** 是否已拥有访问共享存储全部文件的权限 */
    fun hasAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            // 旧版存储模式：只要拿到运行时读权限就能读取全部文件（手表 ROM 的常用做法）
            return isLegacyStorageMode() && hasRuntimeReadPermission(context)
        }
        val readGranted = hasRuntimeReadPermission(context)
        val writeGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    /** 旧版存储模式下还需要申请的运行时权限（已授予的自动跳过） */
    fun pendingRuntimeStoragePermissions(context: Context): Array<String> {
        val pending = mutableListOf<String>()
        if (!hasRuntimeReadPermission(context)) {
            pending.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pending.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return pending.toTypedArray()
    }

    private data class SettingsTarget(
        val component: ComponentName,
        val action: String?,
        val data: Uri?
    )

    /**
     * 打开系统的「所有文件访问权限」设置页。
     *
     * 部分手表 ROM（ColorOS Watch / Wear OS）会把 MANAGE_EXTERNAL_STORAGE 相关 action
     * 静默重定向到「应用详情」页，而应用详情页里没有「所有文件访问」开关。所以这里不能用
     * resolveActivity 的结果直接启动，改成：
     * 1) 枚举每个 action 的**全部**候选组件（不是只取第一个），过滤掉应用详情页；
     * 2) 再扫描设置类应用里类名带 externalstorage/allfiles/fileaccess/specialaccess 的页面；
     * 3) 按页面类型打分排序，逐个显式启动（显式组件能绕过 OEM 的 action 重定向）；
     * 4) 全部失败返回 false，绝不回退到应用详情页。
     *
     * @return 是否成功启动了「所有文件访问」相关页面
     */
    fun openAllFilesAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val pm = context.packageManager
        val selfPackage = context.packageName
        val packageUri = Uri.parse("package:$selfPackage")
        val appAction = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        val listAction = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        val specialAction = "android.settings.MANAGE_SPECIAL_APP_ACCESS"

        // 该 ROM 上「应用详情」的组件（含类名特征），一律不作为目标
        val detailsComponents = queryActivities(pm, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

        val targets = mutableListOf<SettingsTarget>()
        fun collect(action: String, data: Uri?) {
            for (component in queryActivities(pm, action, data)) {
                if (component.packageName == selfPackage) continue
                if (isAppDetailsPage(component) || component in detailsComponents) continue
                if (!isSettingsPackage(pm, component.packageName)) continue
                targets += SettingsTarget(component, action, data)
            }
        }

        // 1) 标准 action 的全部候选组件（包括未声明 CATEGORY_DEFAULT 的）
        collect(appAction, packageUri)
        collect(listAction, null)
        collect(listAction, packageUri)
        collect(specialAction, packageUri)
        collect(specialAction, null)

        // 2) 扫描设置类应用中的「所有文件访问」页面，绕开 OEM 的 action 重定向
        for (component in findActivitiesByKeywords(pm, selfPackage)) {
            targets += SettingsTarget(component, null, null)
            targets += SettingsTarget(component, listAction, null)
            targets += SettingsTarget(component, appAction, packageUri)
        }

        val ordered = targets
            .distinctBy { it.component to it.action }
            .sortedWith(
                compareByDescending<SettingsTarget> { scoreComponent(it.component) }
                    .thenBy { variantRank(it) }
            )

        // 逐个显式启动；异常（组件未导出/不存在）就换下一个
        for (target in ordered) {
            if (startComponent(context, target.component, target.action, target.data)) return true
        }
        // 不再回退到应用详情页或设置首页（那里没有该开关）
        return false
    }

    /** 查询某个 action 的全部候选组件（包含未设置 CATEGORY_DEFAULT 的组件） */
    private fun queryActivities(pm: PackageManager, action: String, data: Uri?): List<ComponentName> {
        return try {
            val intent = Intent(action).apply { if (data != null) setData(data) }
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .asSequence()
                .mapNotNull { it.activityInfo }
                .filter { it.exported }
                .map { ComponentName(it.packageName, it.name) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 应用详情页的类名特征（不同 ROM 叫法不同） */
    private fun isAppDetailsPage(component: ComponentName): Boolean {
        val cls = component.className.lowercase()
        return cls.contains("installedappdetails") ||
            cls.contains("appinfodashboard") ||
            cls.contains("appdetail") ||
            cls.contains("installedapp") ||
            cls.contains("appinfo")
    }

    /** 是否设置类应用（第三方应用不算） */
    private fun isSettingsPackage(pm: PackageManager, packageName: String): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.contains("settings") || pkg.contains("oppo") || pkg.contains("oplus") ||
            pkg.contains("coloros") || pkg.contains("wearable")
        ) return true
        return try {
            val flags = pm.getApplicationInfo(packageName, 0).flags
            (flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) {
            false
        }
    }

    /** 页面类型打分：越像「所有文件访问」页面分数越高，用于排序启动顺序 */
    private fun scoreComponent(component: ComponentName): Int {
        val cls = component.className.lowercase()
        return when {
            cls.contains("appmanageexternalstorage") -> 5
            cls.contains("externalstorage") || cls.contains("external_storage") -> 4
            cls.contains("allfiles") || cls.contains("all_files") ||
                cls.contains("fileaccess") || cls.contains("file_access") -> 4
            cls.contains("manageexternal") || cls.contains("manage_external") -> 3
            cls.contains("specialaccess") || cls.contains("special_access") -> 2
            else -> 1
        }
    }

    /**
     * 枚举所有已安装应用 Activity，找出名字像「所有文件访问」的页面。
     * 需要 QUERY_ALL_PACKAGES 权限才能看到其他应用的 Activity。
     */
    private fun findActivitiesByKeywords(pm: PackageManager, selfPackage: String): List<ComponentName> {
        val keywords = listOf(
            "externalstorage", "external_storage",
            "allfiles", "all_files",
            "fileaccess", "file_access",
            "manageexternal", "manage_external",
            "specialaccess", "special_access"
        )
        val result = mutableListOf<ComponentName>()
        try {
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            for (pkg in packages) {
                if (pkg.packageName == selfPackage) continue
                if (!isSettingsPackage(pm, pkg.packageName)) continue
                for (activity in pkg.activities ?: continue) {
                    if (!activity.exported) continue
                    val cls = activity.name.lowercase()
                    if (keywords.any { cls.contains(it) }) {
                        result += ComponentName(activity.packageName, activity.name)
                    }
                }
            }
        } catch (_: Exception) {
            // 包可见性受限时忽略，走上面的 action 候选
        }
        return result
    }

    /** 同一组件的不同启动参数优先顺序：应用专属页优先，避免被全局列表页二次路由 */
    private fun variantRank(target: SettingsTarget): Int {
        val score = scoreComponent(target.component)
        return when {
            target.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ->
                if (score >= 4) 0 else 2
            target.action == Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION ->
                if (score >= 4) 1 else 0
            target.action == null -> 3
            else -> 2
        }
    }

    /** 显式启动指定组件（action 为 null 时只指定组件和数据） */
    private fun startComponent(
        context: Context,
        component: ComponentName,
        action: String?,
        data: Uri?
    ): Boolean {
        return try {
            val intent = Intent().apply {
                this.component = component
                if (action != null) this.action = action
                if (data != null) setData(data)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 解析 action 的真实目标组件（先按 DEFAULT 解析，再枚举非默认组件） */
    private fun resolveComponent(pm: PackageManager, action: String, data: Uri?): ComponentName? {
        return try {
            val intent = Intent(action).apply { if (data != null) setData(data) }
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let {
                return ComponentName(it.packageName, it.name)
            }
            pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.let {
                ComponentName(it.packageName, it.name)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 是否属于设置类应用（排除普通第三方应用） */
    private fun isSettingsComponent(component: ComponentName): Boolean {
        val pkg = component.packageName.lowercase()
        return pkg.startsWith("com.android.settings") ||
            pkg.startsWith("com.google.android.wearable") ||
            pkg.startsWith("com.oplus") ||
            pkg.startsWith("oppo") ||
            pkg.contains("settings")
    }

    /**
     * 枚举所有已安装应用 Activity，找出名字像「所有文件访问」的页面。
     * 需要 QUERY_ALL_PACKAGES 权限才能看到其他应用的 Activity。
     */
    private fun findActivityByKeywords(pm: PackageManager, selfPackage: String): ComponentName? {
        val keywords = listOf("externalstorage", "allfiles", "allfilesaccess", "fileaccess")
        return try {
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            for (pkg in packages) {
                if (pkg.packageName == selfPackage) continue
                if (!isSettingsComponent(ComponentName(pkg.packageName, ""))) continue
                for (activity in pkg.activities ?: continue) {
                    val cls = activity.name.lowercase()
                    if (keywords.any { cls.contains(it) }) {
                        return ComponentName(activity.packageName, activity.name)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 通过电脑 ADB 授予所有文件访问权限的命令（手表设置界面无该入口时使用） */
    fun adbGrantCommand(context: Context): String =
        "adb shell appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"

    /** 弹出 ADB 授权命令说明，支持一键复制命令 */
    fun showAdbGrantDialog(context: Context) {
        val command = adbGrantCommand(context)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(com.jay.watch_scp.R.string.storage_permission_adb_title)
            .setMessage(
                context.getString(com.jay.watch_scp.R.string.storage_permission_adb_desc) +
                    "\n\n" + command
            )
            .setPositiveButton(com.jay.watch_scp.R.string.storage_permission_copy_adb) { _, _ ->
                try {
                    val clipboard = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("adb", command)
                    )
                    android.widget.Toast.makeText(
                        context,
                        com.jay.watch_scp.R.string.storage_permission_adb_copied,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                    // 复制失败时用户仍可手动抄写命令
                }
            }
            .setNegativeButton(com.jay.watch_scp.R.string.storage_permission_later, null)
            .show()
    }
}

object FileUtils {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun formatDate(timestamp: Long): String {
        return if (timestamp > 0) dateFormat.format(Date(timestamp)) else ""
    }

    fun getFileType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "log", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "c", "cpp", "h" -> "text"
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico" -> "image"
            "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a" -> "audio"
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm" -> "video"
            "zip", "rar", "tar", "gz", "7z", "bz2" -> "archive"
            "apk", "aab" -> "android_package"
            "exe", "sh", "bat" -> "executable"
            "pdf" -> "pdf"
            "doc", "docx" -> "document"
            "xls", "xlsx", "csv" -> "spreadsheet"
            else -> "unknown"
        }
    }
}