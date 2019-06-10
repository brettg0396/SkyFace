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
import kotlin.math.round
import kotlin.math.roundToInt


class Sky(context: Context){
    private val gson = Gson()
    private var myLocation: Location? = null
    private var Forecast: ForecastData? = null
    private var Weather: WeatherData? = null
    private var weatherCode = 800
    private val myContext: Context
    private lateinit var skyImage: Bitmap
    private var effects: List<Effect> = emptyList()
    private var effectPaint: Int? = null
    private var tz = TimeZone.getDefault()
    private var date: Calendar
    private lateinit var solar: Solar

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
        var recolorImg: Bitmap


        Weather?.let {
            if (it.dt * 1000 >= solar.today) {
                if (it.sys.sunrise*1000 != solar.dawn.set || it.sys.sunset*1000 != solar.dusk.set) {
                    updateSolar(it.sys.sunrise*1000, it.sys.sunset*1000, date.clone() as Calendar)
                }
                if (weatherCode != it.weather[0].id) {
                    weatherCode = it.weather[0].id
                    setEffects(starImg.width,starImg.height)
                }
            }
        }

        when(weatherCode) {
            in 200..232, in 502..511, in 602..611 -> {
                seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_stormy)
                recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_stormy)
            }
            in 300..321, 500, 501, in 520..531, in 600..601, in 612..622, in 701..781, 804 -> {
                seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_overcast)
                recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_overcast)
            }
            else -> when (date.get(Calendar.MONTH)) {
                in 0..1 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                }
                in 2..4 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_spring)
                }
                in 5..7 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_summer)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_summer)
                }
                in 8..10 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_fall)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_fall)
                }
                11 -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_winter)
                }
                else -> {
                    seasonImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
                    recolorImg = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds_spring)
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

        seasonImg = Bitmap.createBitmap(seasonImg, 0, time,skyWidth, skyWidth)
        val result = Bitmap.createBitmap(starImg.width, starImg.height, starImg.config)
        val canvas = Canvas(result)
        canvas.drawBitmap(starImg, 0f, 0f, null)
        canvas.drawBitmap(seasonImg, 0f, 0f, null)
        skyImage = result
        effectPaint = recolorImg.getPixel(0,time)
        return result
    }

    fun printTime(time: Long): String{
        var cal: Calendar = Calendar.getInstance()
        cal.timeInMillis = time
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(cal.time)
    }

    private fun getTime(currentTime: Long, start: Long, end: Long, max: Int, min: Int, reversed: Boolean = false): Int{
        val time: Int
        val totalDiff: Float = (end - start).toFloat() //get time the shift should encompass
        val diff: Float = (currentTime - start).toFloat() //get current time in comparison to total
        val rate: Float = max.toFloat()/totalDiff //divide number of pixels to travel over total time in shift
        val progress: Int = (rate*diff).roundToInt() //multiply current time by rate to get current pixel height of shift
        if (reversed){
            time = when{
                min+progress > max -> max
                else -> min+progress
            }
        }
        else {
            time = when{
                max-progress < min -> min
                else -> max-progress
            }
        }
        return time

    }

    private fun setEffects(maxWidth: Int, maxHeight: Int) {
        val windSpeed: Float = Weather?.wind?.speed ?: 3f
        effects = when (weatherCode) {
            in 200..232, in 502..511, in 602..611 -> listOf(
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy3), 0, windSpeed*1/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy2), 0, windSpeed*2/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stormy1), 0, windSpeed,maxWidth,maxHeight )
                )
            in 300..321, 500, 501, in 520..531, in 600..601, in 612..622, in 701..781, 804 -> listOf(
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast3), 0, windSpeed*1/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast2), 0, windSpeed*2/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.overcast1), 0, windSpeed,maxWidth,maxHeight )
            )
            801 -> listOf(
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1), 0, windSpeed,maxWidth,maxHeight )
            )
            802 -> listOf(
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds2), 0, windSpeed*1/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1), 0, windSpeed*2/3,maxWidth,maxHeight )
            )
            803 -> listOf(
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds3), 0, windSpeed*1/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds2), 0, windSpeed*2/3,maxWidth,maxHeight ),
                Effect(BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.clouds1), 0, windSpeed,maxWidth,maxHeight )
            )
            else -> emptyList()
        }
    }

    fun getEffects(): Bitmap?{
        var frame: Bitmap?
        if (effects.size >= 2){
            frame = addEffects(0,1,effects.size)
        }
        else{
            if (effects.isNotEmpty()){
                frame = effects[0].getframe()
            }
            else{
                frame = null
            }
        }

        return frame
    }

    fun getEffectPaint(): Paint? {
        val color = Paint()
        effectPaint?.let{
            color.colorFilter=PorterDuffColorFilter(it,PorterDuff.Mode.SRC_IN)
        }
        return color
    }

    private fun addEffects(effect1: Int, effect2: Int, max: Int): Bitmap{
        val frame: Bitmap
        if (effect2+1 < max){
            frame = addEffects(effect1+1,effect2+1,max)
        }
        else{
            frame = effects[effect2].getframe()
        }
        return effects[effect1]+frame
    }

    inner class Effect(image: Bitmap, moveDirection: Int, moveRate: Float, maxw: Int, maxh: Int){
        private var x: Float = 0f
        private var y: Float = 0f
        private val srcImage: Bitmap
        private val direction: Int
        private val rate: Float
        private val maxWidth: Int
        private val maxHeight: Int

        init{
            srcImage = image
            direction = moveDirection
            rate = moveRate
            maxWidth = maxw
            maxHeight = maxh
        }

        operator fun plus(effect: Effect): Bitmap{
            val frame1 = getframe()
            val frame2 = effect.getframe()
            val result = Bitmap.createBitmap(frame2.width, frame2.height, frame2.config)
            val canvas = Canvas(result)
            canvas.drawBitmap(frame2, 0f, 0f, null)
            canvas.drawBitmap(frame1, 0f, 0f, null)
            return result
        }

        operator fun plus(bitmap: Bitmap): Bitmap{
            val frame1 = getframe()
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            val canvas = Canvas(result)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            canvas.drawBitmap(frame1, 0f, 0f, null)
            return result
        }

        fun getframe(): Bitmap{
            var frame: Bitmap = Bitmap.createBitmap(srcImage, x.roundToInt(), y.roundToInt(),maxWidth, maxHeight)
            when(direction%2){
                0 -> {
                    if (srcImage.width - x.roundToInt() < maxWidth)
                    {
                        val frame1 = Bitmap.createBitmap(srcImage, x.roundToInt(), y.roundToInt(),srcImage.width - x.roundToInt(), maxHeight)
                        val frame2 = Bitmap.createBitmap(srcImage, 0, y.roundToInt(),maxWidth - (srcImage.width-x.roundToInt()), maxHeight)
                        val result = Bitmap.createBitmap(maxWidth,maxHeight,srcImage.config)
                        var canvas = Canvas(result)
                        canvas.drawBitmap(frame1,0f,0f,null)
                        canvas.drawBitmap(frame2,round(x),0f,null)
                        frame = result
                    }
                }
                else -> {
                    if (srcImage.height - y.roundToInt() < maxHeight)
                    {
                        val frame1 = Bitmap.createBitmap(srcImage, x.roundToInt(), y.roundToInt(),maxWidth, srcImage.height - y.roundToInt())
                        val frame2 = Bitmap.createBitmap(srcImage, x.roundToInt(), 0,maxWidth,maxHeight - (srcImage.height - y.roundToInt()))
                        val result = Bitmap.createBitmap(maxWidth,maxHeight,srcImage.config)
                        var canvas = Canvas(result)
                        canvas.drawBitmap(frame1,0f,0f,null)
                        canvas.drawBitmap(frame2,0f,round(y),null)
                        frame = result
                    }
                }
            }
            when(direction){
                0 -> x = (x+rate)%maxWidth
                1 -> y = (y+rate)%maxHeight
                2 -> x = (x-rate)%maxWidth
                3 -> y = (y-rate)%maxHeight
                else -> x = (x+rate)%maxWidth
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
        duskDate.add(Calendar.MILLISECOND,getDST_OFFSET())
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

    private fun getDST_OFFSET(): Int{
        val tempCal: Calendar = Calendar.getInstance(tz)
        return when(tz.inDaylightTime(tempCal.time)){
            true -> tempCal.get(Calendar.DST_OFFSET)
            false -> 0
        }
    }

    fun setDate(){
        date = Calendar.getInstance(tz)
    }

    fun setLocation(location: Location){
        myLocation = location
    }

    fun setWeather(){
        doAsync{
            val weather_url: String = URL_Builder.getWeather(myLocation!!.latitude, myLocation!!.longitude)
            Weather = gson.fromJson(URL(weather_url).readText(), WeatherData::class.java)
        }
    }

    fun setForecast(){
        doAsync {
            val forecast_url: String = URL_Builder.getForecast(myLocation!!.latitude, myLocation!!.longitude)
            Forecast = gson.fromJson(URL(forecast_url).readText(), ForecastData::class.java)
        }
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

    fun testWeather(){
        doAsync {
            val weather_url: String = URL_Builder.getWeather(28.04, -81.95)
            Weather = gson.fromJson(URL(weather_url).readText(), WeatherData::class.java)
        }
    }
}