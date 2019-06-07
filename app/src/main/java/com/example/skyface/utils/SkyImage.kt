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
    private val myContext: Context
    private lateinit var skyImage: Bitmap
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
        var weatherCode = 800
        var seasonImg: Bitmap


        Weather?.let {
            if (it.dt * 1000 >= solar.today) {
                if (it.sys.sunrise*1000 != solar.dawn.set || it.sys.sunset*1000 != solar.dusk.set) {
                    updateSolar(it.sys.sunrise*1000, it.sys.sunset*1000, date)
                }
                weatherCode = it.weather[0].id
            }
        }

        seasonImg = when(weatherCode) {
            in 200..232, in 502..511, in 602..611,804 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_stormy)
            in 300..321, 500, 501, in 520..531, in 600..601, in 612..622, in 701..781, 803 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_overcast)
            else -> when (date.get(Calendar.MONTH)) {
                in 0..1 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                in 2..4 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
                in 5..7 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_summer)
                in 8..10 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_fall)
                11 -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_winter)
                else -> BitmapFactory.decodeResource(myContext.resources, com.example.skyface.R.drawable.sky_spring)
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
        val result: Bitmap = Bitmap.createBitmap(starImg.width, starImg.height, starImg.config);
        val canvas = Canvas(result);
        canvas.drawBitmap(starImg, 0f, 0f, null);
        canvas.drawBitmap(seasonImg, 0f, 0f, null);
        skyImage = result
        return result;
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