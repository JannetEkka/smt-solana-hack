package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import io.github.jannetekka.smtworld.clockin.Grading
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jannetekka.smtworld.market.Prices
import io.github.jannetekka.smtworld.smt.SmtFeed

/**
 * SMT's calls, native. Replaces the old WebView tab: SMT World's site has its own Calls tab now,
 * so showing the whole site here duplicated the game. The site is one tap away in the browser.
 * Before today's first Clock In the board stays shut, so your call is made before you see SMT's.
 */
@Composable
fun SmtScreen(s: UiState, openUrl: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text("What SMT is doing", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Note("SMT is a crypto trading agent. Six advisors vote on each coin and a judge decides. It trades only when " +
                    "the judge is confident; most hours it sits out. In Clock In, SMT is scored only on real calls (LONG or SHORT). " +
                    "When it sits out you still see which way it leans, and why.")
                val ages = Prices.COINS.mapNotNull { s.smtAgeHours(it) }
                ages.minOrNull()?.let { Note("Feed written ${Format.age(it)}." + if (it > 6) " That's old." else "", if (it > 6) Down else Muted) }
                OutlinedButton(onClick = { openUrl(SmtFeed.SITE) }, modifier = Modifier.fillMaxWidth().focusRing()) {
                    Text("Open SMT World (the full web app)", maxLines = 1)
                }
            }
        }
        if (s.walletApp && !s.clockedInToday) {
            item {
                SectionCard {
                    Text("Make today's call first", color = GoldSoft, fontWeight = FontWeight.SemiBold)
                    Note("SMT's calls for today open here after your first Clock In, so your call is your own.")
                }
            }
        } else {
            item { SmtBoardCard(s) }
        }
    }
}

/** SMT's board: each coin's call or lean, conviction and price; tap a coin for the reasons. */
@Composable
internal fun SmtBoardCard(s: UiState) = SectionCard {
    Text("SMT's board", color = GoldSoft, style = MaterialTheme.typography.titleMedium)
    s.smtError?.let { Note(it, Down) }
    if (s.smt.isEmpty()) { Note("Loading SMT's calls…"); return@SectionCard }
    var open by remember { mutableStateOf<String?>(null) }
    Prices.COINS.mapNotNull { s.smt[it] }.forEach { c ->
        // Two lines per coin so nothing is squeezed on a narrow phone or with large system fonts:
        // coin and price on top, SMT's lean and conviction underneath.
        TextButton(onClick = { open = if (open == c.coin) null else c.coin }, modifier = Modifier.fillMaxWidth().focusRing()) {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.coin, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(Format.price(s.prices[c.coin]?.px), color = Muted, maxLines = 1, softWrap = false)
                    Text(if (open == c.coin) "  ▴" else "  ▾", color = Muted)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(leanLabel(c.lean), leanColor(c.lean))
                    Spacer(Modifier.width(8.dp))
                    Text(if (Grading.isRealCall(c.action)) "calls ${c.action} · ${c.convictionPct}%" else "sitting out · leans",
                        color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (open == c.coin) SmtCallBody(c, s.smtAgeHours(c.coin))
    }
}
