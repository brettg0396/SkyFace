package com.example.skyface.models

import com.google.gson.annotations.SerializedName


data class ForecastData(
    @SerializedName("cod") val cod: String,
    @SerializedName("message") val message: Double,
    @SerializedName("cnt") val cnt: Int,
    @SerializedName("list") val list: List<WeatherData>
)

data class WeatherData(
    @SerializedName("coord") val coord: Coord,
    @SerializedName("weather") val weather: List<Weather>,
    @SerializedName("base") val base: String,
    @SerializedName("main") val main: Main,
    @SerializedName("wind") val wind: Wind,
    @SerializedName("snow") val snow: Snow,
    @SerializedName("rain") val rain: Rain,
    @SerializedName("clouds") val clouds: Clouds,
    @SerializedName("dt") val dt: Int,
    @SerializedName("sys") val sys: Sys,
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("cod") val cod: Int,
    @SerializedName("dt_txt") val dt_txt: String
    )

data class Coord(
    @SerializedName("lon") val lon: Double,
    @SerializedName("lat") val lat: Double
)

data class Weather(
    @SerializedName("id") val id: Int,
    @SerializedName("main") val main: String,
    @SerializedName("description") val description: String,
    @SerializedName("icon") val icon: String
)

data class Main(
    @SerializedName("temp") val temp: Float,
    @SerializedName("pressure") val pressure: Float,
    @SerializedName("humidity") val humidity: Int,
    @SerializedName("temp_min") val temp_min: Float,
    @SerializedName("temp_max") val temp_max: Float,
    @SerializedName("sea_level") val sea_level: Float,
    @SerializedName("grnd_level") val grnd_level: Float,
    @SerializedName("temp_kf") val temp_kf: Int
)

data class Wind(
    @SerializedName("speed") val speed: Float,
    @SerializedName("deg") val deg: Float
)

data class Snow(
    @SerializedName("1h") val one_h: Float,
    @SerializedName("3h") val three_h: Float
)

data class Rain(
    @SerializedName("1h") val one_h: Float,
    @SerializedName("3h") val three_h: Float
)

data class Clouds(
    @SerializedName("all") val all: Int
)

data class Sys(
    @SerializedName("message") val message: Double,
    @SerializedName("country") val country: String,
    @SerializedName("sunrise") val sunrise: Long,
    @SerializedName("sunset") val sunset: Long,
    @SerializedName("pod") val pod: String,
    @SerializedName("type") val type: Int
)