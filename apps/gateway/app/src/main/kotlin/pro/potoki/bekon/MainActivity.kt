package pro.potoki.bekon

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import pro.potoki.bekon.touch.TouchService

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AgentForegroundService.onCapturePermissionGranted(result.data!!, result.resultCode)
            Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startButton: Button = findViewById(R.id.button_start_service)
        val stopButton: Button = findViewById(R.id.button_stop_service)
        val requestCaptureButton: Button = findViewById(R.id.button_request_capture)
        val enableTouchButton: Button = findViewById(R.id.button_enable_touch)
        val tapCenterButton: Button = findViewById(R.id.button_tap_center)
        val swipeUpButton: Button = findViewById(R.id.button_swipe_up)
        val pressHomeButton: Button = findViewById(R.id.button_press_home)

        startButton.setOnClickListener {
            AgentForegroundService.start(this)
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            AgentForegroundService.stop(this)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        }

        requestCaptureButton.setOnClickListener {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }

        enableTouchButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        tapCenterButton.setOnClickListener {
            val tc = TouchService.instance?.controller
            if (tc != null) {
                tc.tap(540f, 960f)
            } else {
                Toast.makeText(this, "Touch service not connected", Toast.LENGTH_SHORT).show()
            }
        }

        swipeUpButton.setOnClickListener {
            val tc = TouchService.instance?.controller
            if (tc != null) {
                tc.swipe(540f, 1200f, 540f, 400f)
            } else {
                Toast.makeText(this, "Touch service not connected", Toast.LENGTH_SHORT).show()
            }
        }

        pressHomeButton.setOnClickListener {
            val tc = TouchService.instance?.controller
            if (tc != null) {
                tc.home()
            } else {
                Toast.makeText(this, "Touch service not connected", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
