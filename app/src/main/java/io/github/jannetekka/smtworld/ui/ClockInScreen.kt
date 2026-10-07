@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.jannetekka.smtworld.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.solana.DevnetRpc

@Composable
fun ClockInScreen(s: UiState, vm: UiActions, onWallet: (WalletAction) -> Unit, openUrl: (String) -> Unit) {
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(s.send) {
        if (s.send is SendState.Done && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StreakCard(s) }
        item { WalletCard(s, onWallet, openUrl) }
        if (s.walletApp) item { CallCard(s, vm, onWallet) }
        val done = s.send as? SendState.Done
        if (done != null && s.lastSent != null) item { RevealCard(s, done.signature, openUrl) }
        if (!s.walletApp || s.clockedInToday) item { BoardCard(s) }
        item { RemindersCard(s, vm) }
        item {
            Note("Devnet only: no real money moves. Prices: Binance public data, CoinGecko when Binance is unavailable. " +
                "SMT's calls: the public SMT World feed.")
        }
    }
}

enum class WalletAction { CONNECT, CLOCK_IN, FORGET }

@Composable
private fun StreakCard(s: UiState) = SectionCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🔥", fontSize = 40.sp)
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column {
            Text("${s.streak}-day streak", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (s.clockedInToday) "Clocked in today ✓  Come back tomorrow to keep it going."
                else if (s.streak > 0) "Not yet today. Make a call before midnight to keep it."
                else "Make one call a day. Your streak lives on Solana, not on this phone.",
                color = Muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (s.bestStreak > 1) Note("Best streak: ${s.bestStreak} days")
}

@Composable
private fun WalletCard(s: UiState, onWallet: (WalletAction) -> Unit, openUrl: (String) -> Unit) = SectionCard {
    when {
        !s.walletApp -> {
            Text("No Solana wallet app on this device", color = Ink, fontWeight = FontWeight.SemiBold)
            Note("Clock In needs a wallet that supports Mobile Wallet Adapter, such as Phantom or Solflare, set to Devnet. " +
                "Until then you can still follow SMT's calls below and browse SMT World.")
        }
        s.address == null -> {
            Text("Connect a devnet wallet", color = Ink, fontWeight = FontWeight.SemiBold)
            Note("Set Phantom or Solflare to Devnet first. Each Clock In costs about 0.000005 devnet SOL, which is free from the faucet.")
            Button(onClick = { onWallet(WalletAction.CONNECT) }, modifier = Modifier.focusRing()) { Text("Connect wallet") }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("DEVNET", GoldSoft)
                Text(Format.shortAddr(s.address), color = Ink, fontWeight = FontWeight.SemiBold)
                Text(s.balanceSol?.let { String.format(java.util.Locale.US, "%.4f SOL", it) } ?: "", color = Muted)
            }
            if (s.balanceSol != null && s.balanceSol < 0.0001) {
                Note("This wallet has no devnet SOL yet. Get 1 SOL free from the faucet, then come back.", GoldSoft)
                OutlinedButton(onClick = { openUrl("https://faucet.solana.com") }, modifier = Modifier.focusRing()) { Text("Open the devnet faucet") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openUrl(DevnetRpc.explorerAddress(s.address)) }, modifier = Modifier.focusRing()) { Text("See it on Explorer") }
                TextButton(onClick = { onWallet(WalletAction.FORGET) }, modifier = Modifier.focusRing()) { Text("Use another wallet") }
            }
        }
    }
}

