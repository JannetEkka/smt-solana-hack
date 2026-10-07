@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jannetekka.smtworld.clockin.Grading
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.solana.DevnetRpc

@Composable
fun HistoryScreen(s: UiState, vm: UiActions, openUrl: (String) -> Unit, onShare: (OnChainCall) -> Unit = {}) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScoreCard(s) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your calls, read from devnet", color = GoldSoft, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.refresh() }, enabled = !s.loading, modifier = Modifier.focusRing()) { Text(if (s.loading) "Loading…" else "Refresh") }
            }
        }
        s.historyError?.let { item { Note(it, Down) } }
        if (s.address == null) item { Note("Connect a wallet on the Clock In tab to see your calls.") }
        else if (s.calls.isEmpty()) item { Note("No Clock Ins on this wallet yet.") }
        items(s.calls, key = { it.signature }) { c -> CallRow(c, s, openUrl, onShare) }
    }
}

@Composable
private fun ScoreCard(s: UiState) = SectionCard {
    Text("You vs SMT", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Row {
        ScoreCell("You", s.scoreYou, Modifier.weight(1f))
        ScoreCell("SMT", s.scoreSmt, Modifier.weight(1f))
    }
    Note("Each call is graded on the price move ${io.github.jannetekka.smtworld.clockin.ClockInCall.HORIZON_HOURS} hours later. " +
        "When SMT is sitting out, its lean is graded. When its personas cancel out or its feed is old, SMT isn't scored.")
}

@Composable
private fun ScoreCell(who: String, sc: Grading.Score, modifier: Modifier) = Column(modifier) {
    Text(who, color = Muted)
    Text(sc.pct?.let { "$it%" } ?: "—", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Text("${sc.right} right of ${sc.scored}", color = Muted)
}

@Composable
private fun CallRow(c: OnChainCall, s: UiState, openUrl: (String) -> Unit, onShare: (OnChainCall) -> Unit) = SectionCard {
    val g = s.grades[c.signature]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.call.coin, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(Format.dayTime(c.blockTime), color = Muted, modifier = Modifier.weight(1f))
        if (g != null) Text(Format.pct(g.movePct), color = if (g.movePct >= 0) Up else Down, fontWeight = FontWeight.Bold)
    }
    // Wraps instead of overflowing with large system fonts.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("You", color = Muted); Pill(dirLabel(c.call.you), dirColor(c.call.you))
        Spacer(Modifier.width(8.dp))
        Text("SMT", color = Muted); Pill(leanLabel(c.call.smtLean), leanColor(c.call.smtLean))
        Text("${c.call.smtConvictionPct}% ${c.call.smtAction.lowercase()}", color = Muted)
    }
    if (g == null) {
        Note(if (c.dueAt > s.nowSec) "Graded at ${Format.time(c.dueAt)} against the price then (entry ${Format.price(c.call.entryPx)})."
             else "Waiting for the price at ${Format.time(c.dueAt)}…")
    } else {
        Row {
            Text("You: ${outcomeMark(g.you)}", color = outcomeColor(g.you), modifier = Modifier.weight(1f))
            Text("SMT: ${outcomeMark(g.smt)}", color = outcomeColor(g.smt), modifier = Modifier.weight(1f))
        }
        Note("${Format.price(g.entryPx)} → ${Format.price(g.exitPx)} (${g.source})")
    }
    Row {
        TextButton(onClick = { openUrl(DevnetRpc.explorerTx(c.signature)) }, modifier = Modifier.focusRing()) { Text("Transaction ${c.signature.take(8)}…") }
        TextButton(onClick = { onShare(c) }, modifier = Modifier.focusRing()) { Text("Share") }
    }
}
