package com.example.googletimer2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import androidx.palette.graphics.Palette
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.text.format.DateFormat
import android.view.SurfaceHolder
import android.view.WindowInsets
import android.widget.Toast
import androidx.core.content.ContextCompat

import com.example.android.wearable.watchface.util.DigitalWatchFaceUtil

import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val HOUR_STROKE_WIDTH = 5f
private const val MINUTE_STROKE_WIDTH = 3f
private const val SECOND_TICK_STROKE_WIDTH = 2f

private const val CENTER_GAP_AND_CIRCLE_RADIUS = 4f

private const val SHADOW_RADIUS = 6f

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn"t
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class MyWatchFace : CanvasWatchFaceService() {
    companion object{
        const val COLON_STRING = ":"
        const val AM_STRING = "AM"
        const val PM_STRING = "PM"
    }

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: MyWatchFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<MyWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {
        private lateinit var mCalendar: Calendar

        private val BOLD_TYPEFACE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val NORMAL_TYPEFACE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

        private var mXOffset: Float = 0F
        private var mYOffset: Float = 0F

        private lateinit var mDatePaint: Paint
        private lateinit var mHour2Paint: Paint
        private lateinit var mMinSecPaint: Paint
        private lateinit var mAmPmPaint: Paint

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        private var mSecondHandLength: Float = 0F
        private var sMinuteHandLength: Float = 0F
        private var sHourHandLength: Float = 0F

        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private var mWatchHandColor: Int = 0
        private var mWatchHandHighlightColor: Int = 0
        private var mWatchHandShadowColor: Int = 0

        private lateinit var mHourPaint: Paint
        private lateinit var mMinutePaint: Paint
        private lateinit var mSecondPaint: Paint
        private lateinit var mTickAndCirclePaint: Paint

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mBackgroundBitmap: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false
        var bIsRound: Boolean = false

        var mInteractiveBackgroundColor: Int =
            DigitalWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND
        var mInteractiveHourDigitsColor: Int =
            DigitalWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_HOUR_DIGITS
        var mInteractiveMinuteDigitsColor: Int =
            DigitalWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINUTE_DIGITS
        var mInteractiveSecondDigitsColor: Int =
            DigitalWatchFaceUtil.COLOR_VALUE_DEFAULT_AND_AMBIENT_SECOND_DIGITS

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()

            initializeBackground()
            initializeWatchFace()

            var resources: Resources = this@MyWatchFace.resources
            mYOffset = resources.getDimension(R.dimen.digital_y_offset)

            mDatePaint = createTextPaint(
                ContextCompat.getColor(applicationContext, R.color.digital_date)
            )
            mHour2Paint = createTextPaint(mInteractiveHourDigitsColor, BOLD_TYPEFACE)
            mMinSecPaint = createTextPaint(mInteractiveMinuteDigitsColor)
            mAmPmPaint = createTextPaint(ContextCompat.getColor(applicationContext, R.color.digital_am_pm))
        }

        private fun createTextPaint(defaultInteractiveColor: Int): Paint {
            return createTextPaint( defaultInteractiveColor, NORMAL_TYPEFACE )
        }

        private fun createTextPaint( defaultInteractiveColor: Int, typeface: Typeface): Paint {
            val paint = Paint()
            paint.color = defaultInteractiveColor
            paint.typeface = typeface
            paint.isAntiAlias = true
            return paint
        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }
            mBackgroundBitmap =
                BitmapFactory.decodeResource(resources, R.drawable.kiki)

            /* Extracts colors from background image to improve watchface style. */
            Palette.from(mBackgroundBitmap).generate {
                it?.let {
                    mWatchHandHighlightColor = it.getVibrantColor(Color.RED)
                    mWatchHandColor = it.getLightVibrantColor(Color.WHITE)
                    mWatchHandShadowColor = it.getDarkMutedColor(Color.BLACK)
                    updateWatchHandStyle()
                }
            }
        }

        private fun initializeWatchFace() {
            /* Set defaults for colors */
            mWatchHandColor = Color.WHITE
            mWatchHandHighlightColor = Color.RED
            mWatchHandShadowColor = Color.BLACK

            mHourPaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = HOUR_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
            }

            mMinutePaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = MINUTE_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
            }

            mSecondPaint = Paint().apply {
                color = mWatchHandHighlightColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
            }

            mTickAndCirclePaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = true
                style = Paint.Style.STROKE
                setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
            }
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            mBurnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            updateWatchHandStyle()

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        override fun onApplyWindowInsets(insets: WindowInsets) : Unit {
            super.onApplyWindowInsets(insets)

            val resources: Resources = this@MyWatchFace.resources
            bIsRound = insets.isRound
            mXOffset = resources.getDimension(if (bIsRound) R.dimen.digital_x_offset_round else R.dimen.digital_x_offset)
            mYOffset = resources.getDimension(if (bIsRound) R.dimen.digital_y_offset_round else R.dimen.digital_y_offset)
            val textSize = resources.getDimension(if (bIsRound) R.dimen.digital_text_size_round else R.dimen.digital_text_size)
            val amPmSize = resources.getDimension(if (bIsRound) R.dimen.digital_am_pm_size_round else R.dimen.digital_am_pm_size)

            mDatePaint.textSize = resources.getDimension(R.dimen.digital_date_text_size)
            mHour2Paint.textSize = textSize
            mMinSecPaint.textSize = textSize
            mAmPmPaint.textSize = amPmSize
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mHourPaint.color = Color.WHITE
                mMinutePaint.color = Color.WHITE
                mSecondPaint.color = Color.WHITE
                mTickAndCirclePaint.color = Color.WHITE

                mHourPaint.isAntiAlias = false
                mMinutePaint.isAntiAlias = false
                mSecondPaint.isAntiAlias = false
                mTickAndCirclePaint.isAntiAlias = false

                mHourPaint.clearShadowLayer()
                mMinutePaint.clearShadowLayer()
                mSecondPaint.clearShadowLayer()
                mTickAndCirclePaint.clearShadowLayer()

            } else {
                mHourPaint.color = mWatchHandColor
                mMinutePaint.color = mWatchHandColor
                mSecondPaint.color = mWatchHandHighlightColor
                mTickAndCirclePaint.color = mWatchHandColor

                mHourPaint.isAntiAlias = true
                mMinutePaint.isAntiAlias = true
                mSecondPaint.isAntiAlias = true
                mTickAndCirclePaint.isAntiAlias = true

                mHourPaint.setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
                mMinutePaint.setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
                mSecondPaint.setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
                mTickAndCirclePaint.setShadowLayer(
                    SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor
                )
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                mHourPaint.alpha = if (inMuteMode) 100 else 255
                mMinutePaint.alpha = if (inMuteMode) 100 else 255
                mSecondPaint.alpha = if (inMuteMode) 80 else 255
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f
            mCenterY = height / 2f

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            mSecondHandLength = (mCenterX * 0.875).toFloat()
            sMinuteHandLength = (mCenterX * 0.75).toFloat()
            sHourHandLength = (mCenterX * 0.5).toFloat()

            /* Scale loaded background image (more efficient) if surface dimensions change. */
            val scale = width.toFloat() / mBackgroundBitmap.width.toFloat()

            mBackgroundBitmap = Bitmap.createScaledBitmap(
                mBackgroundBitmap,
                (mBackgroundBitmap.width * scale).toInt(),
                (mBackgroundBitmap.height * scale).toInt(), true
            )

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don"t want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren"t
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap()
            }
        }

        private fun initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                mBackgroundBitmap.width,
                mBackgroundBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(mGrayBackgroundBitmap)
            val grayPaint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint.colorFilter = filter
            canvas.drawBitmap(mBackgroundBitmap, 0f, 0f, grayPaint)
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP ->
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(applicationContext, R.string.message, Toast.LENGTH_SHORT)
                        .show()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now
            val is24Hour:Boolean = DateFormat.is24HourFormat(this@MyWatchFace )
            val resources: Resources = this@MyWatchFace.resources

            drawBackground(canvas)
            drawWatchFace(canvas)

            var X: Float = mXOffset

            val strAm: String
            val strPm: String
            val strHour: String

            val paintAm: Paint = Paint()
            val paintPm: Paint = Paint()

            paintAm.set(mAmPmPaint)
            paintPm.set(mAmPmPaint)

            if( is24Hour ){
                X += mAmPmPaint.measureText(AM_STRING) / 2F

                strHour = String.format("%02d", mCalendar.get(Calendar.HOUR_OF_DAY))
                strAm = ""
                strPm = ""
            }
            else{
                var Hour = mCalendar.get(Calendar.HOUR)
                if( Hour == 0 )
                    Hour = 12
                if( Calendar.AM == mCalendar.get(Calendar.AM_PM) )
                    paintAm.color = Color.YELLOW
                else
                    paintPm.color = Color.YELLOW

                strAm = AM_STRING
                strPm = PM_STRING
                strHour = String.format("%02d", Hour)
            }
            canvas.drawText(strHour, X, mYOffset, mHour2Paint)
            X += mHour2Paint.measureText(strHour)

            canvas.drawText(COLON_STRING, X, mYOffset, mMinSecPaint)
            X += mMinSecPaint.measureText(COLON_STRING)

            val strMin: String = String.format("%02d", mCalendar.get(Calendar.MINUTE))
            canvas.drawText(strMin, X, mYOffset, mMinSecPaint)
            X += mMinSecPaint.measureText(strMin)

            canvas.drawText(COLON_STRING, X, mYOffset, mMinSecPaint)
            X += mMinSecPaint.measureText(COLON_STRING)

            val strSec: String = String.format("%02d", mCalendar.get(Calendar.SECOND))
            canvas.drawText(strSec,X, mYOffset, mMinSecPaint)
            X += mMinSecPaint.measureText(strSec)
            X += 2F
            canvas.drawText(strAm, X, ( mYOffset - resources.getDimension(if(bIsRound) R.dimen.digital_am_pm_size else R.dimen.digital_am_pm_size_round ) ), paintAm)
            canvas.drawText(strPm, X, mYOffset, paintPm)
        }

        private fun drawBackground(canvas: Canvas) {

            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK)
            } else if (mAmbient) {
                canvas.drawBitmap(mGrayBackgroundBitmap, 0f, 0f, mBackgroundPaint)
            } else {
                canvas.drawBitmap(mBackgroundBitmap, 0f, 0f, mBackgroundPaint)
            }
        }

        private fun drawWatchFace(canvas: Canvas) {

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            val innerTickRadius = mCenterX - 10
            val outerTickRadius = mCenterX
            val mLongTickPaint = Paint()
            mLongTickPaint.set(mTickAndCirclePaint)
            mLongTickPaint.strokeWidth = HOUR_STROKE_WIDTH

            val intArray = arrayOf(0,5,10,15,20,25,30,35,40,45,50,55)

            for (tickIndex in 0..59) {
                if( tickIndex % 5 == 0 )
                {
                    val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 60).toFloat()
                    val strNum = intArray[tickIndex/5].toString()

                    val innerX = Math.sin(tickRot.toDouble()).toFloat() * ( innerTickRadius - 10 )
                    val innerY = (-Math.cos(tickRot.toDouble())).toFloat() * ( innerTickRadius - 10 )
                    val outerX = Math.sin(tickRot.toDouble()).toFloat() * outerTickRadius
                    val outerY = (-Math.cos(tickRot.toDouble())).toFloat() * outerTickRadius

                    canvas.drawLine(
                        mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mLongTickPaint
                    )

                    val innerNumX = Math.sin(tickRot.toDouble()).toFloat() * (innerTickRadius - 30)
                    val innerNumY =
                        (-Math.cos(tickRot.toDouble())).toFloat() * (innerTickRadius - 30)
                    canvas.drawText(
                        strNum,
                        mCenterX + innerNumX - (mAmPmPaint.measureText(strNum) / 2F),
                        mCenterY + innerNumY + (mAmPmPaint.textSize / 3F),
                        mAmPmPaint
                    )
                }
                else
                {
                    val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 60).toFloat()
                    val innerX = Math.sin(tickRot.toDouble()).toFloat() * innerTickRadius
                    val innerY = (-Math.cos(tickRot.toDouble())).toFloat() * innerTickRadius
                    val outerX = Math.sin(tickRot.toDouble()).toFloat() * outerTickRadius
                    val outerY = (-Math.cos(tickRot.toDouble())).toFloat() * outerTickRadius

                    canvas.drawLine(
                        mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint
                    )
                }
            }

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds =
                mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f
            val secondsRotation = seconds * 6f

            val minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f

            val hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f
            val hoursRotation = mCalendar.get(Calendar.HOUR) * 30 + hourHandOffset

            /*
             * Save the canvas state before we can begin to rotate it.
             */
            canvas.save()

            canvas.rotate(hoursRotation, mCenterX, mCenterY)
            canvas.drawLine(
                mCenterX,
                mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                mCenterX,
                mCenterY - sHourHandLength,
                mHourPaint
            )

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY)
            canvas.drawLine(
                mCenterX,
                mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                mCenterX,
                mCenterY - sMinuteHandLength,
                mMinutePaint
            )

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY)
                canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - mSecondHandLength,
                    mSecondPaint
                )

            }
            canvas.drawCircle(
                mCenterX,
                mCenterY,
                CENTER_GAP_AND_CIRCLE_RADIUS,
                mTickAndCirclePaint
            )

            /* Restore the canvas" original orientation. */
            canvas.restore()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren"t visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@MyWatchFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@MyWatchFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }
    }
}