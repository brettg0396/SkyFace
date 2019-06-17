package com.example.skyface.utils

import java.net.URL
import com.google.gson.Gson
import android.location.Location
import com.example.skyface.models.WeatherData
import com.example.skyface.models.ForecastData
import org.jetbrains.anko.doAsync
import android.content.Context
import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt
import android.graphics.PorterDuffXfermode
import java.lang.Thread.sleep

fun wrapMod(a: Float, b: Float): Float{
    var r=a.rem(b)
    if (r<0) return r+b
    return r
}

class Sky(context: Context){
    private val gson = Gson()
    private var myLocation: Location? = null
    private var Forecast: ForecastData? = null
    private var Weather: WeatherData? = null
    private var weatherCode = 800
    private val myContext: Context
    private lateinit var skyImage: Bitmap
    private var effects: MutableList<Effect> = mutableListOf()
    private var tz = TimeZone.getDefault()
    private var date: Calendar
    private lateinit var solar: Solar
    private var lightning: Boolean = false
    private var currentLightning: Int = 0

    data class Solar(
        var day: Int,
        val dawn: Sun,
        val dusk: Sun,
        var today: Long,
        var tomorrow: Long
    )

    data class Sun(
        var set: Long,
        var dim_time: Long = 1000*60
                *60, //1 hour in ms
        var bright_time: Long = 1000*60
                *60*2, //2 hours in ms
        var start: Long,
        var end: Long
    )

    init{
        myContext=context
        date = Calendar.getInstance(tz)
        initSolar()
    }

    fun getSkyImage(): Bitmap{

        if (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) != solar.day) {
            setDate()
            initSolar()
        }

        /**
         * Get the colors (image) for the sky depending on the season
         * (Summer has more vibrant colors, winter more muted, fall/spring in between)
         */

