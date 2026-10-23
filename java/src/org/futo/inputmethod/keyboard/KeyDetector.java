/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.keyboard;

import android.content.Context;
import android.graphics.Rect;

import org.futo.inputmethod.latin.settings.Settings;
import org.futo.inputmethod.latin.settings.SettingsValues;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.uix.DataStoreHelper;
import org.futo.inputmethod.latin.uix.settings.pages.PowerTypingSettings;

import java.util.Set;

import javax.annotation.Nullable;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;
    @Nullable
    private final Context mContext;

    @Nullable
    private Set<Integer> mBoostedCodePoints;

    public KeyDetector() {
        this(null, 0.0f /* keyHysteresisDistance */,
                0.0f /* keyHysteresisDistanceForSlidingModifier */);
    }

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     */
    public KeyDetector(final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        this(null, keyHysteresisDistance, keyHysteresisDistanceForSlidingModifier);
    }

    public KeyDetector(@Nullable final Context context, final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        mContext = context == null ? null : context.getApplicationContext();
        mKeyHysteresisDistanceSquared = (int)(keyHysteresisDistance * keyHysteresisDistance);
        mKeyHysteresisDistanceForSlidingModifierSquared = (int)(
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
    }

    public void setKeyboard(final Keyboard keyboard, final float correctionX,
            final float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        mCorrectionX = (int)correctionX;
        mCorrectionY = (int)correctionY;
        mKeyboard = keyboard;
    }

    public int getKeyHysteresisDistanceSquared(final boolean isSlidingFromModifier) {
        return isSlidingFromModifier
                ? mKeyHysteresisDistanceForSlidingModifierSquared : mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(final int x) {
        return x + mCorrectionX;
    }

    // TODO: Remove vertical correction.
    public int getTouchY(final int y) {
        return y + mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return false;
    }

    private boolean isAdaptiveTouchEnabled() {
        return mContext != null && DataStoreHelper.getSetting(PowerTypingSettings.AdaptiveTouch);
    }

    private int getAdaptiveTouchX(final Key key, final int touchX) {
        if (!isAdaptiveTouchEnabled()) return touchX;
        final AdaptiveTouchOffset offset = AdaptiveTouchModel.get(mContext, key.getCode());
        return touchX - Math.round(offset.getX() * key.getWidth());
    }

    private int getAdaptiveTouchY(final Key key, final int touchY) {
        if (!isAdaptiveTouchEnabled()) return touchY;
        final AdaptiveTouchOffset offset = AdaptiveTouchModel.get(mContext, key.getCode());
        return touchY - Math.round(offset.getY() * key.getHeight());
    }

    private AdaptiveHitboxExpansion getAdaptiveHitboxExpansion(final Key key) {
        if (!isAdaptiveTouchEnabled()
                || !DataStoreHelper.getSetting(PowerTypingSettings.AdaptiveKeyResizing)
                || !Character.isLetterOrDigit(key.getCode())) {
            return null;
        }
        return AdaptiveTouchModel.getHitboxExpansion(mContext, key.getCode(),
                DataStoreHelper.getSetting(PowerTypingSettings.AdaptiveKeyGrowth));
    }

    private boolean isOnAdaptiveKey(final Key key, final int x, final int y,
            @Nullable final AdaptiveHitboxExpansion expansion) {
        if (expansion == null) return key.isOnKey(x, y);
        final Rect hitBox = key.getHitBox();
        final int extraLeft = Math.round(expansion.getLeft() * key.getWidth());
        final int extraRight = Math.round(expansion.getRight() * key.getWidth());
        final int extraTop = Math.round(expansion.getTop() * key.getHeight());
        final int extraBottom = Math.round(expansion.getBottom() * key.getHeight());
        return x >= hitBox.left - extraLeft && x < hitBox.right + extraRight
                && y >= hitBox.top - extraTop && y < hitBox.bottom + extraBottom;
    }

    private float getAdaptiveDistance(final Key key, final int x, final int y,
            @Nullable final AdaptiveHitboxExpansion expansion) {
        final float distance = key.distanceToCenter(x, y);
        if (expansion == null) return distance;
        final float centerX = key.getX() + key.getWidth() * 0.5f;
        final float centerY = key.getY() + key.getHeight() * 0.5f;
        final float horizontalGrowth = x < centerX ? expansion.getLeft() : expansion.getRight();
        final float verticalGrowth = y < centerY ? expansion.getTop() : expansion.getBottom();
        // In an overlap, a confidently expanded edge receives a modest priority advantage.
        return distance / (1.0f + Math.max(horizontalGrowth, verticalGrowth) * 1.4f);
    }

    public void recordTouch(@Nullable final Key key, final int x, final int y) {
        if (key == null || !isAdaptiveTouchEnabled()) {
            return;
        }
        final int code = key.getCode();
        if (code == Constants.CODE_DELETE) {
            AdaptiveTouchModel.noteBackspace();
            return;
        }
        if (!Character.isLetterOrDigit(code)) {
            // Shift and other modifiers may be part of a correction. Text boundaries end it.
            if (code == Constants.CODE_SPACE || code == Constants.CODE_ENTER || code > 0) {
                AdaptiveTouchModel.noteWordBoundary(mContext);
            }
            return;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);
        final float normalizedX = (touchX - (key.getX() + key.getWidth() * 0.5f))
                / Math.max(1.0f, key.getWidth());
        final float normalizedY = (touchY - (key.getY() + key.getHeight() * 0.5f))
                / Math.max(1.0f, key.getHeight());
        AdaptiveCorrectionCandidate correction = null;
        if (DataStoreHelper.getSetting(PowerTypingSettings.AdaptiveCorrectionLearning)) {
            correction = AdaptiveTouchModel.prepareLetter(mContext, code);
        } else {
            AdaptiveTouchModel.cancelCorrectionLearning();
        }
        float correctedX = 0.0f;
        float correctedY = 0.0f;
        if (correction != null) {
            correctedX = (correction.getTouchX() - (key.getX() + key.getWidth() * 0.5f))
                    / Math.max(1.0f, key.getWidth());
            correctedY = (correction.getTouchY() - (key.getY() + key.getHeight() * 0.5f))
                    / Math.max(1.0f, key.getHeight());
        }
        AdaptiveTouchModel.record(mContext, code, normalizedX, normalizedY);
        AdaptiveTouchModel.noteLetterTouch(code, touchX, touchY, correctedX, correctedY,
                correction != null);
    }

    public void recordRepeatedBackspaceForCorrection() {
        if (isAdaptiveTouchEnabled()
                && DataStoreHelper.getSetting(PowerTypingSettings.AdaptiveCorrectionLearning)) {
            AdaptiveTouchModel.noteBackspace();
        } else {
            AdaptiveTouchModel.cancelCorrectionLearning();
        }
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    public Key detectHitKey(final int x, final int y) {
        if (mKeyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        float minDistance = Float.POSITIVE_INFINITY;
        Key primaryKey = null;

        final SettingsValues settingsValues = Settings.getInstance().getCurrent();

        if(mBoostedCodePoints != null && !mBoostedCodePoints.isEmpty()) {
            for (final Key key : mKeyboard.getNearestKeys(touchX, touchY)) {
                final int adaptedX = getAdaptiveTouchX(key, touchX);
                final int adaptedY = getAdaptiveTouchY(key, touchY);
                if (mBoostedCodePoints.contains(key.getCode())) {
                    // Using min distance to edge for boosted codepoints,
                    // versus min distance to center for non boosted ones.
                    final float distance = key.distanceToEdge(adaptedX, adaptedY);

                    if(distance > Math.min(key.getWidth(), key.getHeight()) * 0.5f)
                        continue;

                    if(distance < minDistance) {
                        minDistance = distance;
                        primaryKey = key;
                    }
                }

                // If we are on a non-word key (e.g. shift, backspace), disable boosting
                // (break out of the loop and reset what we found)
                if(key.isOnKey(adaptedX, adaptedY)
                        && !settingsValues.isWordCodePoint(key.getCode())) {
                    minDistance = Integer.MAX_VALUE;
                    primaryKey = null;
                    break;
                }
            }
        }

        for (final Key key: mKeyboard.getNearestKeys(touchX, touchY)) {
            final int adaptedX = getAdaptiveTouchX(key, touchX);
            final int adaptedY = getAdaptiveTouchY(key, touchY);
            final AdaptiveHitboxExpansion expansion = getAdaptiveHitboxExpansion(key);
            // An edge key always has its enlarged hitbox to respond to an event that occurred in
            // the empty area around the key. (@see Key#markAsLeftEdge(KeyboardParams)} etc.)
            if (!isOnAdaptiveKey(key, adaptedX, adaptedY, expansion)) {
                continue;
            }
            final float distance = getAdaptiveDistance(key, adaptedX, adaptedY, expansion);
            if (distance > minDistance) {
                continue;
            }
            // To take care of hitbox overlaps, we compare key's code here too.
            if (primaryKey == null || distance < minDistance
                    || key.getCode() > primaryKey.getCode()) {
                minDistance = distance;
                primaryKey = key;
            }
        }
        return primaryKey;
    }

    public void updateBoostedCodePoints(@Nullable Set<Integer> validNextCodePoints) {
        mBoostedCodePoints = validNextCodePoints;
    }
}
