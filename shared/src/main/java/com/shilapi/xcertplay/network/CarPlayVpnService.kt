package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Android VPN tunnel, the NCM IPv6 bridge, and the AirPlay TCP :7000 listener.
 *
 * The caller binds to this service and calls [attach] after Android USB Host brought the NCM
 * bridge up. VPN consent is requested with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    private var serverSocket: ServerSocket? = null
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder = binder

    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticationClient?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (!active.compareAndSet(false, true)) return AttachResult.AlreadyStarted
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, 0)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { onTransportError() }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            val server = ServerSocket()
            server.bind(InetSocketAddress(address, config.port))
            serverSocket = server
            Thread(
                { acceptLoop(server, config, identity, pairings, mfi, listener, media) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
            AttachResult.Started
        } catch (error: Exception) {
            release()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun acceptLoop(
        server: ServerSocket,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticationClient?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val session = AirPlaySession(
                    socket = socket,
                    config = config,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = object : AirPlaySessionListener by listener {
                        override fun onSessionEnded(session: AirPlaySession) {
                            removeSession(session)
                            listener.onSessionEnded(session)
                        }
                    },
                    media = media,
                )
                addSession(session)
                session.start()
            }
        } catch (_: IOException) {
            // The server socket is closed during teardown.
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun onTransportError() {
        Thread(
            {
                release()
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun release() {
        active.set(false)
        serverSocket?.close()
        serverSocket = null
        synchronized(sessionsLock) {
            sessions.toList().forEach(AirPlaySession::close)
            sessions.clear()
        }
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::/64"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}
