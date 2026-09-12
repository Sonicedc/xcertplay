package com.shilapi.xcertplay

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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

/**
 * Full-screen CarPlay host. It renders decoded video through a [SurfaceView], forwards touch to
 * the active AirPlay session, and drives the complete wired bring-up through [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private val airPlayConfig = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "xcertplay-device",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private lateinit var airPlayIdentity: AirPlayIdentity
    private val identification = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 1,
    )
    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private val runtimeConfig: CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
        identification = identification,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startCarPlay() else setStatus("VPN consent was denied")
        }

    private var surfaceView: SurfaceView? = null
    private var statusView: TextView? = null
    private var reconnectButtons: View? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private val logLines = ArrayDeque<String>()

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            currentSurface = holder.surface
            appendLog("Surface created")
            attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (currentSurface === holder.surface) currentSurface = null
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
        if (consent == null) startCarPlay() else vpnConsent.launch(consent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        controller?.close()
        controller = null
        sink?.close()
        sink = null
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
                appendLog("Reconnect MFi requested")
                controller?.reconnectMfi()
            }
        }
        val iphoneButton = Button(this).apply {
            text = "Reconnect iPhone"
            setOnClickListener {
                appendLog("Reconnect iPhone requested")
                controller?.reconnectIphone()
            }
        }
        reconnect.addView(mfiButton)
        reconnect.addView(iphoneButton)
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

    private fun startCarPlay() {
        val config = runtimeConfig
        appendLog("Starting CarPlay controller")
        val renderer = AndroidMediaSink(null)
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
                        reconnectButtons?.visibility = View.GONE
                    }
                }

                override fun onSessionEnded(session: AirPlaySession) {
                    runOnUiThread {
                        appendLog("AirPlay session ended")
                        reconnectButtons?.visibility = View.VISIBLE
                    }
                }
            },
            media = media,
            reportStatus = { status -> setStatus(status.describe()) },
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
        )
        controller = next
        next.start()
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
        appendLog(message)
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
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val MAX_LOG_LINES = 120
    }
}