        val starImg: Bitmap = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stars)
        var seasonImg: Bitmap

        var updateEffects = false


        Weather?.let {
            if (it.dt * 1000 >= solar.today) {
                if (it.sys.sunrise*1000 != solar.dawn.set || it.sys.sunset*1000 != solar.dusk.set) {
                    updateSolar(it.sys.sunrise*1000, it.sys.sunset*1000, date.clone() as Calendar)
                }
                if (weatherCode != it.weather[0].id) {
                    weatherCode = it.weather[0].id
                    updateEffects = true
                }
            }
        }

        when(weatherCode) {
            in 200..232, in 502..511, in 602..611 -> {
                seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_stormy)
            }
            in 300..321, 500, 501, in 520..531, in 600..601, in 612..622, in 701..781, 804 -> {
                seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_overcast)
            }
            else -> when (date.get(Calendar.MONTH)) {
                in 0..1 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                }
                in 2..4 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
                }
                in 5..7 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_summer)
                }
                in 8..10 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_fall)
                }
                11 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                }
                else -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
                }
            }
        }
        /**
         * Calculate how the tall "sky" image should be cropped depending on the time of day as dusk or dawn approaches,
         * at a rate dependent on the size of the image, and therefore the DPI of the device (Time is in milliseconds since epoch)
         */

        val skyWidth: Int = seasonImg.width
        val sunHeight: Int = 2*skyWidth
        val dayHeight: Int = seasonImg.height - skyWidth
        val duskHeight: Int = seasonImg.height - skyWidth - sunHeight
        val time: Int = when(val currentTime: Long = Calendar.getInstance().timeInMillis){
            in solar.today..solar.dawn.start -> 0
            in solar.dawn.start+1..solar.dawn.set -> getTime(currentTime,solar.dawn.start,solar.dawn.set,sunHeight,skyWidth,reversed=true)
            in solar.dawn.set+1..solar.dawn.end -> getTime(currentTime,solar.dawn.set,solar.dawn.end,duskHeight,sunHeight,reversed=true)
            in solar.dawn.end+1..solar.dusk.start -> dayHeight
            in solar.dusk.start+1..solar.dusk.set -> getTime(currentTime,solar.dusk.start,solar.dusk.set,duskHeight,sunHeight)
            in solar.dusk.set+1..solar.dusk.end -> getTime(currentTime,solar.dusk.set,solar.dusk.end,sunHeight,skyWidth)
            in solar.dusk.end+1..solar.tomorrow -> 0
            else -> 0
        }


        if (updateEffects) setEffects(starImg.width,starImg.height,seasonImg)

        seasonImg = Bitmap.createBitmap(seasonImg, 0, time,skyWidth, skyWidth)
        val result = Bitmap.createBitmap(starImg.width, starImg.height, starImg.config)
        val canvas = Canvas(result)
        canvas.drawBitmap(starImg, 0f, 0f, null)
        canvas.drawBitmap(seasonImg, 0f, 0f, null)
        skyImage = result
        for (effect in effects){
            effect.setShader(time)
        }
        return result
    }

    fun printTime(time: Long): String{
        val cal: Calendar = Calendar.getInstance()
        cal.timeInMillis = time
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(cal.time)
    }

    private fun getTime(currentTime: Long, start: Long, end: Long, max: Int, min: Int, reversed: Boolean = false): Int{
        val time: Int
        val totalDiff: Float = (end - start).toFloat() //get time the shift should encompass
        val diff: Float = (currentTime - start).toFloat() //get current time in comparison to total
        val rate: Float = max.toFloat()/totalDiff //divide number of pixels to travel over total time in shift
        val progress: Int = (rate*diff).roundToInt() //multiply current time by rate to get current pixel height of shift
        time = if (reversed){
            when{
                min+progress > max -> max
                else -> min+progress
            }
        }
        else {
            when{
                max-progress < min -> min
                else -> max-progress
            }
        }
        return time

    }

    private fun setEffects(maxWidth: Int, maxHeight: Int, defaultRecolor: Bitmap) {
        val windSpeed: Float = Weather?.wind?.speed ?: 3f
        val recolorImg: Bitmap
        val vertRate: Float = maxHeight.toFloat()
        val horRate: Float = maxWidth.toFloat()
        val code = weatherCode
        when (code) {
            in 200..232, in 502..511, in 602..611 -> {
                recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_stormy)
                effects = mutableListOf(
                Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy3)), "cloud",  maxWidth,maxHeight, 0, windSpeed*1/3, recolorImg) ,
                Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy2)), "cloud",  maxWidth,maxHeight, 0, windSpeed*2/3, recolorImg) ,
                Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy1)), "cloud",  maxWidth,maxHeight, 0, windSpeed, recolorImg )
                )
                when (code) {
                    in 200..232 -> {
                        effects.add(
                            Effect(
                                listOf(
                                    BitmapFactory.decodeResource(
                                        myContext.resources,
                                        com.example.skyface.R.drawable.lightning1
                                    ),
                                    BitmapFactory.decodeResource(
                                        myContext.resources,
                                        com.example.skyface.R.drawable.lightning2
                                    ),
                                    BitmapFactory.decodeResource(
                                        myContext.resources,
                                        com.example.skyface.R.drawable.lightning3
                                    ),
                                    BitmapFactory.decodeResource(
                                        myContext.resources,
                                        com.example.skyface.R.drawable.lightning4
                                    )
                                ), "lightning", maxWidth, maxHeight, isVisible = false
                            )
                        )
                        when (code) {
                            200, in 231..232 -> effects.add(0,
                                Effect(
                                    listOf(
                                        BitmapFactory.decodeResource(
                                            myContext.resources,
                                            com.example.skyface.R.drawable.light_rain
                                        )
                                    ), "cloud", maxWidth, maxHeight, 3, vertRate * 2, recolorImg
                                )
                            )
                        }
                        when (code) {
                            201 -> effects.add(0,
                                Effect(
                                    listOf(
                                        BitmapFactory.decodeResource(
                                            myContext.resources,
                                            com.example.skyface.R.drawable.rain
                                        )
                                    ), "cloud", maxWidth, maxHeight, 3, vertRate * 3, recolorImg
                                )
                            )
                        }
                        when (code) {
                            202 -> effects.add(0,
                                Effect(
                                    listOf(
                                        BitmapFactory.decodeResource(
                                            myContext.resources,
                                            com.example.skyface.R.drawable.heavy_rain
                                        )
                                    ), "cloud", maxWidth, maxHeight, 3, vertRate * 4, recolorImg
                                )
                            )
                        }
                    }
                }
            }
            in 300..321, 500, 501, in 520..531, in 600..601, in 612..622, in 701..781, 804 -> {
                recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_overcast)
                effects = mutableListOf(
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast3)), "cloud",  maxWidth,maxHeight, 0, windSpeed*1/3, recolorImg ),
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast2)), "cloud",  maxWidth,maxHeight, 0, windSpeed*2/3, recolorImg ),
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast1)), "cloud",  maxWidth,maxHeight, 0, windSpeed, recolorImg )
                )
                when (code){
                    in 301..310,500,520,531,615 -> {
                        effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.light_rain)), "cloud", maxWidth, maxHeight, 3,vertRate*2, recolorImg))
                        when (code) {
                            615 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.snow)), "cloud", maxWidth, maxHeight, 3,vertRate/2, recolorImg))
                        }
                    }
                    in 311..313,321,501,511,521,616 -> {
                        effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.rain)), "cloud", maxWidth, maxHeight, 3,vertRate*3, recolorImg))
                        when (code){
                            616 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.snow)), "cloud", maxWidth, maxHeight, 3,vertRate/2, recolorImg))
                        }
                    }
                    314, in 502..504,522 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.heavy_rain)), "cloud", maxWidth, maxHeight, 3,vertRate*4, recolorImg))
                    600,620 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.light_snow)), "cloud", maxWidth, maxHeight, 3,vertRate/4, recolorImg))
                    601,621 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.snow)), "cloud", maxWidth, maxHeight, 3,vertRate/2, recolorImg))
                    602,622 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.heavy_snow)), "cloud", maxWidth, maxHeight, 3,vertRate, recolorImg))
                    611 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.sleet)), "cloud", maxWidth, maxHeight, 3,vertRate*3, recolorImg))
                    612 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.light_sleet)), "cloud", maxWidth, maxHeight, 3,vertRate*2, recolorImg))
                    613 -> effects.add(0,Effect(listOf(BitmapFactory.decodeResource(myContext.resources,com.example.skyface.R.drawable.heavy_sleet)), "cloud", maxWidth, maxHeight, 3,vertRate*4, recolorImg))
                }
            }
            801 -> {
                recolorImg = defaultRecolor
                effects = mutableListOf(
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1)), "cloud",  maxWidth,maxHeight, 0, windSpeed, recolorImg )
                )
            }
            802 -> {
                recolorImg = defaultRecolor
                effects = mutableListOf(
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds2)), "cloud",  maxWidth,maxHeight, 0, windSpeed*1/3, recolorImg ),
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1)), "cloud",  maxWidth,maxHeight, 0, windSpeed*2/3, recolorImg )
                )
            }
            803 -> {
                recolorImg = defaultRecolor
                effects = mutableListOf(
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds3)), "cloud",  maxWidth,maxHeight, 0, windSpeed*1/3, recolorImg ),
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds2)), "cloud",  maxWidth,maxHeight, 0, windSpeed*2/3, recolorImg ),
                    Effect(listOf(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1)),  "cloud",  maxWidth,maxHeight, 0, windSpeed, recolorImg )
                )
            }
            else -> {
                effects = mutableListOf()
            }
        }
    }

    fun hasEffect(effectType: String): Boolean{
        return effects.any{it.getType() == effectType}
    }

    fun getEffects(): List<Effect>{
        return effects.toList()
    }

    fun flashLightning(){
        if (hasEffect("lightning")){

            doAsync {
                currentLightning = (0..3).random()
                val lightningIndex = effects.indexOfFirst{it.getType() == "lightning"}
                val lightningFrame = effects[lightningIndex].apply{
                    val srcImage = this.getImages()[0]
                    setPos((-(srcImage.width * 1.0 / 5).roundToInt()..(srcImage.width * 1.0 / 5).roundToInt()).random().toFloat(),0F)
                }
                if (!lightningFrame.isVisible()) {
                    val newLightningFrame = (1..3).random()
                    effects.add(newLightningFrame, lightningFrame)
                    effects[newLightningFrame].setVisibility(true)
                    sleep(250)
                    lightning = true
                    sleep(250)
                    lightning = false
                    sleep(250)
                    lightning = true
                    sleep(500)
                    effects[newLightningFrame].setVisibility(false)
                    lightning = false
                }
            }
        }
    }

    fun getLightning(): Boolean{
        return lightning
    }

    fun getFrames(): List<Bitmap>{
        val frames: MutableList<Bitmap> = mutableListOf()
        for (effect in effects){
            frames.add(effect.getFrame())
        }
        return frames.toList()
    }

    fun getCombinedFrame(): Bitmap?{
        val frame: Bitmap?
        if (effects.size >= 2){
            frame = addEffects(0,1,effects.size)
        }
        else{
            if (effects.isNotEmpty()){
                frame = effects[0].getFrame()
            }
            else{
                frame = null
            }
        }

        return frame
    }

    private fun addEffects(effect1: Int, effect2: Int, max: Int): Bitmap{
        val frame = if (effect2+1 < max){
            addEffects(effect1+1,effect2+1,max)
        }
        else{
            effects[effect2].getFrame()
        }
        return effects[effect1]+frame
    }

    inner class Effect(images: List<Bitmap>, effectType: String, maxw: Int, maxh: Int, moveDirection: Int = 0, moveRate: Float = 0F, recolorImg: Bitmap? = null, isVisible: Boolean = true) {
        private var x: Float = 0f
        private var y: Float = 0f
        private val srcImages: List<Bitmap>
        private val type: String
        private val direction: Int
        private val rate: Float
        private val maxWidth: Int
        private val maxHeight: Int
        private var lastUpdate: Long
        private val recolor: Bitmap?
        private var visible: Boolean
        private var shader: Bitmap?

        init {
            srcImages = images
            type = effectType
            direction = moveDirection
            rate = moveRate / 1000
            maxWidth = maxw
            maxHeight = maxh
            lastUpdate = Calendar.getInstance().timeInMillis
            recolor = recolorImg
            shader = null
            visible = isVisible
        }

        fun isVisible(): Boolean{
            return visible
        }

        fun setVisibility(visibility: Boolean){
            visible = visibility
        }

        fun setPos(newX: Float, newY: Float){
            x = newX
            y = newY
        }

        operator fun plus(effect: Effect): Bitmap {
            val frame1 = getFrame()
            val frame2 = effect.getFrame()
            val result = Bitmap.createBitmap(frame2.width, frame2.height, frame2.config)
            val canvas = Canvas(result)
            canvas.drawBitmap(frame2, 0f, 0f, null)
            canvas.drawBitmap(frame1, 0f, 0f, null)
            return result
        }

        operator fun plus(bitmap: Bitmap): Bitmap {
            val frame1 = getFrame()
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            val canvas = Canvas(result)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            canvas.drawBitmap(frame1, 0f, 0f, null)
            return result
        }

        fun getType(): String{
            return type
        }

        fun getImages(): List<Bitmap>{
            return srcImages
        }

        fun getEmptyFrame(): Bitmap{
            return Bitmap.createBitmap(maxWidth,maxHeight,srcImages[0].config)
        }

        fun setShader(time: Int){
            recolor?.let {
                shader = Bitmap.createBitmap(it, 0, time, maxWidth, maxHeight)
            }
        }

        fun getShader(): Bitmap {
            shader?.let{
                return it
            }
            return Bitmap.createBitmap(maxWidth,maxHeight,Bitmap.Config.ARGB_8888)
        }

        fun getPaint(): Paint? {
            val color = Paint()
            shader?.let{
                color.xfermode=PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            return color
        }

        fun getFrame(): Bitmap {
            val frame: Bitmap
            val time = Calendar.getInstance().timeInMillis
            var srcImage = srcImages[0]
            when (type) {
                "cloud" -> {
                    if (visible) {
                        when (direction) {
                            0 -> x = wrapMod((x + (time - lastUpdate) * rate),srcImage.width.toFloat())
                            1 -> y = wrapMod((y + (time - lastUpdate) * rate),srcImage.height.toFloat())
                            2 -> x = wrapMod((x - (time - lastUpdate) * rate),srcImage.width.toFloat())
                            3 -> y = wrapMod((y - (time - lastUpdate) * rate),srcImage.height.toFloat())
                            else -> x = wrapMod((x + (time - lastUpdate) * rate),srcImage.width.toFloat())
                        }
                        lastUpdate = time

                        val xInt = x.roundToInt().rem(srcImage.width)
                        val yInt = y.roundToInt().rem(srcImage.height)

                        when (direction % 2) {
                            0 -> {
                                if (srcImage.width - xInt in 1 until maxWidth) {
                                    val frame1 =
                                        Bitmap.createBitmap(srcImage, xInt, yInt, srcImage.width - xInt, maxHeight)
                                    val frame2 = Bitmap.createBitmap(
                                        srcImage,
                                        0,
                                        yInt,
                                        maxWidth - (srcImage.width - xInt),
                                        maxHeight
                                    )
                                    val result = Bitmap.createBitmap(maxWidth, maxHeight, srcImage.config)
                                    val canvas = Canvas(result)
                                    canvas.drawBitmap(frame1, 0f, 0f, null)
                                    canvas.drawBitmap(frame2, (srcImage.width - xInt).toFloat(), 0f, null)
                                    frame = result
                                } else {
                                    frame = Bitmap.createBitmap(srcImage, xInt, yInt, maxWidth, maxHeight)
                                }
                            }
                            else -> {
                                frame = if (srcImage.height - yInt in 1 until maxHeight) {
                                    val frame1 =
                                        Bitmap.createBitmap(srcImage, xInt, yInt, maxWidth, srcImage.height - yInt)
                                    val frame2 = Bitmap.createBitmap(srcImage, xInt,0, maxWidth, maxHeight - (srcImage.height - yInt)
                                    )
                                    val result = Bitmap.createBitmap(maxWidth, maxHeight, srcImage.config)
                                    val canvas = Canvas(result)
                                    canvas.drawBitmap(frame1, 0f, 0f, null)
                                    canvas.drawBitmap(frame2, 0f, (srcImage.height - yInt).toFloat(), null)
                                    result
                                } else {
                                    Bitmap.createBitmap(srcImage, xInt, yInt, maxWidth, maxHeight)
                                }
                            }
                        }
                    }
                    else frame = getEmptyFrame()
                }
                "lightning" -> {
                    if (visible) {
                        srcImage = srcImages[currentLightning]

                        val result = Bitmap.createBitmap(maxWidth, maxHeight, srcImage.config)
                        val canvas = Canvas(result)
                        canvas.drawBitmap(srcImage,x.rem(maxWidth),y,null)
                        frame = result
                    }
                    else frame = getEmptyFrame()

                }
                else -> {
                    frame = Bitmap.createBitmap(maxWidth, maxHeight, srcImages[0].config)
                }
            }
            return frame
        }
    }

    private fun updateSolar(sunrise: Long, sunset: Long, today: Calendar){
        today.set(Calendar.HOUR_OF_DAY,0)
        today.set(Calendar.MINUTE,0)
        today.set(Calendar.SECOND,0)
        solar.dusk.set = sunset
        solar.dusk.start = sunset - solar.dusk.bright_time
        solar.dusk.end = sunset + solar.dusk.dim_time
        solar.dawn.set = sunrise
        solar.dawn.start = sunrise - solar.dawn.dim_time
        solar.dawn.end = sunrise + solar.dawn.bright_time
        solar.day = today.get(Calendar.DAY_OF_MONTH)
        solar.today = today.timeInMillis
        today.add(Calendar.DAY_OF_YEAR,1)
        solar.tomorrow = today.timeInMillis
    }

    private fun initSolar(){
        val duskDate: Calendar = date.clone() as Calendar
        duskDate.set(Calendar.HOUR_OF_DAY,0)
        duskDate.set(Calendar.MINUTE,0)
        duskDate.set(Calendar.SECOND,0)
        val todayCal: Calendar = duskDate.clone() as Calendar
        duskDate.add(Calendar.MILLISECOND,getDSTOFFSET())
        val dawnDate: Calendar = duskDate.clone() as Calendar
        duskDate.add(Calendar.HOUR_OF_DAY,19)
        dawnDate.add(Calendar.HOUR_OF_DAY,7)
        val dusk = Sun(set=duskDate.timeInMillis,start=duskDate.timeInMillis,end=duskDate.timeInMillis)
        val dawn = Sun(set=dawnDate.timeInMillis,start=dawnDate.timeInMillis,end=dawnDate.timeInMillis)
        dusk.start-=dusk.bright_time
        dusk.end+=dusk.dim_time
        dawn.start-=dawn.dim_time
        dawn.end+=dawn.bright_time
        val today: Long = todayCal.timeInMillis
        val month_day: Int = todayCal.get(Calendar.DAY_OF_MONTH)
        todayCal.add(Calendar.DAY_OF_YEAR,1)
        val tomorrow: Long = todayCal.timeInMillis
        solar = Solar(month_day,dawn,dusk,today,tomorrow)
    }

    private fun getDSTOFFSET(): Int{
        val tempCal: Calendar = Calendar.getInstance(tz)
        return when(tz.inDaylightTime(tempCal.time)){
            true -> tempCal.get(Calendar.DST_OFFSET)
            false -> 0
        }
    }

    fun updateTZ(){
        tz = TimeZone.getDefault()
        date=Calendar.getInstance(tz)
    }

    fun setDate(){
        date = Calendar.getInstance(tz)
    }

    fun setLocation(location: Location){
        myLocation = location
    }

    fun setWeather(){
        val weather_url: String = URL_Builder.getWeather(myLocation!!.latitude, myLocation!!.longitude)
        Weather = gson.fromJson(URL(weather_url).readText(), WeatherData::class.java)
    }

    fun setForecast(){
        val forecast_url: String = URL_Builder.getForecast(myLocation!!.latitude, myLocation!!.longitude)
        Forecast = gson.fromJson(URL(forecast_url).readText(), ForecastData::class.java)
    }

    fun getDate(): Calendar?{
        return date
    }

    fun getWeather(): WeatherData?{
        return Weather
    }

    fun getForecast(): ForecastData?{
        return Forecast
    }

    fun getLocation(): Location?{
        return myLocation
    }

    fun testWeather(latitude: Double, longitude: Double){
        val weather_url: String = URL_Builder.getWeather(latitude, longitude)
        Weather = gson.fromJson(URL(weather_url).readText(), WeatherData::class.java)
    }
}