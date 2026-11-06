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
 * Matriz de recolorado 3x3 (sin traslacion) para una pintura del Fiesta.
 *
 * Metodo: rotacion de tono que preserva la luminancia + escala de croma +
 * realce de brillo proporcional al "azul" (B - luminancia). Todo lineal, asi
 * que cabe en una ColorMatrix 4x5. La clave: un pixel GRIS (ventanas, ruedas,
 * llantas, faros, parrilla) tiene croma 0 y B == luminancia, asi que la
 * matriz lo deja EXACTAMENTE igual. Solo la carroceria azul saturada cambia
 * de color. Las matrices anteriores usaban el canal azul crudo y pintaban
 * tambien las ventanas y las ruedas del color de la carroceria.
 */
private fun recolorMatrix(
    m00: Float, m01: Float, m02: Float,
    m10: Float, m11: Float, m12: Float,
    m20: Float, m21: Float, m22: Float
): ColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    m00, m01, m02, 0f, 0f,
    m10, m11, m12, 0f, 0f,
    m20, m21, m22, 0f, 0f,
    0f,  0f,  0f,  1f, 0f
)))

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
        colorFilter = recolorMatrix(
            -0.735f, 0.100f, 1.635f,
            0.997f, -0.735f, 0.738f,
            0.100f, 0.997f, -0.097f
        ),
        highwayColorFilter = recolorMatrix(
            -0.735f, 0.100f, 1.635f,
            0.997f, -0.735f, 0.738f,
            0.100f, 0.997f, -0.097f
        )
    ),
    OXFORD_WHITE(
        id = "oxford_white",
        displayName = "Blanco Oxford",
        paintCode = "YZ",
        swatchColor = Color(0xFFF8FAFC),
        colorFilter = recolorMatrix(
            -0.875f, -0.875f, 2.750f,
            -0.875f, -0.875f, 2.750f,
            -0.875f, -0.875f, 2.750f
        ),
        highwayColorFilter = recolorMatrix(
            -0.875f, -0.875f, 2.750f,
            -0.875f, -0.875f, 2.750f,
            -0.875f, -0.875f, 2.750f
        )
    ),
    SHADOW_BLACK(
        id = "shadow_black",
        displayName = "Negro Sombra / Tuxedo",
        paintCode = "G1",
        swatchColor = Color(0xFF0F172A),
        colorFilter = recolorMatrix(
            0.594f, 0.594f, -0.189f,
            0.594f, 0.594f, -0.189f,
            0.594f, 0.594f, -0.189f
        ),
        highwayColorFilter = recolorMatrix(
            0.594f, 0.594f, -0.189f,
            0.594f, 0.594f, -0.189f,
            0.594f, 0.594f, -0.189f
        )
    ),
    INGOT_SILVER(
        id = "ingot_silver",
        displayName = "Plata Lingote Metalico",
        paintCode = "UX",
        swatchColor = Color(0xFFCBD5E1),
        colorFilter = recolorMatrix(
            -0.636f, -0.636f, 2.272f,
            -0.636f, -0.636f, 2.272f,
            -0.636f, -0.636f, 2.272f
        ),
        highwayColorFilter = recolorMatrix(
            -0.636f, -0.636f, 2.272f,
            -0.636f, -0.636f, 2.272f,
            -0.636f, -0.636f, 2.272f
        )
    ),
    MAGNETIC_GRAY(
        id = "magnetic_gray",
        displayName = "Gris Magnetico",
        paintCode = "J7",
        swatchColor = Color(0xFF475569),
        colorFilter = recolorMatrix(
            0.364f, 0.114f, 0.522f,
            0.114f, 0.364f, 0.522f,
            0.114f, 0.114f, 0.772f
        ),
        highwayColorFilter = recolorMatrix(
            0.364f, 0.114f, 0.522f,
            0.114f, 0.364f, 0.522f,
            0.114f, 0.114f, 0.772f
        )
    ),
    GREEN_ENVY(
        id = "green_envy",
        displayName = "Verde Envidia Metalico",
        paintCode = "HD",
        swatchColor = Color(0xFF10B981),
        colorFilter = recolorMatrix(
            0.674f, 0.406f, -0.080f,
            -0.942f, 0.674f, 1.268f,
            0.406f, -0.942f, 1.536f
        ),
        highwayColorFilter = recolorMatrix(
            0.674f, 0.406f, -0.080f,
            -0.942f, 0.674f, 1.268f,
            0.406f, -0.942f, 1.536f
        )
    ),
    MOLTEN_ORANGE(
        id = "molten_orange",
        displayName = "Naranja Fundido Metalico",
        paintCode = "Y8",
        swatchColor = Color(0xFFF97316),
        colorFilter = recolorMatrix(
            -1.070f, 0.320f, 1.750f,
            0.520f, -1.070f, 1.550f,
            0.320f, 0.520f, 0.160f
        ),
        highwayColorFilter = recolorMatrix(
            -1.070f, 0.320f, 1.750f,
            0.520f, -1.070f, 1.550f,
            0.320f, 0.520f, 0.160f
        )
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
