package com.shilapi.xcertplay.orchestration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2CsmChannel
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String) : CarPlayStatus()
}

/**
 * Wires the complete wired CarPlay path: MFi coprocessor discovery, iPhone USB bring-up, USBMUX,
 * Lockdown pairing, iAP2 control, NCM/VPN transport, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    private val reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
) : Closeable {
    private enum class Phase { IDLE, MFI, IPHONE, REENUMERATION, CONFIGURING, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        IphoneUsbMatcher(config.iphoneDevices),
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString()
    private val systemBuid = randomHex(20)

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2CsmChannel? = null
    @Volatile private var activeSession: AirPlaySession? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as CarPlayVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            listener.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) activeSession = null
            listener.onSessionEnded(session)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) =
            listener.onDeviceInfo(session, info)

        override fun onHostUiRequested(session: AirPlaySession) =
            listener.onHostUiRequested(session)

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) =
            listener.onCommand(session, type, params)
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
        attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        startMfi()
    }

    fun sendTouch(contacts: List<AirPlayContact>) {
        activeSession?.sendTouch(contacts)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        closeReceivers()
        unbindVpn()
        Thread(
            {
                csm?.close()
                csm = null
                mux?.close()
                mux = null
                mfiSession?.close()
                mfiSession = null
                executor.shutdownNow()
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startMfi() {
        phase = Phase.MFI
        if (config.ch341Devices.isNotEmpty()) {
            val host = Ch341UsbHost(appContext, usbManager, Ch341DeviceMatcher(config.ch341Devices))
            ch341Host = host
            ch341PermissionCloseable = host.registerPermissionReceiver(::onCh341Permission)
            onStatus(CarPlayStatus.DiscoveringMfi)
            val device = host.discover().firstOrNull()
            if (device == null) {
                onStatus(CarPlayStatus.Failed("No configured CH341 USB device found"))
            } else {
                requestCh341Permission(device)
            }
        } else {
            openLinuxMfi()
        }
    }

    private fun openLinuxMfi() {
        onStatus(CarPlayStatus.DiscoveringMfi)
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    startIphone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice) {
        try {
            when (val request = ch341Host!!.requestPermission(device)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted ->
                    onCh341Permission(Ch341UsbHost.PermissionResult.Granted(request.device))
                is Ch341UsbHost.PermissionRequest.Requested ->
                    onStatus(CarPlayStatus.RequestingMfiPermission)
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> openCh341(result.device)
            is Ch341UsbHost.PermissionResult.Denied ->
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
        }
    }

    private fun openCh341(device: UsbDevice) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    try {
                        val transport = Ch341I2cTransport(session)
                        mfiSession = MfiSession(MfiRuntime.scan(transport), session)
                        startIphone()
                    } catch (error: Throwable) {
                        session.close()
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> fail(result.error)
            }
        }
    }

    private fun startIphone() {
        phase = Phase.IPHONE
        onStatus(CarPlayStatus.DiscoveringIphone)
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.Failed("No configured iPhone USB device found"))
        } else {
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted ->
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                is IphoneUsbHost.PermissionRequest.Requested ->
                    onStatus(CarPlayStatus.RequestingIphonePermission)
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted ->
                if (phase == Phase.REENUMERATION) selectConfiguration(result.device)
                else beginReenumeration(result.device)
            is IphoneUsbHost.PermissionResult.Denied ->
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
        }
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                IphoneUsbHost.TransitionResult.CarPlayConfigurationSelected ->
                    selectConfiguration(device)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> requestIphonePermission(device)
            else -> Unit
        }
    }

    private fun selectConfiguration(device: UsbDevice) {
        phase = Phase.CONFIGURING
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.selectCarPlayConfigurationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.CarPlayConfigurationSelected -> openDataPaths(device)
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> Unit
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = (0 until device.configurationCount)
            .map(device::getConfiguration)
            .firstOrNull { it.id == CARPLAY_CONFIGURATION_ID }
            ?: throw IphoneUsbException.Protocol(
                "iPhone does not expose configuration $CARPLAY_CONFIGURATION_ID",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        try {
            val mux = Iap2UsbMuxHost.open(usbSession)
            this.mux = mux
            onStatus(CarPlayStatus.Pairing)
            val pairRecord = loadPairRecord() ?: LockdownPairingClient(mux)
                .pair(
                    label = config.label,
                    hostName = config.hostName,
                    hostId = hostId,
                    systemBuid = systemBuid,
                    totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
                )
                .pairRecord
                .also(savePairRecord)
            onStatus(CarPlayStatus.ConnectingControl)
            val carkit = LockdownCarKitClient(mux).open(pairRecord, config.label)
            val csm = Iap2CsmChannel.open(carkit)
            this.csm = csm

            if (!attachVpn(ncm)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }

            val mfi = mfiSession?.client
                ?: throw IphoneUsbException.DeviceUnavailable("MFi coprocessor client is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = airPlayConfig.deviceId.ifBlank { null },
            )
            onStatus(CarPlayStatus.RunningControl)
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = CONTROL_LOOP_TIMEOUT_MILLIS,
                onIncoming = { },
            )
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.TIMED_OUT -> CarPlayStatus.ControlEnded
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun attachVpn(ncm: NcmUsbBridge): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            ncm.close()
            return false
        }
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = config.hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiSession?.client,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            CarPlayVpnService.AttachResult.Started -> true
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun awaitVpnService(): CarPlayVpnService? {
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        mainHandler.post {
            try {
                val intent = Intent(appContext, CarPlayVpnService::class.java)
                if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                    vpnBound = false
                    vpnLatch.countDown()
                }
            } catch (_: Throwable) {
                vpnBound = false
                vpnLatch.countDown()
            }
        }
    }

    private fun unbindVpn() {
        if (!vpnBound) return
        vpnBound = false
        mainHandler.post {
            try {
                appContext.unbindService(serviceConnection)
            } catch (_: Exception) {
                // The service may have already been unbound.
            }
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable, ch341PermissionCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
        ch341PermissionCloseable = null
    }

    private fun fail(error: Throwable) {
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed) reportStatus(status)
        }
    }

    companion object {
        private const val CARPLAY_CONFIGURATION_ID = 6
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L

        private fun randomHex(bytes: Int): String {
            val data = ByteArray(bytes)
            SecureRandom().nextBytes(data)
            return data.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        }
    }
}
