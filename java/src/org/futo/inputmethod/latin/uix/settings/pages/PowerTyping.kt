package org.futo.inputmethod.latin.uix.settings.pages

import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.launch
import org.futo.inputmethod.keyboard.AdaptiveTouchModel
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsExporter
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.Route
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

object PowerTypingSettings {
    @JvmField val AnimationSpeed = SettingsKey(
        intPreferencesKey("power_animation_speed_percent"), 100)
    @JvmField val SelectionWrapping = SettingsKey(
        booleanPreferencesKey("power_selection_wrapping"), true)
    @JvmField val SmartBackspace = SettingsKey(
        booleanPreferencesKey("power_smart_backspace"), true)
    @JvmField val EnhancedCursor = SettingsKey(
        booleanPreferencesKey("power_enhanced_cursor"), true)
    @JvmField val CursorHaptics = SettingsKey(
        booleanPreferencesKey("power_cursor_haptics"), true)
    @JvmField val NumericSwipe = SettingsKey(
        booleanPreferencesKey("power_numeric_swipe"), true)
    @JvmField val AdaptiveTouch = SettingsKey(
        booleanPreferencesKey("power_adaptive_touch"), true)
    @JvmField val AdaptiveCorrectionLearning = SettingsKey(
        booleanPreferencesKey("power_adaptive_correction_learning"), true)
    @JvmField val AdaptiveKeyResizing = SettingsKey(
        booleanPreferencesKey("power_adaptive_key_resizing"), true)
    @JvmField val AdaptiveKeyGrowth = SettingsKey(
        intPreferencesKey("power_adaptive_key_growth_percent"), 15)
    @JvmField val AutomaticBackup = SettingsKey(
        booleanPreferencesKey("power_automatic_backup"), true)
}

