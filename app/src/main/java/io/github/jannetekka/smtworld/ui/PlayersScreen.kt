package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jannetekka.smtworld.clockin.Leaderboard
import io.github.jannetekka.smtworld.solana.DevnetRpc
import io.github.jannetekka.smtworld.solana.MemoTransaction

/** Everyone vs SMT, read straight from the chain. */
@Composable
fun PlayersScreen(s: UiState, vm: UiActions, openUrl: (String) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshBoard() }
    val rows = s.boardRows
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard {
                Text("Everyone vs SMT", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Note("Every Clock In, from every player, also names one public address on Solana. This list is read " +
                    "straight from that address: there's no server, and nobody can edit it. A player is ranked after " +
                    "${Leaderboard.MIN_GRADED} graded calls.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openUrl(DevnetRpc.explorerAddress(MemoTransaction.REGISTRY)) }, modifier = Modifier.focusRing()) {
                        Text("See the address on Explorer")
                    }
                    OutlinedButton(onClick = { vm.refreshBoard() }, enabled = !s.boardLoading, modifier = Modifier.focusRing()) {
                        Text(if (s.boardLoading) "Loading…" else "Refresh")
                    }
                }
            }
        }
        s.boardError?.let { item { Note(it, Down) } }
        if (rows.all { it.player == Leaderboard.SMT && it.calls == 0 }) {
            item { Note(if (s.boardLoading) "Reading the chain…" else "No Clock Ins on the board yet. Yours will be the first.") }
        } else {
            itemsIndexed(rows, key = { _, r -> r.player }) { i, r -> PlayerRow(i + 1, r, s.address) }
        }
    }
}

@Composable
private fun PlayerRow(rank: Int, r: Leaderboard.Row, me: String?) = SectionCard {
    val isSmt = r.player == Leaderboard.SMT
    val isMe = r.player == me
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (r.ranked) "#$rank" else "—", color = if (r.ranked) GoldSoft else Muted, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when { isSmt -> "SMT (the AI)"; isMe -> "You · ${Format.shortAddr(r.player)}"; else -> Format.shortAddr(r.player) },
                color = if (isSmt) Gold else Ink, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                if (isSmt) "${r.right} right of ${r.graded} calls it leaned on"
                else if (r.ranked) "${r.right} right of ${r.graded} graded · ${r.calls} calls"
                else "${r.calls} calls · ${r.graded} graded (ranked from ${Leaderboard.MIN_GRADED})",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(r.pct?.let { "$it%" } ?: "—", color = if (isSmt) Gold else Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}
