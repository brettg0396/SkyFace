package com.example.skyface

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v7.graphics.Palette
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.view.SurfaceHolder
import android.widget.Toast
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.location.Location
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import com.example.skyface.utils.Sky
import org.jetbrains.anko.doAsync
import java.lang.ref.WeakReference
import java.util.*

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val HOUR_STROKE_WIDTH = 5f
private const val MINUTE_STROKE_WIDTH = 3f
private const val SECOND_TICK_STROKE_WIDTH = 2f

private const val CENTER_GAP_AND_CIRCLE_RADIUS = 4f

private const val SHADOW_RADIUS = 6f
private const val WEATHER_INTERVAL: Long = 1000*60*20 // 20 minutes (in ms)
private const val SCREEN_INTERVAL: Long = 1000*5 // 5 seconds

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
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


class PermissionRequestActivity : Activity() {
    private lateinit var mPermissions: Array<String?>
    private var mRequestCode: Int = 0

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == mRequestCode) {
            for (i in permissions.indices) {
                val permission = permissions[i]
                val grantResult = grantResults[i]
                if (grantResult == PackageManager.PERMISSION_GRANTED){
                    ActivityCompat.requestPermissions(this, arrayOf(permission),requestCode)
                }
            }
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        mPermissions = this.intent.getStringArrayExtra("KEY_PERMISSIONS")
        mRequestCode = this.intent.getIntExtra("KEY_REQUEST_CODE", PERMISSIONS_CODE)

        ActivityCompat.requestPermissions(this, mPermissions, mRequestCode)
    }

    companion object {
        private val PERMISSIONS_CODE = 0
    }
}

