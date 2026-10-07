package io.github.jannetekka.smtworld

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import io.github.jannetekka.smtworld.ui.AppRoot
import io.github.jannetekka.smtworld.ui.AppViewModel
import io.github.jannetekka.smtworld.ui.SmtTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** MWA needs this registered before the activity starts; it carries the wallet hand-off. */
    private lateinit var sender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sender = ActivityResultSender(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { SmtTheme { AppRoot(vm, sender, ::openUrl, ::shareCall) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    private fun shareCall(oc: io.github.jannetekka.smtworld.clockin.OnChainCall) {
        try {
            io.github.jannetekka.smtworld.share.ShareCard.share(this, oc, vm.state.value.grades[oc.signature])
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser on this device: $url", Toast.LENGTH_LONG).show()
        }
    }
}
