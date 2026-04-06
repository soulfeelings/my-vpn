package com.vlessvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.v2ray.ang.service.TProxyService
import go.Seq
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
    private var trafficJob: Job? = null
    private var startRxBytes = 0L
    private var startTxBytes = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Initialize gomobile runtime
        Seq.setContext(applicationContext)
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
                broadcastLog("Loading config...")
                val config = VlessConfig.load(this@VlessVpnService)
                val configJson = config.toXrayJson()
                broadcastLog("Server: ${config.serverAddress}:${config.serverPort}")
                broadcastLog("Security: ${config.security}, Network: ${config.network}")

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
                broadcastLog("TUN interface established, fd=$fd")

                // Protect the VPN fd
                protect(fd)

                // Initialize V2Ray core environment (assetPath, xudpBaseKey)
                broadcastLog("Initializing V2Ray core...")
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")

                // Initialize V2Ray with CoreController API
                val callback = object : CoreCallbackHandler {
                    override fun onEmitStatus(status: Long, msg: String?): Long {
                        Log.d(TAG, "V2Ray status: $status - $msg")
                        broadcastLog("core: $msg")
                        return 0
                    }

                    override fun shutdown(): Long {
                        Log.d(TAG, "V2Ray shutdown callback")
                        vpnInterface?.close()
                        vpnInterface = null
                        return 0
                    }

                    override fun startup(): Long {
                        Log.d(TAG, "V2Ray startup callback")
                        return 0
                    }
                }

                val controller = Libv2ray.newCoreController(callback)
                coreController = controller

                broadcastLog("Starting V2Ray loop...")
                // Pass 0 as fd: tun2socks handles the TUN, xray just provides SOCKS5
                controller.startLoop(configJson, 0)

                if (!controller.isRunning) {
                    throw Exception("V2Ray core failed to start")
                }

                broadcastLog("V2Ray core started successfully")

                // Write tun2socks YAML config and start it
                broadcastLog("Starting tun2socks...")
                val tproxyConfig = """
                    |tunnel:
                    |  mtu: 1500
                    |  ipv4: 10.1.0.1
                    |socks5:
                    |  port: 10808
                    |  address: 127.0.0.1
                    |  udp: 'udp'
                    |misc:
                    |  tcp-read-write-timeout: 300000
                    |  udp-read-write-timeout: 60000
                    |  log-level: warn
                """.trimMargin()
                val tproxyFile = File(filesDir, "hev-socks5-tunnel.yaml")
                tproxyFile.writeText(tproxyConfig)

                try {
                    TProxyService.TProxyStartService(tproxyFile.absolutePath, fd)
                    broadcastLog("tun2socks started")
                } catch (e: Throwable) {
                    broadcastLog("tun2socks error: ${e.message}")
                    throw e
                }

                isRunning = true
                broadcastState("CONNECTED")

                // Record baseline traffic
                startRxBytes = TrafficStats.getTotalRxBytes()
                startTxBytes = TrafficStats.getTotalTxBytes()

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, createNotification("Connected", "0 B", "0 B"))
                }

                // Start traffic stats update loop
                startTrafficMonitor()

                Log.i(TAG, "VPN connected successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                val errorMsg = e.message ?: "Failed to start VPN"
                broadcastLog("ERROR: $errorMsg")
                broadcastState("ERROR", errorMsg)
                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, createNotification("Error: $errorMsg"))
                }
                // Keep notification visible for 3 seconds so user can see the error
                delay(3000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        trafficJob?.cancel()
        trafficJob = null
        serviceScope.launch {
            try {
                TProxyService.TProxyStopService()
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping tun2socks", e)
            }
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

    private fun startTrafficMonitor() {
        trafficJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(2000)
                val rxBytes = TrafficStats.getTotalRxBytes() - startRxBytes
                val txBytes = TrafficStats.getTotalTxBytes() - startTxBytes
                val downStr = formatBytes(rxBytes)
                val upStr = formatBytes(txBytes)

                withContext(Dispatchers.Main) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, createNotification("Connected", downStr, upStr))
                }

                broadcastTraffic(downStr, upStr)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun broadcastTraffic(down: String, up: String) {
        val intent = Intent(BROADCAST_ACTION).apply {
            setPackage(packageName)
            putExtra("state", "TRAFFIC")
            putExtra("down", down)
            putExtra("up", up)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        val intent = Intent(BROADCAST_ACTION).apply {
            setPackage(packageName)
            putExtra("state", "LOG")
            putExtra("log", msg)
        }
        sendBroadcast(intent)
    }

    private fun broadcastState(state: String, message: String? = null) {
        Log.d(TAG, "Broadcasting state: $state ${message ?: ""}")
        val intent = Intent(BROADCAST_ACTION).apply {
            setPackage(packageName)
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

    private fun createNotification(status: String, down: String? = null, up: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VLESS VPN - $status")
            .setSmallIcon(R.drawable.ic_vpn_on)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (down != null && up != null) {
            builder.setContentText("\u2193 $down  \u2191 $up")
            builder.setStyle(NotificationCompat.BigTextStyle()
                .bigText("\u2193 Download: $down\n\u2191 Upload: $up"))
        } else {
            builder.setContentText(status)
        }

        return builder.build()
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