@Composable
private fun AdaptiveTouchMap() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val offsets = remember(refresh) { AdaptiveTouchModel.snapshot(context) }
    val maximumGrowth = useDataStoreValue(PowerTypingSettings.AdaptiveKeyGrowth)
    val keyColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val heatColor = MaterialTheme.colorScheme.tertiary
    val growthColor = MaterialTheme.colorScheme.secondary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rows = remember { listOf("qwertyuiop", "asdfghjkl", "zxcvbnm") }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            stringResource(R.string.power_typing_adaptive_map_samples,
                AdaptiveTouchModel.totalSamples(context),
                AdaptiveTouchModel.totalCorrections(context))
        )
        Text(
            stringResource(R.string.power_typing_adaptive_map_growth_legend),
            color = growthColor,
        )
        Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp)) {
            val keyWidth = size.width / 10.5f
            val keyHeight = size.height / 3.15f
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = keyHeight * 0.27f
            }

            rows.forEachIndexed { rowIndex, row ->
                val rowOffset = when (rowIndex) {
                    1 -> keyWidth * 0.45f
                    2 -> keyWidth * 1.35f
                    else -> 0.0f
                }
                val rowGrowth = row.map {
                    AdaptiveTouchModel.getHitboxExpansion(context, it.code, maximumGrowth)
                }
                // Shared weighted boundaries make growth zero-sum: one key's gain is its
                // neighbor's loss, matching the winner-selection algorithm used while typing.
                val touchBoundaries = FloatArray(row.length + 1)
                touchBoundaries[0] = rowOffset
                touchBoundaries[row.length] = rowOffset + row.length * keyWidth
                for (boundary in 1 until row.length) {
                    val leftCenter = rowOffset + (boundary - 0.5f) * keyWidth
                    val rightCenter = rowOffset + (boundary + 0.5f) * keyWidth
                    val leftScale = 1.0f + rowGrowth[boundary - 1].right * 1.4f
                    val rightScale = 1.0f + rowGrowth[boundary].left * 1.4f
                    touchBoundaries[boundary] =
                        (leftScale * rightCenter + rightScale * leftCenter) /
                                (leftScale + rightScale)
                }
                row.forEachIndexed { column, char ->
                    val left = rowOffset + column * keyWidth
                    val top = rowIndex * keyHeight
                    val center = Offset(left + keyWidth * 0.46f, top + keyHeight * 0.45f)
                    val growth = rowGrowth[column]
                    drawRoundRect(
                        color = keyColor,
                        topLeft = Offset(left + 2.dp.toPx(), top + 2.dp.toPx()),
                        size = Size(keyWidth - 4.dp.toPx(), keyHeight - 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                    val touchLeft = touchBoundaries[column]
                    val touchRight = touchBoundaries[column + 1]
                    val redistributed = abs(touchLeft - left) > 0.25f
                            || abs(touchRight - (left + keyWidth)) > 0.25f
                            || growth.top + growth.bottom > 0.0f
                    if (redistributed) {
                        val ownsExtraArea = (growth.left + growth.right
                                + growth.top + growth.bottom) > 0.0f
                        drawRect(
                            color = growthColor.copy(alpha = if (ownsExtraArea) 0.11f else 0.035f),
                            topLeft = Offset(touchLeft, top + 1.dp.toPx()),
                            size = Size(touchRight - touchLeft, keyHeight - 2.dp.toPx()),
                        )
                        drawRect(
                            color = growthColor.copy(alpha = if (ownsExtraArea) 0.9f else 0.5f),
                            topLeft = Offset(touchLeft, top + 1.dp.toPx()),
                            size = Size(touchRight - touchLeft, keyHeight - 2.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                    offsets[char]?.let { learned ->
                        val grid = AdaptiveTouchModel.HEAT_GRID_SIZE
                        val inset = 3.dp.toPx()
                        val cellWidth = (keyWidth - inset * 2) / grid
                        val cellHeight = (keyHeight - inset * 2) / grid
                        clipRect(left + 2.dp.toPx(), top + 2.dp.toPx(),
                            left + keyWidth - 2.dp.toPx(), top + keyHeight - 2.dp.toPx()) {
                            learned.heat.forEachIndexed { index, count ->
                                if (count > 0) {
                                    // Absolute density makes overlapping hotspots brighten over time.
                                    val strength = (1.0 - exp(-count / 20.0)).toFloat()
                                    val heatCenter = Offset(
                                        left + inset + (index % grid + 0.5f) * cellWidth,
                                        top + inset + (index / grid + 0.5f) * cellHeight,
                                    )
                                    val radius = min(keyWidth, keyHeight) *
                                            (0.20f + strength * 0.12f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                heatColor.copy(alpha = 0.18f + strength * 0.72f),
                                                heatColor.copy(alpha = 0.07f + strength * 0.25f),
                                                Color.Transparent,
                                            ),
                                            center = heatCenter,
                                            radius = radius,
                                        ),
                                        radius = radius,
                                        center = heatCenter,
                                    )
                                }
                            }
                        }
                        val learnedPoint = Offset(
                            center.x + learned.x * keyWidth,
                            center.y + learned.y * keyHeight,
                        )
                        drawLine(lineColor.copy(alpha = 0.65f), center, learnedPoint,
                            strokeWidth = 2.dp.toPx())
                        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = learnedPoint)
                    }
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(char.uppercase(), center.x,
                            center.y + textPaint.textSize * 0.33f, textPaint)
                    }
                }
            }
        }
        Button(onClick = {
            AdaptiveTouchModel.clear(context)
            refresh++
        }) {
            Text(stringResource(R.string.power_typing_adaptive_map_reset))
        }
    }
}

