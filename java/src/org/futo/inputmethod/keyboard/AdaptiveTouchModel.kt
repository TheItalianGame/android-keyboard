package org.futo.inputmethod.keyboard

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class AdaptiveTouchOffset(
    val x: Float,
    val y: Float,
    val samples: Int,
    val heat: List<Int>,
    val corrections: Int,
    val correctionX: Float,
    val correctionY: Float,
)

data class AdaptiveHitboxExpansion(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

data class AdaptiveCorrectionCandidate(
    val deletedCode: Int,
    val touchX: Int,
    val touchY: Int,
)

/** Local-only model of tap density, per-key bias, and deliberate in-word corrections. */
object AdaptiveTouchModel {
    private const val PREFS_NAME = "adaptive_touch_model"
    private const val MAX_OFFSET = 0.30f
    private const val MAX_NORMAL_UPDATE = 0.030f
    private const val MAX_CORRECTION_UPDATE = 0.055f
    private const val MAX_SAMPLE_AXIS = 0.68f
    private const val MAX_SAMPLE_RADIUS_SQUARED = 0.72f * 0.72f
    private const val HARD_REJECT_AXIS = 1.25f
    private const val HARD_REJECT_RADIUS_SQUARED = 1.40f * 1.40f
    private const val FAR_EDGE_EVIDENCE = 24
    private const val MAX_EFFECTIVE_WEIGHT = 64.0f
    private const val CORRECTION_WEIGHT = 4
    private const val HISTORY_WINDOW_MS = 8_000L
    private const val MAX_WORD_HISTORY = 24
    const val HEAT_GRID_SIZE = 9
    private const val HEAT_CELL_COUNT = HEAT_GRID_SIZE * HEAT_GRID_SIZE
    private const val MAX_CELL_COUNT = 100_000

    private data class Tap(
        val code: Int,
        val touchX: Int,
        val touchY: Int,
        val timestamp: Long,
    )

    private data class Replacement(
        val original: Tap,
        val replacementCode: Int,
        val originalTouchOnReplacementX: Float,
        val originalTouchOnReplacementY: Float,
    )

    private data class StagedCorrection(
        val code: Int,
        val normalizedX: Float,
        val normalizedY: Float,
    )

    private val cache = ConcurrentHashMap<Int, AdaptiveTouchOffset>()
    private val wordHistory = mutableListOf<Tap>()
    private val deletedNewestFirst = mutableListOf<Tap>()
    private var activeDeleted: List<Tap>? = null
    private val replacements = mutableListOf<Replacement>()
    private var hadRetainedPrefix = false
    private var stagedCorrection: StagedCorrection? = null

    private fun canonicalCode(code: Int): Int = Character.toLowerCase(code)
    private fun key(axis: String, code: Int) = "${axis}_${canonicalCode(code)}"

    @JvmStatic
    fun get(context: Context, code: Int): AdaptiveTouchOffset {
        val canonical = canonicalCode(code)
        cache[canonical]?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AdaptiveTouchOffset(
            prefs.getFloat(key("x", canonical), 0.0f),
            prefs.getFloat(key("y", canonical), 0.0f),
            prefs.getInt(key("n", canonical), 0),
            List(HEAT_CELL_COUNT) { prefs.getInt(key("h$it", canonical), 0) },
            prefs.getInt(key("c", canonical), 0),
            prefs.getFloat(key("cx", canonical), 0.0f),
            prefs.getFloat(key("cy", canonical), 0.0f),
        ).also { cache[canonical] = it }
    }

    private fun heatCell(normalizedX: Float, normalizedY: Float): Int {
        val column = ((normalizedX.coerceIn(-0.5f, 0.4999f) + 0.5f) * HEAT_GRID_SIZE)
            .toInt().coerceIn(0, HEAT_GRID_SIZE - 1)
        val row = ((normalizedY.coerceIn(-0.5f, 0.4999f) + 0.5f) * HEAT_GRID_SIZE)
            .toInt().coerceIn(0, HEAT_GRID_SIZE - 1)
        return row * HEAT_GRID_SIZE + column
    }

    private fun recordWeighted(
        context: Context,
        code: Int,
        normalizedX: Float,
        normalizedY: Float,
        weight: Int,
        correction: Boolean,
    ) {
        if (!Character.isLetterOrDigit(code)) return
        val canonical = canonicalCode(code)
        val old = get(context, canonical)
        val cell = heatCell(normalizedX, normalizedY)
        val isFarEdge = abs(normalizedX) > MAX_SAMPLE_AXIS
                || abs(normalizedY) > MAX_SAMPLE_AXIS
                || normalizedX * normalizedX + normalizedY * normalizedY > MAX_SAMPLE_RADIUS_SQUARED
        val isHardOutlier = abs(normalizedX) > HARD_REJECT_AXIS
                || abs(normalizedY) > HARD_REJECT_AXIS
                || (normalizedX * normalizedX + normalizedY * normalizedY
                    > HARD_REJECT_RADIUS_SQUARED)
        // Beyond the neighboring-key area is an edit or stray touch, not useful training data.
        if (correction && isHardOutlier) return
        // A neighboring-key correction may be genuine, but must repeat in the same heat cell.
        val hasFarEdgeEvidence = correction && old.corrections >= 2
                && old.heat[cell] >= FAR_EDGE_EVIDENCE
        val mayAdjustBias = !isFarEdge || hasFarEdgeEvidence
        val nextSamples = if (old.samples == Int.MAX_VALUE) old.samples else old.samples + 1
        val effectiveWeight = min(old.samples.toFloat(), MAX_EFFECTIVE_WEIGHT)
        val blend = weight / (effectiveWeight + weight)
        val sampleX = normalizedX.coerceIn(-0.75f, 0.75f)
        val sampleY = normalizedY.coerceIn(-0.75f, 0.75f)
        val maxUpdate = if (correction) MAX_CORRECTION_UPDATE else MAX_NORMAL_UPDATE
        val x = if (!mayAdjustBias) old.x else (old.x
                + ((sampleX - old.x) * blend).coerceIn(-maxUpdate, maxUpdate))
            .coerceIn(-MAX_OFFSET, MAX_OFFSET)
        val y = if (!mayAdjustBias) old.y else (old.y
                + ((sampleY - old.y) * blend).coerceIn(-maxUpdate, maxUpdate))
            .coerceIn(-MAX_OFFSET, MAX_OFFSET)
        val heat = old.heat.toMutableList()
        val centerColumn = cell % HEAT_GRID_SIZE
        val centerRow = cell / HEAT_GRID_SIZE
        val changedCells = mutableListOf<Int>()
        for (rowDelta in -1 .. 1) {
            for (columnDelta in -1 .. 1) {
                val column = centerColumn + columnDelta
                val row = centerRow + rowDelta
                if (column !in 0 until HEAT_GRID_SIZE || row !in 0 until HEAT_GRID_SIZE) continue
                val index = row * HEAT_GRID_SIZE + column
                val kernel = when {
                    rowDelta == 0 && columnDelta == 0 -> 4
                    rowDelta == 0 || columnDelta == 0 -> 2
                    else -> 1
                }
                heat[index] = min(MAX_CELL_COUNT, heat[index] + weight * kernel)
                changedCells.add(index)
            }
        }
        val corrections = if (correction && old.corrections < Int.MAX_VALUE) {
            old.corrections + 1
        } else old.corrections
        val correctionX = if (correction) {
            old.correctionX + ((normalizedX.coerceIn(-1.0f, 1.0f) - old.correctionX) /
                    max(1, corrections).toFloat())
        } else old.correctionX
        val correctionY = if (correction) {
            old.correctionY + ((normalizedY.coerceIn(-1.0f, 1.0f) - old.correctionY) /
                    max(1, corrections).toFloat())
        } else old.correctionY

        val updated = AdaptiveTouchOffset(x, y, nextSamples, heat, corrections,
            correctionX, correctionY)
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(key("x", canonical), x)
            .putFloat(key("y", canonical), y)
            .putInt(key("n", canonical), nextSamples)
            .putInt(key("c", canonical), corrections)
            .putFloat(key("cx", canonical), correctionX)
            .putFloat(key("cy", canonical), correctionY)
        changedCells.forEach { editor.putInt(key("h$it", canonical), heat[it]) }
        editor.apply()
        cache[canonical] = updated
    }

    @JvmStatic
    fun record(context: Context, code: Int, normalizedX: Float, normalizedY: Float) {
        recordWeighted(context, code, normalizedX, normalizedY, 1, false)
    }

    @JvmStatic
    fun getHitboxExpansion(
        context: Context,
        code: Int,
        maximumGrowthPercent: Int,
    ): AdaptiveHitboxExpansion {
        val learned = get(context, code)
        if (learned.corrections < 3 || maximumGrowthPercent <= 0) {
            return AdaptiveHitboxExpansion(0.0f, 0.0f, 0.0f, 0.0f)
        }
        val cap = maximumGrowthPercent.coerceIn(0, 25) / 100.0f
        val confidence = (1.0 - exp(-(learned.corrections - 2) / 6.0)).toFloat()
        val available = cap * confidence
        val horizontalDirection = abs(learned.correctionX).coerceIn(0.0f, 1.0f)
        val verticalDirection = abs(learned.correctionY).coerceIn(0.0f, 1.0f)
        val base = available * 0.18f
        val directedX = available * 0.82f * horizontalDirection
        val directedY = available * 0.82f * verticalDirection
        return AdaptiveHitboxExpansion(
            left = base + if (learned.correctionX < 0.0f) directedX else 0.0f,
            right = base + if (learned.correctionX > 0.0f) directedX else 0.0f,
            top = base + if (learned.correctionY < 0.0f) directedY else 0.0f,
            bottom = base + if (learned.correctionY > 0.0f) directedY else 0.0f,
        )
    }

    private fun confirmStagedCorrection(context: Context) {
        val staged = stagedCorrection
        stagedCorrection = null
        if (staged != null) {
            recordWeighted(context, staged.code, staged.normalizedX, staged.normalizedY,
                CORRECTION_WEIGHT, true)
        }
    }

    /** Called before recording a letter. It returns the deleted tap aligned to this replacement. */
    @JvmStatic
    @Synchronized
    fun prepareLetter(context: Context, replacementCode: Int): AdaptiveCorrectionCandidate? {
        // A previously completed correction survived another key, so it is now trustworthy.
        confirmStagedCorrection(context)
        if (activeDeleted == null && deletedNewestFirst.isNotEmpty()) {
            activeDeleted = deletedNewestFirst.asReversed().toList()
            deletedNewestFirst.clear()
            replacements.clear()
            hadRetainedPrefix = wordHistory.isNotEmpty()
        }
        val original = activeDeleted?.getOrNull(replacements.size) ?: return null
        return AdaptiveCorrectionCandidate(original.code, original.touchX, original.touchY)
    }

    /** Completes one replacement position and checks a fully retyped suffix for one substitution. */
    @JvmStatic
    @Synchronized
    fun noteLetterTouch(
        code: Int,
        touchX: Int,
        touchY: Int,
        originalTouchOnReplacementX: Float,
        originalTouchOnReplacementY: Float,
        hasCorrectionCandidate: Boolean,
    ) {
        val canonical = canonicalCode(code)
        val active = activeDeleted
        if (active != null && hasCorrectionCandidate) {
            val original = active.getOrNull(replacements.size)
            if (original != null) {
                replacements.add(Replacement(original, canonical,
                    originalTouchOnReplacementX, originalTouchOnReplacementY))
            }
        }
        wordHistory.add(Tap(canonical, touchX, touchY, SystemClock.elapsedRealtime()))
        if (wordHistory.size > MAX_WORD_HISTORY) wordHistory.removeAt(0)

        if (active != null && replacements.size == active.size) {
            val mismatches = replacements.filter {
                canonicalCode(it.original.code) != canonicalCode(it.replacementCode)
            }
            val matchingContext = replacements.any {
                canonicalCode(it.original.code) == canonicalCode(it.replacementCode)
            }
            // Learn only one-letter substitutions with a retained or retyped alphabetic anchor.
            if (mismatches.size == 1 && (hadRetainedPrefix || matchingContext)) {
                val correction = mismatches.single()
                stagedCorrection = StagedCorrection(correction.replacementCode,
                    correction.originalTouchOnReplacementX,
                    correction.originalTouchOnReplacementY)
            }
            activeDeleted = null
            replacements.clear()
            hadRetainedPrefix = false
        }
    }

    @JvmStatic
    @Synchronized
    fun noteBackspace() {
        // If a replacement is itself deleted, it did not survive and is not training evidence.
        stagedCorrection = null
        if (activeDeleted != null) {
            activeDeleted = null
            replacements.clear()
            deletedNewestFirst.clear()
            hadRetainedPrefix = false
        }
        val now = SystemClock.elapsedRealtime()
        val deleted = wordHistory.removeLastOrNull()
        if (deleted == null || now - deleted.timestamp > HISTORY_WINDOW_MS) {
            deletedNewestFirst.clear()
            return
        }
        deletedNewestFirst.add(deleted)
    }

    @JvmStatic
    @Synchronized
    fun noteWordBoundary(context: Context) {
        // A boundary confirms a complete replacement, but incomplete rewrites are discarded.
        confirmStagedCorrection(context)
        wordHistory.clear()
        deletedNewestFirst.clear()
        activeDeleted = null
        replacements.clear()
        hadRetainedPrefix = false
    }

    @JvmStatic
    @Synchronized
    fun cancelCorrectionLearning() {
        wordHistory.clear()
        deletedNewestFirst.clear()
        activeDeleted = null
        replacements.clear()
        stagedCorrection = null
        hadRetainedPrefix = false
    }

    @JvmStatic
    fun clear(context: Context) {
        cache.clear()
        cancelCorrectionLearning()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun snapshot(context: Context): Map<Char, AdaptiveTouchOffset> =
        (('a'..'z') + ('0'..'9')).associateWith { get(context, it.code) }
            .filterValues { it.samples > 0 }

    @JvmStatic
    fun totalSamples(context: Context): Int = snapshot(context).values.sumOf { max(0, it.samples) }

    @JvmStatic
    fun totalCorrections(context: Context): Int =
        snapshot(context).values.sumOf { max(0, it.corrections) }
}
