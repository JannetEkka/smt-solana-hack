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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import io.github.jannetekka.smtworld.clockin.OnChainCall

enum class AppTab(val label: String, val glyph: String, val title: String) {
    CLOCK_IN("Clock In", "⏱", "SMT Clock In"), CALLS("My calls", "📜", "My calls"),
    PLAYERS("Players", "🏆", "Everyone vs SMT"), WORLD("SMT World", "🌍", "SMT World")
}

@Composable
fun AppRoot(vm: AppViewModel, sender: ActivityResultSender, openUrl: (String) -> Unit, onShare: (OnChainCall) -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    AppScaffold(AppTab.entries[tab], onTab = { tab = it.ordinal }) {
        when (AppTab.entries[tab]) {
            AppTab.CLOCK_IN -> ClockInScreen(s, vm, onWallet = { a ->
                when (a) {
                    WalletAction.CONNECT -> vm.connect(sender)
                    WalletAction.CLOCK_IN -> vm.clockIn(sender)
                    WalletAction.FORGET -> vm.forgetWallet()
                }
            }, openUrl = openUrl, onShare = onShare)
            AppTab.CALLS -> HistoryScreen(s, vm, openUrl, onShare)
            AppTab.PLAYERS -> PlayersScreen(s, vm, openUrl)
            AppTab.WORLD -> WorldScreen(openUrl)
        }
    }
}

/** The app's chrome: title bar and bottom tabs. Separate from AppRoot so it renders without a view model. */
@Composable
fun AppScaffold(tab: AppTab, onTab: (AppTab) -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Navy,
        topBar = {
            TopAppBar(
                title = { Text(tab.title, color = Gold, fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = NavyRaised) {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedTextColor = Gold, unselectedTextColor = Muted, indicatorColor = NavyLine),
                        modifier = Modifier.focusRing(),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) { content() }
    }
}
