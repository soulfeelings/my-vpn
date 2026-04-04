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
import com.vlessvpn.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("state")) {
                "CONNECTED" -> updateUI(connected = true)
                "DISCONNECTED" -> updateUI(connected = false)
                "TRAFFIC" -> {
                    val down = intent?.getStringExtra("down") ?: "0 B"
                    val up = intent?.getStringExtra("up") ?: "0 B"
                    binding.tvStatus.text = "\u2193 $down  \u2191 $up"
                }
                "ERROR" -> {
                    val msg = intent.getStringExtra("message") ?: "Unknown error"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    updateUI(connected = false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener {
            if (isConnected) {
                stopVpn()
            } else {
                requestVpnPermissionAndStart()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        val config = VlessConfig.load(this)
        if (config.serverAddress.isBlank() || config.uuid.isBlank()) {
            Toast.makeText(this, "Please configure server address and UUID in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        // Show connecting state immediately
        binding.btnConnect.text = "Connecting..."
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "Connecting..."

        val intent = Intent(this, VlessVpnService::class.java).apply {
            action = VlessVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, VlessVpnService::class.java).apply {
            action = VlessVpnService.ACTION_STOP
        }
        startService(intent)
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
        } else {
            binding.btnConnect.text = getString(R.string.connect)
            binding.btnConnect.backgroundTintList = ColorStateList.valueOf(getColor(R.color.connect_blue))
            binding.tvStatus.text = getString(R.string.status_disconnected)
            binding.tvStatus.setTextColor(getColor(R.color.disconnected_gray))
            binding.ivStatusIcon.setImageResource(R.drawable.ic_vpn_off)
        }
    }
}
