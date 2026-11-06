package com.x.carlauncher.location

import java.util.Calendar
import kotlin.math.*

/**
 * Astronomical Solar Calculator for XCarOS (NOAA Zenith Algorithm).
 *
 * Computes exact, minute-precision astronomical sunrise and sunset times
 * based on vehicle GPS coordinates (latitude, longitude) anywhere on Earth,
 * operating 100% offline without network calls or external libraries.
 */
object SolarCalculator {

    data class SolarTimes(
        val sunriseMinutes: Int,    // Minutes from midnight local time (e.g. 360 = 06:00 AM)
        val sunsetMinutes: Int,     // Minutes from midnight local time (e.g. 1080 = 18:00 PM)
        val isNight: Boolean,       // Whether current vehicle local time is after sunset or before sunrise
        val sunriseFormatted: String, // "05:48 AM"
        val sunsetFormatted: String   // "05:52 PM"
    )

    /**
     * Calculates astronomical sunrise/sunset and night status.
     *
     * @param latitude Vehicle decimal latitude.
     * @param longitude Vehicle decimal longitude.
     * @param calendar Optional Calendar instance (defaults to current time).
     * @return SolarTimes, o null si todavía no hay un fix GPS real
     *         (coordenadas 0,0): sin ubicación real no se inventa
     *         ninguna ubicación para calcular el día/noche.
     */
    fun calculate(
        latitude: Double,
        longitude: Double,
        calendar: Calendar = Calendar.getInstance()
    ): SolarTimes? {
        if (latitude == 0.0 && longitude == 0.0) return null
        val lat = latitude
        val lng = longitude

        // Official NOAA solar zenith for sunrise and sunset: 90 50' = 90.833
        val zenith = 90.833

        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val tzOffsetHours = calendar.timeZone.getOffset(calendar.timeInMillis) / 3_600_000.0

        // 1. Calculate UTC sunrise & sunset hours
        val sunriseUt = computeSunTime(dayOfYear, lat, lng, zenith, isSunrise = true)
        val sunsetUt = computeSunTime(dayOfYear, lat, lng, zenith, isSunrise = false)

        // 2. Convert to local minutes from midnight (0..1439)
        val sunriseLocalMinutes = ((sunriseUt + tzOffsetHours + 24.0) % 24.0 * 60).roundToInt()
        val sunsetLocalMinutes = ((sunsetUt + tzOffsetHours + 24.0) % 24.0 * 60).roundToInt()

        // 3. Determine if current minute is within the nocturnal window
        val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val isNight = if (sunsetLocalMinutes > sunriseLocalMinutes) {
            currentMinutes >= sunsetLocalMinutes || currentMinutes < sunriseLocalMinutes
        } else {
            // Polar / high-latitude edge cases
            currentMinutes in sunsetLocalMinutes until sunriseLocalMinutes
        }

        return SolarTimes(
            sunriseMinutes = sunriseLocalMinutes,
            sunsetMinutes = sunsetLocalMinutes,
            isNight = isNight,
            sunriseFormatted = formatMinutes(sunriseLocalMinutes),
            sunsetFormatted = formatMinutes(sunsetLocalMinutes)
        )
    }

    private fun computeSunTime(
        dayOfYear: Int,
        lat: Double,
        lng: Double,
        zenith: Double,
        isSunrise: Boolean
    ): Double {
        val lngHour = lng / 15.0
        val t = if (isSunrise) {
            dayOfYear + ((6.0 - lngHour) / 24.0)
        } else {
            dayOfYear + ((18.0 - lngHour) / 24.0)
        }

        // Sun's mean anomaly
        val M = (0.9856 * t) - 3.289
        val mRad = Math.toRadians(M)

        // Sun's true longitude
        var L = M + (1.916 * sin(mRad)) + (0.020 * sin(2 * mRad)) + 282.634
        L = (L % 360.0 + 360.0) % 360.0
        val lRad = Math.toRadians(L)

        // Sun's right ascension
        var RA = Math.toDegrees(atan(0.91764 * tan(lRad)))
        RA = (RA % 360.0 + 360.0) % 360.0

        // Right ascension in same quadrant as L
        val lQuadrant = floor(L / 90.0) * 90.0
        val raQuadrant = floor(RA / 90.0) * 90.0
        RA += (lQuadrant - raQuadrant)
        RA /= 15.0 // Convert to hours

        // Sun's declination
        val sinDec = 0.39782 * sin(lRad)
        val cosDec = cos(asin(sinDec))

        // Local hour angle
        val latRad = Math.toRadians(lat)
        val zenRad = Math.toRadians(zenith)
        val cosH = (cos(zenRad) - (sinDec * sin(latRad))) / (cosDec * cos(latRad))

        if (cosH > 1.0) return if (isSunrise) 0.0 else 24.0   // Sun never rises
        if (cosH < -1.0) return if (isSunrise) 0.0 else 24.0  // Midnight sun

        var H = if (isSunrise) {
            360.0 - Math.toDegrees(acos(cosH))
        } else {
            Math.toDegrees(acos(cosH))
        }
        H /= 15.0 // Convert to hours

        // Local mean time of rising/setting
        val T = H + RA - (0.06571 * t) - 6.622

        // Universal Time
        var UT = T - lngHour
        UT = (UT % 24.0 + 24.0) % 24.0
        return UT
    }

    private fun formatMinutes(minutes: Int): String {
        val h24 = (minutes / 60) % 24
        val m = minutes % 60
        val isPm = h24 >= 12
        val h12 = when {
            h24 == 0 -> 12
            h24 > 12 -> h24 - 12
            else -> h24
        }
        val amPm = if (isPm) "PM" else "AM"
        return String.format("%02d:%02d %s", h12, m, amPm)
    }
}

