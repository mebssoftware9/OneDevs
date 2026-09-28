package com.devbangs.onedevs.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether there is a usable connection.
 *
 * VALIDATED, not merely connected: a captive portal in a hotel or a SIM with
 * no data left both report an attached network that no request will survive.
 * Asking whether the system has proved the network reaches the internet is the
 * difference between "there is wifi" and "there is internet".
 *
 * Starts optimistic. The callback answers within a frame or two, and assuming
 * offline first would show every launch an offline screen it then took back.
 */
class Connectivity(context: Context) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        override fun onLost(network: Network) {
            _online.value = false
        }

        override fun onUnavailable() {
            _online.value = false
        }
    }

    init {
        manager?.registerDefaultNetworkCallback(callback)
        // Asked directly, and the answer is taken either way.
        //
        // The callback is no help here: with no default network at all it
        // never fires -- onUnavailable is not delivered to a default callback
        // -- so launching in airplane mode left the optimistic value standing
        // and the app waited on requests that could not leave the device.
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        _online.value = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
