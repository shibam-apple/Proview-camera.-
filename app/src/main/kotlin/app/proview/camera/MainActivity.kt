package app.proview.camera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.proview.camera.device.DeviceProbe
import app.proview.camera.device.DeviceReport
import app.proview.camera.ui.DeviceCheckScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val report by produceState<DeviceReport?>(initialValue = null) {
                value = withContext(Dispatchers.Default) { DeviceProbe(applicationContext).probe() }
            }
            DeviceCheckScreen(report = report, onShare = { report?.let(::share) })
        }
    }

    private fun share(report: DeviceReport) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Proview device report")
            putExtra(Intent.EXTRA_TEXT, report.toText())
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }
}