val PowerTypingSettingsMenu = UserSettingsMenu(
    title = R.string.power_typing_title,
    navPath = "powerTyping",
    registerNavPath = true,
    settings = listOf(
        userSettingNavigationItem(
            title = R.string.action_editor_title,
            subtitle = R.string.action_editor_subtitle,
            style = NavigationItemStyle.Misc,
            navigateTo = ActionsScreen.navPath),
        userSettingNavigationItem(
            title = R.string.edit_personal_dictionary,
            style = NavigationItemStyle.Misc,
            navigate = { it.navigate(Route.PersonalDictList(null)) }),
        UserSetting(name = R.string.power_typing_animation_speed) {
            SettingSlider(
                title = stringResource(R.string.power_typing_animation_speed),
                subtitle = stringResource(R.string.power_typing_animation_speed_subtitle),
                setting = PowerTypingSettings.AnimationSpeed,
                range = 50.0f .. 200.0f,
                hardRange = 25.0f .. 300.0f,
                transform = { it.roundToInt() },
                indicator = { "$it%" },
                steps = 14,
            )
        },
        userSettingToggleDataStore(
            title = R.string.power_typing_selection_wrap,
            subtitle = R.string.power_typing_selection_wrap_subtitle,
            setting = PowerTypingSettings.SelectionWrapping),
        userSettingToggleDataStore(
            title = R.string.power_typing_smart_backspace,
            subtitle = R.string.power_typing_smart_backspace_subtitle,
            setting = PowerTypingSettings.SmartBackspace),
        userSettingToggleDataStore(
            title = R.string.power_typing_enhanced_cursor,
            subtitle = R.string.power_typing_enhanced_cursor_subtitle,
            setting = PowerTypingSettings.EnhancedCursor),
        userSettingToggleDataStore(
            title = R.string.power_typing_cursor_haptics,
            subtitle = R.string.power_typing_cursor_haptics_subtitle,
            setting = PowerTypingSettings.CursorHaptics),
        userSettingToggleDataStore(
            title = R.string.power_typing_numeric_swipe,
            subtitle = R.string.power_typing_numeric_swipe_subtitle,
            setting = PowerTypingSettings.NumericSwipe),
        userSettingToggleDataStore(
            title = R.string.power_typing_adaptive_touch,
            subtitle = R.string.power_typing_adaptive_touch_subtitle,
            setting = PowerTypingSettings.AdaptiveTouch),
        userSettingToggleDataStore(
            title = R.string.power_typing_adaptive_correction_learning,
            subtitle = R.string.power_typing_adaptive_correction_learning_subtitle,
            setting = PowerTypingSettings.AdaptiveCorrectionLearning),
        userSettingToggleDataStore(
            title = R.string.power_typing_adaptive_key_resizing,
            subtitle = R.string.power_typing_adaptive_key_resizing_subtitle,
            setting = PowerTypingSettings.AdaptiveKeyResizing),
        UserSetting(name = R.string.power_typing_adaptive_key_growth) {
            SettingSlider(
                title = stringResource(R.string.power_typing_adaptive_key_growth),
                subtitle = stringResource(R.string.power_typing_adaptive_key_growth_subtitle),
                setting = PowerTypingSettings.AdaptiveKeyGrowth,
                range = 5.0f .. 25.0f,
                hardRange = 0.0f .. 30.0f,
                transform = { it.roundToInt() },
                indicator = { "$it%" },
                steps = 19,
            )
        },
        UserSetting(name = R.string.power_typing_adaptive_map) { AdaptiveTouchMap() },
        userSettingToggleDataStore(
            title = R.string.power_typing_automatic_backup,
            subtitle = R.string.power_typing_automatic_backup_subtitle,
            setting = PowerTypingSettings.AutomaticBackup),
        UserSetting(name = R.string.power_typing_backup_now) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Button(
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = {
                    scope.launch {
                        val file = SettingsExporter.createAutomaticBackup(context, force = true)
                        Toast.makeText(
                            context,
                            file?.let { context.getString(R.string.power_typing_backup_created) }
                                ?: context.getString(R.string.power_typing_backup_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) { Text(stringResource(R.string.power_typing_backup_now)) }
        },
    )
)

val PowerTypingNavigationItem = userSettingNavigationItem(
    title = R.string.power_typing_title,
    subtitle = R.string.power_typing_subtitle,
    style = NavigationItemStyle.Misc,
    navigateTo = PowerTypingSettingsMenu.navPath,
    icon = R.drawable.keyboard_icon,
)
