package com.jay.watch_scp.ui.connect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.jay.watch_scp.R
import com.jay.watch_scp.WatchScpApp
import com.jay.watch_scp.data.model.ServerInfo
import com.jay.watch_scp.databinding.FragmentConnectBinding
import com.jay.watch_scp.util.FontSizeUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConnectFragment : Fragment() {

    private var _binding: FragmentConnectBinding? = null
    private val binding get() = _binding!!

    var onConnected: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    /** true 时不预填上次服务器信息，要求重新输入地址等 */
    var startFresh: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (startFresh) {
            // 手动断开后要求重新输入：不预填旧服务器，端口保持默认 22
            binding.etPort.setText("22")
        } else {
            loadLastServer()
        }
        binding.btnConnect.setOnClickListener { connect() }
        binding.btnBack.setOnClickListener { onBack?.invoke() }
    }

    override fun onResume() {
        super.onResume()
        // 字体大小已全局缓存，无需异步读取DataStore
        FontSizeUtils.applyToViewTree(binding.root)
    }

    private fun loadLastServer() {
        lifecycleScope.launch {
            val app = WatchScpApp.instance
            val servers = app.preferencesManager.savedServers.first()
            if (servers.isNotEmpty()) {
                val last = servers.maxByOrNull { it.lastConnected }
                if (last != null) {
                    binding.etAddress.setText(last.address)
                    binding.etPort.setText(last.port.toString())
                    binding.etUsername.setText(last.username)
                    if (last.password.isNotEmpty()) {
                        binding.etPassword.setText(last.password)
                    }
                }
            }
        }
    }

    private fun connect() {
        val address = binding.etAddress.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (address.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showError("请填写所有必填字段")
            return
        }

        binding.btnConnect.visibility = View.GONE
        binding.progressConnect.visibility = View.VISIBLE
        binding.tvConnectError.visibility = View.GONE

        lifecycleScope.launch {
            val app = WatchScpApp.instance
            val result = app.sftpClient.connect(address, port, username, password)
            if (!isAdded) return@launch
            if (result.isSuccess) {
                app.markConnected()
                app.preferencesManager.saveServer(
                    ServerInfo(address = address, port = port, username = username, password = password)
                )
                binding.progressConnect.visibility = View.GONE
                onConnected?.invoke()
            } else {
                showError(result.exceptionOrNull()?.message ?: "连接失败")
                binding.btnConnect.visibility = View.VISIBLE
                binding.progressConnect.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.tvConnectError.text = message
        binding.tvConnectError.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}