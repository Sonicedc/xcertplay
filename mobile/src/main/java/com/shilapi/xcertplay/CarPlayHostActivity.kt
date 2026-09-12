package com.shilapi.xcertplay

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [SurfaceView], forwards touch to
 * the active AirPlay session, and drives the complete wired bring-up through [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private lateinit var airPlayIdentity: AirPlayIdentity
    private val identification = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
    )
    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private val runtimeConfig: CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
        ch341MfiResetGpio = 0, // CH341 D0/CS0 -> open-drain MFi RST
        identification = identification,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }

    private var surfaceView: SurfaceView? = null
    private var statusView: TextView? = null
    private var reconnectButtons: View? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var awaitingVpnConsent = false
    private var vpnReady = false
    private var userLeaving = false
    private var restartGeneration = 0
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            currentSurface = holder.surface
            appendLog("Surface created")
            attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            currentSurface = holder.surface
            scheduleDisplaySize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (currentSurface === holder.surface) {
                currentSurface = null
                sink?.clearSurface(SCREEN_TYPE_MAIN, holder.surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, holder.surface)
            }
            appendLog("Surface destroyed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        setContentView(buildContentView())
        hideSystemBars()

        appendLog("Host started; CH341 1A86:5512 configured")
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    override fun onResume() {
        super.onResume()
        userLeaving = false
        hideSystemBars()
    }

    override fun onUserLeaveHint() {
        userLeaving = true
        super.onUserLeaveHint()
    }

    override fun onStop() {
        super.onStop()
        if (userLeaving && !isChangingConfigurations && !awaitingVpnConsent) {
            finishAndRemoveTask()
            shutdown(terminateProcess = true, reason = "activity left foreground")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        surfaceView?.post {
            val surface = surfaceView ?: return@post
            scheduleDisplaySize(surface.width, surface.height)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(applyDisplaySize)
        shutdown(
            terminateProcess = isFinishing && !isChangingConfigurations,
            reason = "activity destroyed",
        )
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this)
        val surface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            holder.addCallback(surfaceCallback)
            setOnTouchListener { view, event -> onTouch(view, event) }
        }
        val log = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = ""
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))

        val reconnect = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val mfiButton = Button(this).apply {
            text = "Reconnect MFi"
            setOnClickListener {
                restartCarPlay("Reconnect MFi requested")
            }
        }
        val iphoneButton = Button(this).apply {
            text = "Reconnect iPhone"
            setOnClickListener {
                restartCarPlay("Reconnect iPhone requested")
            }
        }
        val rotateButton = Button(this).apply {
            text = "Rotate"
            setOnClickListener {
                val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                requestedOrientation = if (portrait) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        reconnect.addView(mfiButton)
        reconnect.addView(iphoneButton)
        reconnect.addView(rotateButton)
        val reconnectParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )
        reconnectParams.setMargins(dp(12), dp(12), 0, 0)

        root.addView(surface)
        root.addView(log, statusParams)
        root.addView(reconnect, reconnectParams)
        surfaceView = surface
        statusView = log
        reconnectButtons = reconnect
        return root
    }

    private fun createAirPlayConfig(size: DisplaySize) = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(widthPixels = size.width, heightPixels = size.height),
    )

    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get() || controller != null) return
        val config = runtimeConfig
        val airPlayConfig = createAirPlayConfig(size)
        appendLog("Starting CarPlay controller at ${size.width}x${size.height}")
        Log.i(TAG, "starting controller display=${size.width}x${size.height}")
        val renderer = AndroidMediaSink(
            surface = null,
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = CarPlayMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) {
                    runOnUiThread {
                        appendLog("AirPlay session active")
                        statusView?.visibility = View.GONE
                        reconnectButtons?.visibility = View.GONE
                    }
                }

                override fun onSessionEnded(session: AirPlaySession) {
                    runOnUiThread {
                        appendLog("AirPlay session ended")
                        statusView?.visibility = View.VISIBLE
                        reconnectButtons?.visibility = View.VISIBLE
                    }
                }
            },
            media = media,
            reportStatus = { status -> setStatus(status.describe()) },
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
        )
        controller = next
        next.start()
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activeDisplaySize || size == pendingDisplaySize) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        activeDisplaySize = size
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun maybeStartCarPlay() {
        val size = activeDisplaySize ?: return
        if (!vpnReady || shuttingDown.get() || controller != null) return
        startCarPlay(size)
    }

    /** A resolution change requires a fresh /info advertisement, so rebuild the complete stack. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get()) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        controller = null
        sink = null
        teardownExecutor.execute {
            oldController?.close()
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) startCarPlay(size)
            }
        }
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    private fun onTouch(view: View, event: MotionEvent): Boolean {
        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        controller?.sendTouch(contacts)
        return true
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            statusView?.visibility = View.VISIBLE
            appendLog(message)
        }
    }

    private fun appendLog(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())}  $message"
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        statusView?.text = logLines.joinToString("\n")
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Discovering MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi coprocessor ready"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork -> "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val MAX_LOG_LINES = 120
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
    }

    private data class DisplaySize(val width: Int, val height: Int)
}
