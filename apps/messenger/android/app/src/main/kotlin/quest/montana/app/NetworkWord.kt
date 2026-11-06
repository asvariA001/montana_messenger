package quest.montana.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the system says the default network is — for the diary, never shown. */
fun networkWord(c: Context): String {
    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return "no manager"
    val n = cm.activeNetwork ?: return "no active network"
    val caps = cm.getNetworkCapabilities(n) ?: return "no capabilities"
    return listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet")
        .filter { caps.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "other" }
}

/** Wi-Fi now, as the system names the default network (the daily road rides Wi-Fi alone). */
fun onWifi(c: Context): Boolean {
    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
