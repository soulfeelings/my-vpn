package com.vlessvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class VlessVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.vlessvpn.app.START"
        const val ACTION_STOP = "com.vlessvpn.app.STOP"
        const val BROADCAST_ACTION = "com.vlessvpn.app.VPN_STATE"
        const val CHANNEL_ID = "vless_vpn_channel"
        const val NOTIFICATION_ID = 1

        var isRunning = false
            private set

        private const val TAG = "VlessVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
                startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        serviceScope.launch {
            try {
                val config = VlessConfig.load(this@VlessVpnService)
                val configJson = config.toXrayJson()

                // Write config to file
                val configFile = File(filesDir, "config.json")
                configFile.writeText(configJson)

                // Setup TUN interface
                val builder = Builder()
                    .setSession("VlessVPN")
                    .setMtu(1500)
                    .addAddress("10.1.0.1", 30)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // Exclude the app itself from VPN to prevent loops
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude app from VPN", e)
                }

                vpnInterface = builder.establish()
                val fd = vpnInterface?.fd ?: throw Exception("Failed to establish VPN interface")

                // Initialize V2Ray with new CoreController API
                val callback = object : CoreCallbackHandler {
                    override fun onEmitStatus(status: Long, msg: String?): Long {
                        Log.d(TAG, "V2Ray status: $status - $msg")
                        return 0
                    }

                    override fun shutdown(): Long {
                        vpnInterface?.close()
                        vpnInterface = null
                        return 0
                    }

                    override fun startup(): Long {
                        return fd.toLong()
                    }
                }

                val controller = Libv2ray.newCoreController(callback)
                coreController = controller

                controller.startLoop(configJson, fd)

                isRunning = true
                broadcastState("CONNECTED")

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, createNotification("Connected"))
                }

                Log.i(TAG, "VPN connected successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                broadcastState("ERROR", e.message ?: "Failed to start VPN")
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        serviceScope.launch {
            try {
                coreController?.stopLoop()
                coreController = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping V2Ray", e)
            }

            vpnInterface?.close()
            vpnInterface = null
            isRunning = false

            broadcastState("DISCONNECTED")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun broadcastState(state: String, message: String? = null) {
        val intent = Intent(BROADCAST_ACTION).apply {
            putExtra("state", state)
            message?.let { putExtra("message", it) }
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLESS VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_on)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        vpnInterface?.close()
        isRunning = false
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
