/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Sample digital watch face with blinking colons and seconds. In ambient mode, the seconds are
 * replaced with an AM/PM indicator and the colons don't blink. On devices with low-bit ambient
 * mode, the text is drawn without anti-aliasing in ambient mode. On devices which require burn-in
 * protection, the hours are drawn in normal rather than bold. The time is drawn with less contrast
 * and without seconds in mute mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWFService";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;


        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        Paint mBackgroundPaint;
        boolean mMute;

        Bitmap mWeatherBitmap;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        boolean mShouldDrawColons;
        float mXOffset;
        float mYOffset;

        int mInteractiveBackgroundColor;
        int mInteractiveHourDigitsColor;
        int mInteractiveMinuteDigitsColor;

        // TODO, a little code behind.  pull this into data model
        int mId;
        int mMaxTemp;
        int mMinTemp;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private int specW, specH;
        private View mLayout;
        private final Point mDisplaySize = new Point();
        private TextView mDateText, mHourText, mMinuteText, mHiText, mLoText;
        private ImageView mWeatherImage;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            // Inflate the layout that we're using for the watch face
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = inflater.inflate(R.layout.layout, null);

            // Load the display spec - we'll need this later for measuring myLayout
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(mDisplaySize);

            // Find some views for later use
            mWeatherImage = (ImageView) mLayout.findViewById(R.id.weatherImage);
            mDateText = (TextView) mLayout.findViewById(R.id.dateTextView);
            mHourText = (TextView) mLayout.findViewById(R.id.hourText);
            mMinuteText = (TextView) mLayout.findViewById(R.id.minText);
            mHiText = (TextView) mLayout.findViewById(R.id.hiTextView);
            mLoText = (TextView) mLayout.findViewById(R.id.loTextView);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mInteractiveBackgroundColor = resources.getColor(R.color.primary);
            mInteractiveHourDigitsColor = resources.getColor(R.color.white);
            mInteractiveMinuteDigitsColor = resources.getColor(R.color.white);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            onVisibilityChanged(true);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            // Recompute the MeasureSpec fields - these determine the actual size of the layout
            specW = View.MeasureSpec.makeMeasureSpec(mDisplaySize.x, View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(mDisplaySize.y, View.MeasureSpec.EXACTLY);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourText.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            adjustPaintColorToCurrentMode(mBackgroundPaint, mInteractiveBackgroundColor,
                    SunshineWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
            adjustTextColorToCurrentMode(mHourText, mInteractiveHourDigitsColor,
                    SunshineWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS);
            adjustTextColorToCurrentMode(mMinuteText, mInteractiveMinuteDigitsColor,
                    SunshineWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS);

            invalidate();

        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        private void adjustTextColorToCurrentMode(TextView textView, int interactiveColor,
                                                   int ambientColor) {
            textView.setTextColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }
        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alphaMask = ((inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA) << 24) + 0x00FFFFFF;

                setTextViewAlpha(mDateText, alphaMask);
                setTextViewAlpha(mHourText, alphaMask);
                setTextViewAlpha(mMinuteText, alphaMask);
                setTextViewAlpha(mHiText, alphaMask);
                setTextViewAlpha(mLoText, alphaMask);

                invalidate();
            }
        }

        private void setTextViewAlpha(TextView view, int alphaMask)
        {
            int currentColor = view.getCurrentTextColor();
            currentColor = (currentColor & 0x00FFFFFF) & alphaMask;
            view.setTextColor(currentColor);
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            final long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Apply it to the date fields
            mDateText.setText(mDateFormat.format(mDate));

            // Apply it to the time fields
            mHourText.setText(formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY)));

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                mMinuteText.setText(":" + formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE)));
            }
            else {
                mMinuteText.setText(" " + formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE)));
            }

            // Only render the weather if there is no peek card, so it does not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty() && mWeatherBitmap != null) {
                //Draw the weather
                mHiText.setText(Integer.toString(mMaxTemp));
                mWeatherImage.setImageBitmap(mWeatherBitmap);
                mLoText.setText(Integer.toString(mMinTemp));
            }
            else {
                mHiText.setText("");
                mWeatherImage.setImageBitmap(null);
                mLoText.setText("");
            }

            // Update the layout
            mLayout.measure(specW, specH);
            mLayout.layout(0, 0, mLayout.getMeasuredWidth(), mLayout.getMeasuredHeight());

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            mLayout.draw(canvas);

        }

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        SunshineWatchFaceUtil.PATH_WITH_WEATHER)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap weather = dataMapItem.getDataMap();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Weather DataItems updated:" + weather);
                }
                mId = weather.getInt(SunshineWatchFaceUtil.WEATHER_ID_KEY);
                mMaxTemp = weather.getInt(SunshineWatchFaceUtil.MAX_TEMP_KEY);
                mMinTemp = weather.getInt(SunshineWatchFaceUtil.MIN_TEMP_KEY);

                mWeatherBitmap = BitmapFactory.decodeResource(getResources(),
                        SunshineWatchFaceUtil.getArtResourceForWeatherCondition(mId));

                invalidate();
            }
        }



        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }
    }
}