@Composable
private fun CallCard(s: UiState, vm: UiActions, onWallet: (WalletAction) -> Unit) = SectionCard {
    val busy = s.send is SendState.Preparing || s.send is SendState.InWallet
    Text(
        if (s.clockedInToday) "Another call? Where will ${s.coin} be in ${ClockInCall.HORIZON_HOURS} hours?"
        else "Today's call: where will ${s.coin} be in ${ClockInCall.HORIZON_HOURS} hours?",
        color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
    )
    Note("Make your call first. SMT's call on the same coin is revealed after yours is on chain.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Prices.COINS.forEach { c ->
            FilterChip(
                selected = s.coin == c,
                onClick = { vm.selectCoin(c) },
                enabled = !busy,
                label = { Text(c) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy, labelColor = Ink),
                modifier = Modifier.focusRing(8),
            )
        }
    }
    val q = s.prices[s.coin]
    Text("Now ${Format.price(q?.px)}" + (q?.let { "  ·  ${it.source}" } ?: ""), color = Muted)
    s.pricesError?.let { Note(it, Down) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(Dir.UP, Dir.DOWN).forEach { d ->
            val chosen = s.pick == d
            Button(
                onClick = { vm.pick(d) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (chosen) dirColor(d) else NavyLine,
                    contentColor = if (chosen) Navy else Ink,
                ),
                modifier = Modifier.weight(1f).height(64.dp).focusRing(),
            ) { Text(dirLabel(d), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
    Button(
        onClick = { onWallet(WalletAction.CLOCK_IN) },
        enabled = s.pick != null && !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp).focusRing(),
    ) {
        if (busy) { CircularProgressIndicator(Modifier.padding(end = 8.dp).height(20.dp).width(20.dp), color = Navy, strokeWidth = 2.dp) }
        Text(
            when (s.send) {
                SendState.Preparing -> "Getting the price and a blockhash…"
                SendState.InWallet -> "Approve it in your wallet…"
                else -> "Clock in on Solana"
            },
            fontWeight = FontWeight.Bold,
        )
    }
    (s.send as? SendState.Failed)?.let {
        Note(it.message, Down)
        TextButton(onClick = { vm.resetSend() }, modifier = Modifier.focusRing()) { Text("OK") }
    }
}

@Composable
private fun RevealCard(s: UiState, signature: String, openUrl: (String) -> Unit) = SectionCard {
    val oc = s.lastSent ?: return@SectionCard
    Text("You're on chain ✓", color = Up, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("You called ${oc.call.coin} ${dirLabel(oc.call.you)} at ${Format.price(oc.call.entryPx)}. " +
        "It's graded at ${Format.time(oc.dueAt)}, and you'll get a notification.", color = Ink)
    OutlinedButton(onClick = { openUrl(DevnetRpc.explorerTx(signature)) }, modifier = Modifier.focusRing()) { Text("View the transaction") }
    Note("Memo: ${oc.call.toMemo()}")
    Spacer(Modifier.height(4.dp))
    Text("Now, SMT's call on ${oc.call.coin}", color = GoldSoft, style = MaterialTheme.typography.titleMedium)
    val smt = s.smt[oc.call.coin]
    if (smt == null) Note("SMT's feed was unreachable, so SMT has no call recorded this time.", Down)
    else SmtCallBody(smt, s.smtAgeHours(oc.call.coin))
}

@Composable
private fun BoardCard(s: UiState) = SectionCard {
    Text("SMT's board", color = GoldSoft, style = MaterialTheme.typography.titleMedium)
    s.smtError?.let { Note(it, Down) }
    if (s.smt.isEmpty()) { Note("Loading SMT's calls…"); return@SectionCard }
    var open by remember { mutableStateOf<String?>(null) }
    Prices.COINS.mapNotNull { s.smt[it] }.forEach { c ->
        TextButton(onClick = { open = if (open == c.coin) null else c.coin }, modifier = Modifier.fillMaxWidth().focusRing()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c.coin, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                Pill(leanLabel(c.lean), leanColor(c.lean))
                Spacer(Modifier.width(8.dp))
                Text(if (c.action == "WAIT") "sitting out · ${c.convictionPct}%" else "${c.action} · ${c.convictionPct}%", color = Muted)
                Spacer(Modifier.weight(1f))
                Text(Format.price(s.prices[c.coin]?.px), color = Muted)
            }
        }
        if (open == c.coin) SmtCallBody(c, s.smtAgeHours(c.coin))
    }
}

@Composable
private fun RemindersCard(s: UiState, vm: UiActions) = SectionCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Daily reminder", color = Ink, fontWeight = FontWeight.SemiBold)
            Note("A nudge at 9:00 if you haven't clocked in, and a ping when each call is graded.")
        }
        Switch(checked = s.remindersOn, onCheckedChange = { vm.setReminders(it) }, modifier = Modifier.focusRing(20))
    }
}
