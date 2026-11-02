package com.jay.watch_scp.data.model

data class ServerInfo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val address: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val lastConnected: Long = System.currentTimeMillis()
)