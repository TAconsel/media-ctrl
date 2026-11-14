package com.consel.mediactrl

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.InetAddress

/** Finds computers announcing _mediactrl._tcp over mDNS. Call from the main thread. */
class Discovery(context: Context) {
    companion object {
        const val SERVICE_TYPE = "_mediactrl._tcp"
        private const val TAG = "MediaCtrl"
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = context.mainExecutor
    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = HashMap<String, Any>()     // API 34+ ServiceInfoCallback per name
    private val resolveQueue = ArrayDeque<NsdServiceInfo>() // pre-34 resolves run one at a time
    private var resolving = false

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { resolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { lost(info.serviceName) }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery: $e")
            listener = null
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) {
            for (cb in infoCallbacks.values) {
                runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
            }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
        _servers.value = emptyList()
    }

    private fun found(name: String, addresses: List<InetAddress>, port: Int) {
        if (listener == null) return
        val addr = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull() ?: return
        val host = addr.hostAddress ?: return
        val server = Server(name, host, port)
        _servers.update { list -> (list.filterNot { it.name == name } + server).sortedBy { it.name.lowercase() } }
    }

    private fun lost(name: String) {
        _servers.update { list -> list.filterNot { it.name == name } }
        if (Build.VERSION.SDK_INT >= 34) {
            (infoCallbacks.remove(name) as? NsdManager.ServiceInfoCallback)?.let {
                runCatching { nsd.unregisterServiceInfoCallback(it) }
            }
        }
    }

    private fun resolve(info: NsdServiceInfo) {
        if (listener == null) return
        if (Build.VERSION.SDK_INT >= 34) resolveModern(info) else {
            resolveQueue.addLast(info)
            pump()
        }
    }

    @RequiresApi(34)
    private fun resolveModern(info: NsdServiceInfo) {
        val name = info.serviceName
        if (name in infoCallbacks) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                infoCallbacks.remove(name)
            }
            override fun onServiceUpdated(si: NsdServiceInfo) {
                found(name, si.hostAddresses, si.port)
            }
            override fun onServiceLost() {
                _servers.update { list -> list.filterNot { it.name == name } }
            }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks[name] = cb
        try {
            nsd.registerServiceInfoCallback(info, executor, cb)
        } catch (e: Exception) {
            infoCallbacks.remove(name)
            Log.w(TAG, "resolve $name: $e")
        }
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; pump() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                main.post {
                    resolving = false
                    si.host?.let { found(si.serviceName, listOf(it), si.port) }
                    pump()
                }
            }
        })
    }
}
