package io.github.jannetekka.smtworld.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import io.github.jannetekka.smtworld.clockin.ClockInCall
import io.github.jannetekka.smtworld.clockin.Dir
import io.github.jannetekka.smtworld.clockin.Grade
import io.github.jannetekka.smtworld.clockin.Lean
import io.github.jannetekka.smtworld.clockin.OnChainCall
import io.github.jannetekka.smtworld.clockin.Outcome
import io.github.jannetekka.smtworld.solana.DevnetRpc
import io.github.jannetekka.smtworld.ui.Format
import java.io.File

/** A square image of one Clock In (and its grade, once there is one) for the Android share sheet. */
object ShareCard {
    private const val SIZE = 1080
    private const val NAVY = 0xFF081A34.toInt()
    private const val RAISED = 0xFF0F2747.toInt()
    private const val GOLD = 0xFFD4AF37.toInt()
    private const val INK = 0xFFE8EEFB.toInt()
    private const val MUTED = 0xFF9FB4CF.toInt()
    private const val UP = 0xFF3DDC97.toInt()
    private const val DOWN = 0xFFFF6B6B.toInt()

    /** Draws and writes the image (call off the main thread); returns the share-sheet intent. */
    fun prepare(context: Context, oc: OnChainCall, grade: Grade?): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }                      // only the latest card is kept
        val file = dir.resolve("clockin_${oc.signature.take(10)}.png")
        file.outputStream().use { render(oc, grade).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val text = caption(oc, grade) + "\n" + DevnetRpc.explorerTx(oc.signature) + "\nhttps://github.com/JannetEkka/smt-solana-hack"
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share your call")
    }

    fun caption(oc: OnChainCall, g: Grade?): String {
        val c = oc.call
        val dir = if (c.you == Dir.UP) "UP" else "DOWN"
        return when {
            g == null -> "I called ${c.coin} $dir for the next ${c.horizonHours}h on SMT Clock In. Graded at ${Format.time(oc.dueAt)}."
            g.you == Outcome.RIGHT && g.smt == Outcome.WRONG -> "I called ${c.coin} $dir and beat SMT, the AI. ${Format.pct(g.movePct)} in ${c.horizonHours}h."
            g.you == Outcome.RIGHT -> "I called ${c.coin} $dir and got it right. ${Format.pct(g.movePct)} in ${c.horizonHours}h."
            else -> "I called ${c.coin} $dir. The market went ${Format.pct(g.movePct)} in ${c.horizonHours}h."
        }
    }

    fun render(oc: OnChainCall, g: Grade?): Bitmap {
        val c: ClockInCall = oc.call
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(NAVY)
        fun paint(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        cv.drawRoundRect(RectF(60f, 60f, SIZE - 60f, SIZE - 60f), 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RAISED })
        cv.drawText("SMT Clock In", 120f, 190f, paint(GOLD, 56f, true))
        cv.drawText(Format.dayTime(oc.blockTime), 120f, 250f, paint(MUTED, 36f))

        val dirColor = if (c.you == Dir.UP) UP else DOWN
        cv.drawText(c.coin, 120f, 420f, paint(INK, 140f, true))
        cv.drawText(if (c.you == Dir.UP) "▲ UP" else "▼ DOWN", 120f, 540f, paint(dirColor, 96f, true))
        cv.drawText("for the next ${c.horizonHours} hours, from ${Format.price(c.entryPx)}", 120f, 610f, paint(MUTED, 38f))

        if (g != null) {
            cv.drawText(Format.pct(g.movePct), 120f, 730f, paint(if (g.movePct >= 0) UP else DOWN, 80f, true))
            cv.drawText("Me: " + mark(g.you), 120f, 810f, paint(if (g.you == Outcome.RIGHT) UP else MUTED, 44f, true))
            cv.drawText("SMT: " + mark(g.smt), 560f, 810f, paint(if (g.smt == Outcome.RIGHT) UP else MUTED, 44f, true))
        } else {
            cv.drawText("Graded at ${Format.time(oc.dueAt)}", 120f, 730f, paint(INK, 52f, true))
            val smtLine = when {
                c.smtAction == "NOCALL" -> "SMT doesn't call ${c.coin}"
                c.smtLean == Lean.FLAT -> "SMT has no lean on it"
                else -> "SMT leans " + (if (c.smtLean == Lean.UP) "▲ UP" else "▼ DOWN") + " (${c.smtConvictionPct}%)"
            }
            cv.drawText(smtLine, 120f, 810f, paint(MUTED, 44f))
        }
        cv.drawText("Signed on Solana devnet · tx ${oc.signature.take(8)}…", 120f, 930f, paint(MUTED, 32f))
        cv.drawText("github.com/JannetEkka/smt-solana-hack", 120f, 975f, paint(GOLD, 32f))
        return bmp
    }

    private fun mark(o: Outcome) = when (o) { Outcome.RIGHT -> "✓ right"; Outcome.WRONG -> "✗ wrong"; Outcome.NOT_SCORED -> "not scored" }
}
