package io.github.jannetekka.smtworld.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.Outcome
import io.github.jannetekka.smtworld.smt.SmtCall

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NavyRaised),
        shape = RoundedCornerShape(16.dp),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun Note(text: String, color: Color = Muted, modifier: Modifier = Modifier) =
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier)

fun dirColor(d: Dir) = if (d == Dir.UP) Up else Down
fun leanColor(l: Lean) = when (l) { Lean.UP -> Up; Lean.DOWN -> Down; Lean.FLAT -> Muted }
fun dirLabel(d: Dir) = if (d == Dir.UP) "▲ UP" else "▼ DOWN"
fun leanLabel(l: Lean) = when (l) { Lean.UP -> "▲ UP"; Lean.DOWN -> "▼ DOWN"; Lean.FLAT -> "— FLAT" }
fun outcomeMark(o: Outcome) = when (o) { Outcome.RIGHT -> "✓ right"; Outcome.WRONG -> "✗ wrong"; Outcome.NOT_SCORED -> "not scored" }
fun outcomeColor(o: Outcome) = when (o) { Outcome.RIGHT -> Up; Outcome.WRONG -> Down; Outcome.NOT_SCORED -> Muted }

/** SMT's call with its reasons: the committee verdict, then each persona that voted a side. */
@Composable
fun SmtCallBody(call: SmtCall, ageHours: Double?) {
    val headline = when (call.action) {
        "LONG", "SHORT" -> "SMT calls ${call.action} at ${call.convictionPct}% conviction"
        else -> "SMT is sitting out (${call.convictionPct}% conviction), leaning ${leanLabel(call.lean)}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(leanLabel(call.lean), leanColor(call.lean))
        Spacer(Modifier.width(8.dp))
        Text(headline, color = Ink, fontWeight = FontWeight.SemiBold)
    }
    if (call.why.isNotBlank()) Text(call.why, color = Ink, style = MaterialTheme.typography.bodyMedium)
    val voted = call.votes.filter { it.dir == "LONG" || it.dir == "SHORT" }
    if (voted.isNotEmpty()) {
        Text("Who voted", color = GoldSoft, style = MaterialTheme.typography.labelLarge)
        voted.forEach { v ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (v.dir == "LONG") "▲" else "▼", if (v.dir == "LONG") Up else Down)
                    Spacer(Modifier.width(8.dp))
                    Text("${v.name} · ${(v.conf * 100).toInt()}%", color = Ink, fontWeight = FontWeight.Medium)
                }
                if (v.say.isNotBlank()) Note(v.say)
            }
        }
    }
    ageHours?.let { Note("SMT's feed was written ${Format.age(it)}." + if (it > 6) " That's old, so this call is not graded." else "") }
}
