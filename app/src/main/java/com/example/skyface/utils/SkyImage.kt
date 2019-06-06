package com.example.skyface.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL
import com.google.gson.Gson
import android.location.Location
import com.example.skyface.models.WeatherData
import com.example.skyface.models.ForecastData
import org.jetbrains.anko.doAsync
import android.content.Context
import android.graphics.Canvas
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt




class Sky(context: Context){
    private val gson = Gson()
    private var myLocation: Location? = null
    private var Forecast: ForecastData? = null
    private var Weather: WeatherData? = null
    private var myContext: Context
    private lateinit var sky_image: Bitmap
    private var tz = TimeZone.getDefault()
    private var date: Calendar
    private lateinit var solar: Solar

    data class Solar(
        val dawn: Sun,
        val dusk: Sun,
        var today: Long,
        var tomorrow: Long
    )

    data class Sun(
        var set: Long,
        var duration: Long = 1000*60
                *30, //30 minutes in ms
        var before: Long = 1000*60
                *60*2, //3 hours in ms
        var start: Long,
        var end: Long
    )

    init{
        myContext=context
        date = Calendar.getInstance(tz)
        initSolar()
    }

    fun getSkyImage(): Bitmap{
        /**
         * Get the colors (image) for the sky depending on the season
         * (Summer has more vibrant colors, winter more muted, fall/spring in between)
         */
        val starImg: Bitmap = BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.stars)
        var seasonImg: Bitmap = when(date.get(Calendar.MONTH)) {
            in 0..1 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
            in 2..4 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
            in 5..7 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_summer)
            in 8..10 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_fall)
            11 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
            else -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
        }
        /**
         * Calculate how the tall "sky" image should be cropped depending on the time of day as dusk or dawn approaches,
         * at a rate dependent on the size of the image, and therefore the DPI of the device
         */

        val skyWidth: Int = seasonImg.width
        val duskHeight: Int = seasonImg.height - skyWidth

        Weather?.let{
            if (it.sys.sunrise != solar.dawn.set || it.sys.sunset != solar.dusk.set){
                updateSolar(it.sys.sunrise,it.sys.sunset, date)
            }
        }
        val time: Int = when(val currentTime: Long = Calendar.getInstance().timeInMillis){
            in solar.today..solar.dawn.start -> 0
            in solar.dawn.start..solar.dawn.end -> getTime(currentTime,solar.dawn.start,solar.dawn.end,duskHeight,starImg.width,reversed=true)
            in solar.dawn.end..solar.dusk.start -> duskHeight
            in solar.dusk.start..solar.dusk.end -> getTime(currentTime,solar.dusk.start,solar.dusk.end,duskHeight,starImg.width)
            in solar.dusk.end..solar.tomorrow -> 0
            else -> 0
        }

        seasonImg = Bitmap.createBitmap(seasonImg, 0, time,skyWidth, skyWidth)
        val result: Bitmap = Bitmap.createBitmap(starImg.width, starImg.height, starImg.config);
        val canvas = Canvas(result);
        canvas.drawBitmap(starImg, 0f, 0f, null);
        canvas.drawBitmap(seasonImg, 0f, 0f, null);
        return result;
    }

    private fun printTime(time: Long): String{
        var cal: Calendar = Calendar.getInstance()
        cal.timeInMillis = time
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(cal.time)
    }

    private fun getTime(currentTime: Long, start: Long, end: Long, max: Int, min: Int, reversed: Boolean = false): Int{
        val time: Int
        val totalDiff: Float = (end - start).toFloat()
        val diff: Float = (currentTime - start).toFloat()
        val rate: Float = max.toFloat()/totalDiff
        val progress: Int = (rate*diff).roundToInt()
        if (reversed){
            time = when{
                progress > max -> max
                else -> progress
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

    private fun updateSolar(sunrise: Long, sunset: Long, today: Calendar){
        today.set(Calendar.HOUR_OF_DAY,0)
        today.set(Calendar.MINUTE,0)
        today.set(Calendar.SECOND,0)
        solar.dusk.set = sunset*1000
        solar.dusk.start = sunset - solar.dusk.before
        solar.dusk.end = sunset + solar.dusk.duration
        solar.dawn.set = sunrise*1000
        solar.dawn.start = sunrise - solar.dawn.before
        solar.dawn.end = sunrise + solar.dawn.duration
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
        dusk.start-=dusk.before
        dusk.end+=dusk.duration
        dawn.start-=dawn.before
        dawn.end+=dawn.duration
        val today: Long = todayCal.timeInMillis
        todayCal.add(Calendar.DAY_OF_YEAR,1)
        val tomorrow: Long = todayCal.timeInMillis
        solar = Solar(dawn,dusk,today,tomorrow)
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
            val weather_url: String = URL_Builder.getWeather(28.0395, 28.0395)
            Weather = gson.fromJson(URL(weather_url).readText(), WeatherData::class.java)
        }
    }
}