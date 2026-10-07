@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

private enum class Tab(val label: String, val glyph: String) {
    CLOCK_IN("Clock In", "⏱"), CALLS("My calls", "📜"), WORLD("SMT World", "🌍")
}

@Composable
fun AppRoot(vm: AppViewModel, sender: ActivityResultSender, openUrl: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableTabState() }
    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (Tab.entries[tab]) { Tab.CLOCK_IN -> "SMT Clock In"; Tab.CALLS -> "My calls"; Tab.WORLD -> "SMT World" },
                        color = Gold, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = NavyRaised) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedTextColor = Gold, unselectedTextColor = Muted, indicatorColor = NavyLine),
                        modifier = Modifier.focusRing(),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (Tab.entries[tab]) {
                Tab.CLOCK_IN -> ClockInScreen(s, vm, onWallet = { a ->
                    when (a) {
                        WalletAction.CONNECT -> vm.connect(sender)
                        WalletAction.CLOCK_IN -> vm.clockIn(sender)
                        WalletAction.FORGET -> vm.forgetWallet()
                    }
                }, openUrl = openUrl)
                Tab.CALLS -> HistoryScreen(s, vm, openUrl)
                Tab.WORLD -> WorldScreen(openUrl)
            }
        }
    }
}

private fun mutableTabState() = androidx.compose.runtime.mutableIntStateOf(0)
