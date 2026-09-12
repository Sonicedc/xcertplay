package com.shilapi.xcertplay

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig

/**
 * Full-screen CarPlay host. It renders decoded video through a [SurfaceView], forwards touch to
 * the active AirPlay session, and drives the complete wired bring-up through [CarPlayController].
 *
 * A deployment must replace [runtimeConfig] with the real Apple and CH341 USB identities measured
 * on the target unit. Until then the activity shows a status overlay and the stack stays inert.
 */
class CarPlayHostActivity : ComponentActivity() {
    // Deployer: populate with the target's real VID/PID pairs, for example:
    // CarPlayRuntimeConfig(
    //     iphoneDevices = listOf(UsbDeviceId(0x05ac, 0x12a8)),
    //     ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
    //     identification = identification,
    // )
    private val runtimeConfig: CarPlayRuntimeConfig? = null

    private val airPlayConfig = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "xcertplay-device",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val airPlayIdentity = AirPlayIdentity.generate()
    private val identification = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 1,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startCarPlay() else setStatus("VPN consent was denied")
        }

    private var surfaceView: SurfaceView? = null
    private var statusView: TextView? = null
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            currentSurface = holder.surface
            attachSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (currentSurface === holder.surface) currentSurface = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        hideSystemBars()

        if (runtimeConfig == null) {
            setStatus("Deployment configuration required")
        } else {
            val consent = CarPlayVpnService.prepare(this)
            if (consent == null) startCarPlay() else vpnConsent.launch(consent)
        }
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
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = ""
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))
        root.addView(surface)
        root.addView(status, statusParams)
        surfaceView = surface
        statusView = status
        return root
    }

    private fun startCarPlay() {
        val config = runtimeConfig ?: return
        val renderer = AndroidMediaSink(null)
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = CarPlayMediaEngine(renderer)
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = media,
            reportStatus = { status -> setStatus(status.describe()) },
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
        statusView?.text = message
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
    }
}
