package com.vlessvpn.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.vlessvpn.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false
    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            appendLog("VPN permission denied")
            updateUI(connected = false)
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("state")) {
                "CONNECTED" -> {
                    appendLog("Connected!")
                    updateUI(connected = true)
                }
                "DISCONNECTED" -> {
                    appendLog("Disconnected")
                    updateUI(connected = false)
                }
                "TRAFFIC" -> {
                    val down = intent?.getStringExtra("down") ?: "0 B"
                    val up = intent?.getStringExtra("up") ?: "0 B"
                    binding.tvStatus.text = "\u2193 $down  \u2191 $up"
                }
                "LOG" -> {
                    val log = intent?.getStringExtra("log") ?: return
                    appendLog(log)
                }
                "ERROR" -> {
                    val msg = intent.getStringExtra("message") ?: "Unknown error"
                    appendLog("ERROR: $msg")
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    updateUI(connected = false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved link
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedLink = prefs.getString("vless_link", "") ?: ""
        if (savedLink.isNotBlank()) {
            binding.etVlessLink.setText(savedLink)
        }

        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                stopVpn()
            } else {
                val link = binding.etVlessLink.text.toString().trim()
                if (link.isBlank() || !link.startsWith("vless://")) {
                    Toast.makeText(this, "Please paste a valid vless:// link", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Save and parse the link
                prefs.edit().putString("vless_link", link).apply()
                if (!VlessConfig.saveFromLink(this, link)) {
                    Toast.makeText(this, "Invalid VLESS link format", Toast.LENGTH_SHORT).show()
                    appendLog("Failed to parse VLESS link")
                    return@setOnClickListener
                }

                // Clear logs on new connection
                logBuilder.clear()
                binding.tvLogs.text = ""
                appendLog("Parsed link OK")

                requestVpnPermissionAndStart()
            }
        }

        updateUI(connected = false)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(VlessVpnService.BROADCAST_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }
        isConnected = VlessVpnService.isRunning
        updateUI(isConnected)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnStateReceiver)
    }

    private fun requestVpnPermissionAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            appendLog("Requesting VPN permission...")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        binding.btnConnect.text = "Connecting..."
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "Connecting..."
        binding.etVlessLink.isEnabled = false
        appendLog("Starting VPN service...")

        val intent = Intent(this, VlessVpnService::class.java).apply {
            action = VlessVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        appendLog("Stopping VPN...")
        val intent = Intent(this, VlessVpnService::class.java).apply {
            action = VlessVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun appendLog(msg: String) {
        val time = timeFormat.format(Date())
        logBuilder.append("[$time] $msg\n")
        binding.tvLogs.text = logBuilder.toString()
        // Auto-scroll to bottom
        binding.svLogs.post { binding.svLogs.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun updateUI(connected: Boolean) {
        isConnected = connected
        binding.btnConnect.isEnabled = true
        if (connected) {
            binding.btnConnect.text = getString(R.string.disconnect)
            binding.btnConnect.backgroundTintList = ColorStateList.valueOf(getColor(R.color.disconnect_red))
            binding.tvStatus.text = getString(R.string.status_connected)
            binding.tvStatus.setTextColor(getColor(R.color.connected_green))
            binding.ivStatusIcon.setImageResource(R.drawable.ic_vpn_on)
            binding.etVlessLink.isEnabled = false
        } else {
            binding.btnConnect.text = getString(R.string.connect)
            binding.btnConnect.backgroundTintList = ColorStateList.valueOf(getColor(R.color.connect_blue))
            binding.tvStatus.text = getString(R.string.status_disconnected)
            binding.tvStatus.setTextColor(getColor(R.color.disconnected_gray))
            binding.ivStatusIcon.setImageResource(R.drawable.ic_vpn_off)
            binding.etVlessLink.isEnabled = true
        }
    }
}