class MyWatchFace : CanvasWatchFaceService() {


    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: Engine) : Handler() {
        private val mWeakReference: WeakReference<Engine> = WeakReference(reference)

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

        private var SkyImage: Sky = Sky(applicationContext)
        private var updateLock: Boolean = false
        private var lastUpdate: Long = 0L

        private lateinit var fusedLocationClient: FusedLocationProviderClient

        private lateinit var mCalendar: Calendar
        private var myLocation: Location? = null

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        private var mSecondHandLength: Float = 0F
        private var sMinuteHandLength: Float = 0F
        private var sHourHandLength: Float = 0F

        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private var mWatchHandColor: Int = 0
        private var mWatchHandHighlightColor: Int = 0
        private var mWatchHandShadowColor: Int = 0
        private var tempWatchHandColor: Int = mWatchHandColor
        private var tempWatchHandHighlightColor: Int = mWatchHandHighlightColor
        private var tempWatchHandShadowColor: Int  = mWatchHandShadowColor

        private lateinit var mHourPaint: Paint
        private lateinit var mMinutePaint: Paint
        private lateinit var mSecondPaint: Paint
        private lateinit var mTickAndCirclePaint: Paint

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mBackgroundBitmap: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap
        private lateinit var tempBackgroundBitmap: Bitmap
        private lateinit var tempGrayBackgroundBitmap: Bitmap
        private var tempEffects: List<Sky.Effect>? = emptyList()

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false

        private val clock: Timer = Timer()

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        inner class LightningFlash: TimerTask(){
            override fun run(){
                if ((Calendar.getInstance().timeInMillis < lastUpdate + 30000L) && SkyImage.hasEffect("lightning")) {
                    SkyImage.flashLightning()
                }
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(contxt: Context?, intent: Intent?) {

                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> onWake()

                    Intent.ACTION_SCREEN_OFF -> onSleep()
                }
            }
        }

        private fun setTemps(){
            tempBackgroundBitmap = mBackgroundBitmap
            Palette.from(tempBackgroundBitmap).generate {
                it?.let {
                    tempWatchHandHighlightColor = it.getVibrantColor(Color.RED)
                    tempWatchHandColor = it.getLightVibrantColor(Color.WHITE)
                    tempWatchHandShadowColor = it.getDarkMutedColor(Color.BLACK)
                }
            }
            tempGrayBackgroundBitmap = mGrayBackgroundBitmap
        }

        private fun shouldUpdate(): Boolean{
            if ((Calendar.getInstance().timeInMillis > SkyImage.getDate()!!.timeInMillis + WEATHER_INTERVAL) || SkyImage.getLocation() == null){
                return true
            }
            return false
        }

        override fun onCreate(holder: SurfaceHolder) {

            super.onCreate(holder)

            this@MyWatchFace.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

            requestPermission(arrayOf(android.Manifest.permission.BODY_SENSORS,android.Manifest.permission.ACCESS_COARSE_LOCATION))


            fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MyWatchFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            mCalendar = Calendar.getInstance()

            clock.schedule(LightningFlash(),1000L,6000L)

            initializeWatchFace()
            updateLock=true
            initializeBackground()
            initGrayBackgroundBitmap()
            updateLock=false
            setTemps()
            getLastLocation()
        }

        private fun updateBackground(priority: Boolean=false){
            doAsync {
                var doUpdate = false
                if (priority){
                    while (updateLock){

                    }
                    doUpdate = true
                } else if (!updateLock && (Calendar.getInstance().timeInMillis >= lastUpdate+SCREEN_INTERVAL)) {
                    doUpdate = true

                }
                if (doUpdate)
                {
                    updateLock = true
                    initializeBackground()
                    initGrayBackgroundBitmap()
                    lastUpdate=Calendar.getInstance().timeInMillis
                    updateLock = false
                    setTemps()
                }
            }
        }

        private fun onWake(){
            if (timeZoneChanged()) {
                SkyImage.updateTZ()
                getLastLocation()
            }else if (shouldUpdate())
                getLastLocation()

            updateBackground()
        }

        private fun onSleep()
        {
        }

        private fun requestPermission(permissions: Array<String>){
            val myIntent = Intent(baseContext, PermissionRequestActivity::class.java)
            myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            myIntent.putExtra("KEY_PERMISSIONS", permissions)
            startActivity(myIntent)
        }

        private fun getLastLocation()
        {
            if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        SkyImage.setDate()
                        if (location != null) {
                            myLocation = location
                            SkyImage.setLocation(location)
                            doAsync {
                                SkyImage.setWeather()
                                SkyImage.setForecast()
                                updateBackground(priority = true)
                            }
                        }
                    }
            }
        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }

            mBackgroundBitmap = SkyImage.getSkyImage()

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
            this@MyWatchFace.unregisterReceiver(screenReceiver)
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
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
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
                {
                    val message: String = SkyImage.getWeather()?.let {
                        "Location: ${it.name}\nWeather: ${it.weather[0].description}\nCode: ${it.weather[0].id}\nFetched: ${SkyImage.printTime(it.dt*1000)}\nLast Checked: ${(Calendar.getInstance().timeInMillis - SkyImage.getDate()!!.timeInMillis)/1000/60} minutes ago."
                    } ?: "Could not get location"
                        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
                            .show()

                }
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            drawBackground(canvas)
            drawEffects(canvas)
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {

            val background: Bitmap
            val gray: Bitmap
            val paint = Paint(mBackgroundPaint)
            if (!updateLock){
                background = mBackgroundBitmap
                gray = mGrayBackgroundBitmap
            }
            else {
                background = tempBackgroundBitmap
                gray = tempGrayBackgroundBitmap
            }
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK)
            } else if (mAmbient) {
                canvas.drawBitmap(gray, 0f, 0f, paint.apply{
                    if (SkyImage.getLightning()) colorFilter = PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN)
                })
            } else {
                canvas.drawBitmap(background, 0f, 0f, paint.apply{
                    if (SkyImage.getLightning()) colorFilter = PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN)
                })
            }
        }

        private fun drawEffects(canvas: Canvas) {
            val effects = if (!updateLock) SkyImage.getEffects()
            else tempEffects

            effects?.let {
                tempEffects = it
                if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                    canvas.drawColor(Color.BLACK)
                } else if (mAmbient) {
                    for (effect in effects) {
                        val frame = effect.getFrame()
                        val paint = Paint(mBackgroundPaint)
                        canvas.drawBitmap(frame, 0f, 0f, paint.apply {
                            if (SkyImage.getLightning()) colorFilter =
                                PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                        })
                    }
                } else {
                    for (effect in effects) {
                        val frame = effect.getFrame()
                        val effectBitmap = Bitmap.createBitmap(frame.width, frame.height, frame.config)
                        val effectCanvas = Canvas(effectBitmap)
                        effectCanvas.drawBitmap(frame, 0f, 0f, Paint().apply {
                            if (SkyImage.getLightning()) this.colorFilter =
                                PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                        })
                        effectCanvas.drawBitmap(effect.getShader(), 0f, 0f, effect.getPaint().apply {
                                if (SkyImage.getLightning()) this?.colorFilter =
                                    PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                            })
                        canvas.drawBitmap(effectBitmap, 0f, 0f, Paint())
                    }
                }
            }
        }

        private fun drawWatchFace(canvas: Canvas) {

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            val mTickAndCirclePaintCopy = Paint(mTickAndCirclePaint)
            val mHourPaintCopy = Paint(mHourPaint)
            val mMinutePaintCopy = Paint(mMinutePaint)
            val mSecondPaintCopy = Paint(mSecondPaint)
            val innerTickRadius = mCenterX - 10
            val outerTickRadius = mCenterX
            for (tickIndex in 0..11) {
                val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 12).toFloat()
                val innerX = Math.sin(tickRot.toDouble()).toFloat() * innerTickRadius
                val innerY = (-Math.cos(tickRot.toDouble())).toFloat() * innerTickRadius
                val outerX = Math.sin(tickRot.toDouble()).toFloat() * outerTickRadius
                val outerY = (-Math.cos(tickRot.toDouble())).toFloat() * outerTickRadius
                canvas.drawLine(
                    mCenterX + innerX, mCenterY + innerY,
                    mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaintCopy.apply{
                        if (SkyImage.getLightning()) {
                            color = Color.BLACK
                            setShadowLayer(
                                SHADOW_RADIUS, 0f, 0f, Color.WHITE)
                        }
                        else if (updateLock){
                            color = tempWatchHandColor
                            setShadowLayer(
                                SHADOW_RADIUS, 0f, 0f, tempWatchHandShadowColor)
                        }
                    }
                )
            }

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds =
                mCalendar.get(Calendar.SECOND)// + mCalendar.get(Calendar.MILLISECOND) / 1000f
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
                mHourPaintCopy.apply{
                    if (SkyImage.getLightning()) {
                        color = Color.BLACK
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, Color.WHITE)
                    }
                    else if (updateLock){
                        color = tempWatchHandColor
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, tempWatchHandShadowColor)
                    }
                }
            )

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY)
            canvas.drawLine(
                mCenterX,
                mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                mCenterX,
                mCenterY - sMinuteHandLength,
                mMinutePaintCopy.apply{
                    if (SkyImage.getLightning()) {
                        color = Color.BLACK
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, Color.WHITE)
                    }
                    else if (updateLock){
                        color = tempWatchHandColor
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, tempWatchHandShadowColor)
                    }
                }
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
                    mSecondPaintCopy.apply{
                        if (SkyImage.getLightning()) {
                            color = Color.CYAN
                            setShadowLayer(
                                SHADOW_RADIUS, 0f, 0f, Color.WHITE)
                        }
                        else if (updateLock){
                            color = tempWatchHandHighlightColor
                            setShadowLayer(
                                SHADOW_RADIUS, 0f, 0f, tempWatchHandShadowColor)
                        }
                    }
                )

            }
            canvas.drawCircle(
                mCenterX,
                mCenterY,
                CENTER_GAP_AND_CIRCLE_RADIUS,
                mTickAndCirclePaintCopy.apply{
                    if (SkyImage.getLightning()) {
                        color = Color.BLACK
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, Color.WHITE)
                    }
                    else if (updateLock){
                        color = tempWatchHandColor
                        setShadowLayer(
                            SHADOW_RADIUS, 0f, 0f, tempWatchHandShadowColor)
                    }
                }
            )

            /* Restore the canvas' original orientation. */
            canvas.restore()
        }
        
        private fun timeZoneChanged(): Boolean{
            val newTZ = TimeZone.getDefault()
            if (mCalendar.timeZone != newTZ) {
                mCalendar.timeZone = newTZ
                return true
            }
            return false
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                if (timeZoneChanged()) {
                    SkyImage.updateTZ()
                    getLastLocation()
                } else if (shouldUpdate())
                    getLastLocation()
                updateBackground()
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


