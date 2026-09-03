package pro.potoki.bekon

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Transparent Activity that requests MediaProjection permission.
 * Only launched in non-root mode when screen capture permission is needed.
 */
class PermissionActivity : Activity() {

    companion object {
        private const val TAG = "PermissionActivity"
        private const val REQUEST_CODE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            AgentForegroundService.onCapturePermissionGranted(data, resultCode)
            Log.i(TAG, "Capture permission granted")
        } else {
            Log.w(TAG, "Capture permission denied")
        }
        finish()
    }
}
