package com.example.wearnote.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Helper class for managing Wi-Fi connections on Wear OS
 * Uses proper APIs that require user interaction and authorization
 */
object WiFiConnectionHelper {
    private const val TAG = "WiFiConnectionHelper"
    
    /**
     * Check if device is currently connected via WiFi
     */
    fun isConnectedToWiFi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Check if WiFi is available (not necessarily connected)
     */
    fun isWiFiAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val networks = cm.allNetworks
            for (network in networks) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * Get WiFi connection status information
     */
    fun getWiFiStatusMessage(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        
        if (network == null) {
            return "No active network connection"
        }
        
        val capabilities = cm.getNetworkCapabilities(network)
        
        return when {
            capabilities == null -> "Network capabilities unavailable"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 
                "Connected via Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 
                "Connected via Cellular (Wi-Fi not available)"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 
                "Connected via Bluetooth (Wi-Fi not available)"
            else -> "Connected via unknown transport"
        }
    }
    
    /**
     * Request WiFi network connection using WifiNetworkSpecifier
     * This will show a system dialog for user to approve the connection
     * 
     * Note: This API requires user interaction and cannot automatically connect to WiFi
     * 
     * @param ssid The SSID of the WiFi network to connect to
     * @param password The password for the WiFi network (can be null for open networks)
     * @param callback Callback to receive connection result
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun requestWiFiConnection(
        context: Context,
        ssid: String,
        password: String?,
        callback: NetworkCallback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "WiFi Network Request API requires Android Q (API 29) or higher")
            callback.onUnavailable()
            return
        }
        
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
            
            if (password != null) {
                specifierBuilder.setWpa2Passphrase(password)
            }
            
            val specifier = specifierBuilder.build()
            
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "WiFi network available: $ssid")
                    callback.onAvailable(network)
                }
                
                override fun onUnavailable() {
                    Log.d(TAG, "WiFi network unavailable: $ssid")
                    callback.onUnavailable()
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "WiFi network lost: $ssid")
                    callback.onLost(network)
                }
            }
            
            cm.requestNetwork(request, networkCallback)
            Log.d(TAG, "WiFi connection requested for SSID: $ssid")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting WiFi connection", e)
            callback.onUnavailable()
        }
    }
    
    /**
     * Suggest user to enable WiFi auto-connect in settings
     * This is the recommended approach for Wear OS devices
     */
    fun suggestWiFiAutoConnect(context: Context): String {
        return """
            To enable automatic WiFi connection on your watch:
            
            1. Open Settings on your watch
            2. Go to Connectivity → Wi-Fi
            3. Enable 'Auto' mode
            4. Your watch will automatically connect to known WiFi networks when Bluetooth disconnects
            
            This is the recommended way to ensure reliable uploads on WiFi.
        """.trimIndent()
    }
    
    /**
     * Check network connection quality
     */
    fun getNetworkQuality(context: Context): NetworkQuality {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkQuality.NO_CONNECTION
        val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkQuality.NO_CONNECTION
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val bandwidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    capabilities.linkDownstreamBandwidthKbps
                } else {
                    0
                }
                
                when {
                    bandwidth >= 5000 -> NetworkQuality.EXCELLENT_WIFI
                    bandwidth >= 1000 -> NetworkQuality.GOOD_WIFI
                    else -> NetworkQuality.FAIR_WIFI
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 
                NetworkQuality.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 
                NetworkQuality.BLUETOOTH
            else -> NetworkQuality.UNKNOWN
        }
    }
    
    enum class NetworkQuality {
        NO_CONNECTION,
        BLUETOOTH,
        CELLULAR,
        FAIR_WIFI,
        GOOD_WIFI,
        EXCELLENT_WIFI,
        UNKNOWN
    }
    
    interface NetworkCallback {
        fun onAvailable(network: Network)
        fun onUnavailable()
        fun onLost(network: Network)
    }
}
