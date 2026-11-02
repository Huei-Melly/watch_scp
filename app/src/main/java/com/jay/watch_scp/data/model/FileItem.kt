package com.jay.watch_scp.data.model

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val permissions: String = "",
    val contentUri: String? = null   // MediaStore content:// URI, 无文件路径时使用
)