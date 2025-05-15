package com.example.wearnote.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that monitors network conditions and optimizes upload strategies.
 * It helps determine the best upload method based on connection type and quality.
 */
class NetworkMonitorService : Service() {
    private val TAG = "NetworkMonitorService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Network status indicators
    private val isWifiConnected = AtomicBoolean(false)
    private val isCellularConnected = AtomicBoolean(false)
    private val isBluetoothTethered = AtomicBoolean(false)
    private val isFastConnection = AtomicBoolean(false)
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NetworkMonitorService created")
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()
        
        // Initial network check
        checkCurrentNetworkState()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "NetworkMonitorService started")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        Log.d(TAG, "NetworkMonitorService destroyed")
    }
    
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network is available")
                analyzeNetwork(network)
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                checkCurrentNetworkState()
            }
            
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                analyzeNetworkCapabilities(capabilities)
            }
        }
        
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }
    
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }
    
    private fun checkCurrentNetworkState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager?.activeNetwork
            if (network != null) {
                analyzeNetwork(network)
            } else {
                resetNetworkState()
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager?.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected) {
                checkLegacyNetworkType(networkInfo.type)
            } else {
                resetNetworkState()
            }
        }
    }
    
    private fun analyzeNetwork(network: Network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            if (capabilities != null) {
                analyzeNetworkCapabilities(capabilities)
                
                // Test actual network speed
                serviceScope.launch {
                    testNetworkSpeed()
                }
            }
        }
    }
    
    private fun analyzeNetworkCapabilities(capabilities: NetworkCapabilities) {
        isWifiConnected.set(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        isCellularConnected.set(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        isBluetoothTethered.set(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        
        // Check bandwidth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val bandwidth = capabilities.linkDownstreamBandwidthKbps
            isFastConnection.set(bandwidth >= 1000) // Consider 1Mbps as "fast enough"
            Log.d(TAG, "Network bandwidth: $bandwidth Kbps")
        }
        
        Log.d(TAG, "Network analysis: WiFi=${isWifiConnected.get()}, " +
                "Cellular=${isCellularConnected.get()}, " +
                "Bluetooth=${isBluetoothTethered.get()}, " +
                "Fast=${isFastConnection.get()}")
                
        // Update upload strategies based on connection
        updateUploadStrategy()
    }
    
    @Suppress("DEPRECATION")
    private fun checkLegacyNetworkType(type: Int) {
        when (type) {
            ConnectivityManager.TYPE_WIFI -> {
                isWifiConnected.set(true)
                isCellularConnected.set(false)
                isBluetoothTethered.set(false)
            }
            ConnectivityManager.TYPE_MOBILE -> {
                isWifiConnected.set(false)
                isCellularConnected.set(true)
                isBluetoothTethered.set(false)
            }
            ConnectivityManager.TYPE_BLUETOOTH -> {
                isWifiConnected.set(false)
                isCellularConnected.set(false)
                isBluetoothTethered.set(true)
            }
        }
        updateUploadStrategy()
    }
    
    private fun resetNetworkState() {
        isWifiConnected.set(false)
        isCellularConnected.set(false)
        isBluetoothTethered.set(false)
        isFastConnection.set(false)
        
        // Disable uploads when no network
        Log.d(TAG, "No network available")
    }
    
    private fun updateUploadStrategy() {
        // If we detect we're using Bluetooth tethering with slow speed, set a flag that
        // can be checked by the uploader to use more conservative settings
        val isSlowBluetooth = isBluetoothTethered.get() && !isFastConnection.get()
        
        // Save the status for GoogleDriveUploader to use
        getSharedPreferences("network_prefs", Context.MODE_PRIVATE).edit().apply {
            putBoolean("is_slow_bluetooth", isSlowBluetooth)
            putBoolean("is_wifi", isWifiConnected.get())
            putBoolean("is_fast_connection", isFastConnection.get())
            apply()
        }
        
        if (isSlowBluetooth) {
            Log.d(TAG, "Detected slow Bluetooth connection, will use conservative upload settings")
        }
    }
    
    private suspend fun testNetworkSpeed() {
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress("8.8.8.8", 53), 3000)
            socket.close()
            
            val pingTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Network ping test: $pingTime ms")
            
            // Consider connection fast if ping is less than 300ms
            isFastConnection.set(pingTime < 300)
            updateUploadStrategy()
        } catch (e: Exception) {
            Log.e(TAG, "Error testing network speed", e)
            // Default to assuming slow connection on error
            isFastConnection.set(false)
            updateUploadStrategy()
        }
    }
    
    companion object {
        // Helper method to check if we're on a slow Bluetooth connection
        fun isSlowBluetoothConnection(context: Context): Boolean {
            return context.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_slow_bluetooth", false)
        }
        
        // Helper method to check if we're on WiFi
        fun isWifiConnection(context: Context): Boolean {
            return context.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_wifi", false)
        }
        
        // Helper method to check if connection is fast enough
        fun isFastConnection(context: Context): Boolean {
            return context.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_fast_connection", true) // Default to true if not tested yet
        }
        
        // Start the network monitor service
        fun startMonitoring(context: Context) {
            context.startService(Intent(context, NetworkMonitorService::class.java))
        }
    }
}
