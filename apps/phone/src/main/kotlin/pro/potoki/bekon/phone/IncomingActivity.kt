package pro.potoki.bekon.phone

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class IncomingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open = this
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        setContent {
            pro.potoki.bekon.phone.ui.BekonPhoneTheme {
            val shown = remember { mutableStateOf(number.ifBlank { "Incoming call" }) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(shown.value, fontSize = 22.sp)
                Button(
                    onClick = {
                        CallService.pickup(this@IncomingActivity)
                        finish()
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Answer") }
                Button(
                    onClick = {
                        CallService.cancel(this@IncomingActivity)
                        finish()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Decline") }
            }
            }
        }
    }

    override fun onDestroy() {
        if (open === this) open = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NUMBER = "number"

        @Volatile
        private var open: IncomingActivity? = null

        fun finishIfOpen() {
            open?.runOnUiThread { open?.finish() }
        }
    }
}
