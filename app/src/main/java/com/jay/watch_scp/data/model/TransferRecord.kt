package com.jay.watch_scp.data.model

data class TransferRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val remotePath: String,
    val localPath: String,
    val isUpload: Boolean,
    val size: Long = 0,
    val progress: Int = 0,
    val status: TransferStatus = TransferStatus.PENDING,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)

enum class TransferStatus {
    PENDING, TRANSFERRING, PAUSED, COMPLETED, FAILED
}