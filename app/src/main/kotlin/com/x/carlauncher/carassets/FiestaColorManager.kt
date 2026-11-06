package com.x.carlauncher.carassets

import android.content.Context
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Colores oficiales del catalogo Ford Fiesta (2014-2019).
 */
enum class FiestaColor(
    val id: String,
    val displayName: String,
    val paintCode: String,
    val swatchColor: Color,
    val colorFilter: ColorFilter?,
    val highwayColorFilter: ColorFilter?
) {
    BLUE_CANDY(
        id = "blue_candy",
        displayName = "Azul Candy Tintcoat",
        paintCode = "E6",
        swatchColor = Color(0xFF1E40AF),
        colorFilter = null, // Color original base
        highwayColorFilter = null
    ),
    RUBY_RED(
        id = "ruby_red",
        displayName = "Rojo Rubi Metalico",
        paintCode = "D4",
        swatchColor = Color(0xFFDC2626),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.10f, 0.05f, 1.25f, 0f, 0f,
            0.05f, 0.20f, 0.05f, 0f, 0f,
            0.05f, 0.05f, 0.20f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.10f, 0.05f, 1.25f, 0f, 0f,
            0.05f, 0.20f, 0.05f, 0f, 0f,
            0.05f, 0.05f, 0.20f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    OXFORD_WHITE(
        id = "oxford_white",
        displayName = "Blanco Oxford",
        paintCode = "YZ",
        swatchColor = Color(0xFFF8FAFC),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.38f, 0.38f, 0.38f, 0f, 95f,
            0.38f, 0.38f, 0.38f, 0f, 95f,
            0.38f, 0.38f, 0.38f, 0f, 100f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.38f, 0.38f, 0.38f, 0f, 50f,
            0.38f, 0.38f, 0.38f, 0f, 50f,
            0.38f, 0.38f, 0.38f, 0f, 55f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    SHADOW_BLACK(
        id = "shadow_black",
        displayName = "Negro Sombra / Tuxedo",
        paintCode = "G1",
        swatchColor = Color(0xFF0F172A),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.28f, 0.28f, 0.28f, 0f, -45f,
            0.28f, 0.28f, 0.28f, 0f, -45f,
            0.28f, 0.28f, 0.28f, 0f, -40f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.30f, 0.30f, 0.30f, 0f, -35f,
            0.30f, 0.30f, 0.30f, 0f, -35f,
            0.30f, 0.30f, 0.30f, 0f, -30f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    INGOT_SILVER(
        id = "ingot_silver",
        displayName = "Plata Lingote Metalico",
        paintCode = "UX",
        swatchColor = Color(0xFFCBD5E1),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.38f, 0.38f, 0.38f, 0f, 35f,
            0.38f, 0.38f, 0.38f, 0f, 35f,
            0.38f, 0.38f, 0.38f, 0f, 40f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.38f, 0.38f, 0.38f, 0f, 25f,
            0.38f, 0.38f, 0.38f, 0f, 25f,
            0.38f, 0.38f, 0.38f, 0f, 30f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    MAGNETIC_GRAY(
        id = "magnetic_gray",
        displayName = "Gris Magnetico",
        paintCode = "J7",
        swatchColor = Color(0xFF475569),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.33f, 0.33f, 0.33f, 0f, -10f,
            0.33f, 0.33f, 0.33f, 0f, -10f,
            0.33f, 0.33f, 0.33f, 0f, -5f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.33f, 0.33f, 0.33f, 0f, -15f,
            0.33f, 0.33f, 0.33f, 0f, -15f,
            0.33f, 0.33f, 0.33f, 0f, -10f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    GREEN_ENVY(
        id = "green_envy",
        displayName = "Verde Envidia Metalico",
        paintCode = "HD",
        swatchColor = Color(0xFF10B981),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.15f, 0.05f, 0.05f, 0f, 0f,
            0.10f, 0.30f, 1.10f, 0f, 5f,
            0.05f, 0.10f, 0.20f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.15f, 0.05f, 0.05f, 0f, 0f,
            0.10f, 0.30f, 1.10f, 0f, 5f,
            0.05f, 0.10f, 0.20f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        )))
    ),
    MOLTEN_ORANGE(
        id = "molten_orange",
        displayName = "Naranja Fundido Metalico",
        paintCode = "Y8",
        swatchColor = Color(0xFFF97316),
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.10f, 0.10f, 1.35f, 0f, 10f,
            0.05f, 0.15f, 0.65f, 0f, 0f,
            0.05f, 0.05f, 0.10f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))),
        highwayColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.10f, 0.10f, 1.35f, 0f, 10f,
            0.05f, 0.15f, 0.65f, 0f, 0f,
            0.05f, 0.05f, 0.10f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        )))
    );

    companion object {
        fun fromId(id: String): FiestaColor {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BLUE_CANDY
        }
    }
}

/**
 * Gestor persistente del color de carroceria del Ford Fiesta para XCarOS.
 */
object FiestaColorManager {

    private const val PREFS_NAME = "x_fiesta_color_prefs"
    private const val KEY_COLOR_ID = "selected_fiesta_color_id"

    private val _currentColor = MutableStateFlow(FiestaColor.BLUE_CANDY)
    val currentColor: StateFlow<FiestaColor> = _currentColor.asStateFlow()

    private val _bannerNotification = MutableStateFlow<String?>(null)
    val bannerNotification: StateFlow<String?> = _bannerNotification.asStateFlow()

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_COLOR_ID, FiestaColor.BLUE_CANDY.id) ?: FiestaColor.BLUE_CANDY.id
        _currentColor.value = FiestaColor.fromId(savedId)
        isInitialized = true
    }

    /**
     * Avanza al siguiente color oficial con 1 solo clic.
     */
    fun cycleNextColor(context: Context): FiestaColor {
        initialize(context)
        val allColors = FiestaColor.values()
        val currentIndex = allColors.indexOf(_currentColor.value)
        val nextIndex = (currentIndex + 1) % allColors.size
        val nextColor = allColors[nextIndex]

        _currentColor.value = nextColor

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_COLOR_ID, nextColor.id).apply()

        _bannerNotification.value = "${nextColor.displayName} (${nextColor.paintCode})"
        return nextColor
    }

    fun clearBanner() {
        _bannerNotification.value = null
    }
}
