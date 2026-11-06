package com.x.carlauncher.illumination

import com.x.carlauncher.location.SolarCalculator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Pruebas unitarias de iluminación automotriz para XCarOS (Ford Fiesta 2016).
 *
 * Verifica con rigor matemático que los faros del carro se apaguen durante el DÍA
 * y se enciendan solo durante la NOCHE según las coordenadas de Monterrey, NL
 * (Macroplaza, lugar público).
 */
class VehicleIlluminationTest {

    private val lat = 25.6691
    private val lng = -100.3099
    private val timeZone = TimeZone.getTimeZone("America/Monterrey")

    private fun createCalendar(hour: Int, minute: Int, dayOfYear: Int = 305): Calendar {
        return Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.DAY_OF_YEAR, dayOfYear)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    @Test
    fun testHeadlightsOffDuringDaytime_Noon() {
        val calNoon = createCalendar(12, 0)
        val solar = SolarCalculator.calculate(lat, lng, calNoon) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertFalse("A las 12:00 PM (mediodía) los faros deben estar APAGADOS", solar.isNight)
    }

    @Test
    fun testHeadlightsOffDuringDaytime_Morning() {
        val calMorning = createCalendar(8, 30)
        val solar = SolarCalculator.calculate(lat, lng, calMorning) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertFalse("A las 08:30 AM (mañana) los faros deben estar APAGADOS", solar.isNight)
    }

    @Test
    fun testHeadlightsOffDuringDaytime_Afternoon() {
        val calAfternoon = createCalendar(16, 0)
        val solar = SolarCalculator.calculate(lat, lng, calAfternoon) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertFalse("A las 04:00 PM (tarde) los faros deben estar APAGADOS", solar.isNight)
    }

    @Test
    fun testHeadlightsOnDuringNight_Evening() {
        val calEvening = createCalendar(20, 0)
        val solar = SolarCalculator.calculate(lat, lng, calEvening) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertTrue("A las 08:00 PM (noche) los faros deben estar ENCENDIDOS", solar.isNight)
    }

    @Test
    fun testHeadlightsOnDuringNight_Midnight() {
        val calMidnight = createCalendar(23, 45)
        val solar = SolarCalculator.calculate(lat, lng, calMidnight) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertTrue("A las 11:45 PM (medianoche) los faros deben estar ENCENDIDOS", solar.isNight)
    }

    @Test
    fun testHeadlightsOnDuringNight_Dawn() {
        val calDawn = createCalendar(4, 30)
        val solar = SolarCalculator.calculate(lat, lng, calDawn) ?: throw AssertionError("Sin coordenadas reales no hay cálculo solar")
        assertTrue("A las 04:30 AM (madrugada) los faros deben estar ENCENDIDOS", solar.isNight)
    }
}
