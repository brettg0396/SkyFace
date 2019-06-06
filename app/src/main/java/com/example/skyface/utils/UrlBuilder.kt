package com.example.skyface.utils

import com.example.skyface.constants.Secrets

class URL_Builder {

    companion object {
        const val BASE_URL = "https://api.openweathermap.org/"
        const val WEATHER_API_KEY = Secrets.WEATHER_API_KEY
        const val VERSION_URL = "data/2.5/"
        const val WEATHER_URL = "weather?"
        const val FORECAST_URL = "forecast?"

        //Parameters
        const val APP_ID = "appid"
        const val LATITUDE = "&lat"
        const val LONGITUDE = "&lon"

        fun getWeather(lat:Double,long:Double):String{
            return "$BASE_URL$VERSION_URL$WEATHER_URL$APP_ID=$WEATHER_API_KEY$LATITUDE=$lat$LONGITUDE=$long"
        }

        fun getForecast(lat:Double,long:Double):String{
            return "$BASE_URL$VERSION_URL$FORECAST_URL$APP_ID=$WEATHER_API_KEY$LATITUDE=$lat$LONGITUDE=$long"
        }
    }
}