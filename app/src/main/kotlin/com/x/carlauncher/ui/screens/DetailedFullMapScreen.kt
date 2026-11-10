package com.x.carlauncher.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.x.carlauncher.R
import com.x.carlauncher.location.VehicleCompassManager
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.navigation.CarRouteManager
import com.x.carlauncher.navigation.PlaceResult
import com.x.carlauncher.navigation.PlaceSearch
import com.x.carlauncher.voice.CarVoiceAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Destination Preset data for quick navigation selection.
 */
data class NavDestination(
    val name: String,
    val category: String,
    val geoPoint: LatLng
)

/**
 * Fila de destino: sirve igual para un lugar guardado, una busqueda
 * reciente o un resultado de la busqueda de lugares.
 */
@Composable
private fun DestinationRow(
    dest: NavDestination,
    isSelected: Boolean,
    distanceText: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF00E5FF) else Color(0xFF334155)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = when {
                        dest.category.contains("Hogar") -> Icons.Default.Home
                        dest.category.contains("Combustible") -> Icons.Default.LocalGasStation
                        dest.category.contains("Supermercado") -> Icons.Default.ShoppingCart
                        else -> Icons.Default.Place
                    },
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dest.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dest.category,
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = distanceText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF10B981)
            )
        }
    }
}

/**
 * Full-Screen Detailed Navigation Screen ("MAPA FULL") for X CarOS.
 * Replaces legacy app drawer module with a native, high-precision automotive GPS map screen.
 * - Top Bar: Car icon + Ford Fiesta (strictly NO "autosense"), Time, Date, LTE/BT/WiFi/Battery/Temp status.
 * - Top Search Bar: "Busca puntos de interes" with instant destination presets.
 * - Left Floating Controls: Zoom In, 3D Perspective Tilt, Zoom Out, Recenter GPS.
 * - Right Floating Controls: Compass North/Course-Up, Vertical Zoom Slider, Settings, Traffic OTA Updates.
 * - Bottom HUD: ETA Badge (13.50 min), "Navegar a su Destino" Action Button.
 * - Native Google Maps with Cyan Route Polyline and miniature blue Ford Fiesta marker.
 * - La vista 3D usa la inclinacion real de la camara de Google Maps (antes
 *   era una proyeccion falsa con graphicsLayer sobre el mapa anterior).
 */
@Composable
fun DetailedFullMapScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val vehicleLoc by VehicleLocationManager.locationState.collectAsState()
    val compassHeading by VehicleCompassManager.compassHeading.collectAsState()
    val cardinalDir by VehicleCompassManager.cardinalDirection.collectAsState()

    // Unified Route & Navigation Manager States
    val activeRoute by CarRouteManager.activeRoute.collectAsState()
    val isNavigating by CarRouteManager.isNavigating.collectAsState()

    // Progreso en vivo: distancia y ETA que faltan, calculados desde la
    // posicion GPS actual (antes el HUD mostraba los totales fijos del
    // calculo inicial y nunca bajaban). Sin fix de GPS se usan los totales.
    val liveRemainingMeters = remember(vehicleLoc.latitude, vehicleLoc.longitude, activeRoute) {
        val route = activeRoute
        if (route != null && vehicleLoc.hasFix) {
            CarRouteManager.remainingRoadDistanceMeters(
                LatLng(vehicleLoc.latitude, vehicleLoc.longitude), route
            )
        } else null
    }
    val liveRemainingEtaSec = remember(vehicleLoc.latitude, vehicleLoc.longitude, activeRoute) {
        val route = activeRoute
        if (route != null && vehicleLoc.hasFix) {
            CarRouteManager.remainingEtaSeconds(
                LatLng(vehicleLoc.latitude, vehicleLoc.longitude), route
            )
        } else null
    }
    // Velocidad real del GPS (km/h), la misma que muestra el tablero.
    val liveSpeedKmh = if (vehicleLoc.hasFix) (vehicleLoc.speedMph * 1.60934f) else null

    // Screen & Navigation States
    var isCourseUp by remember { mutableStateOf(true) }
    var is3DTilt by remember { mutableStateOf(false) }
    var zoomLevel by remember { mutableDoubleStateOf(15.5) }
    var recenterCounter by remember { mutableIntStateOf(0) }
    var isTrafficLayerActive by remember { mutableStateOf(true) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Foco del campo de busqueda: al abrir la ventana el teclado se abre solo.
    val searchFocusRequester = remember { FocusRequester() }

    // ── Busqueda de lugares (Google Places API New) ─────────────────────
    // Resultados de la busqueda: null = aun no se busco (se muestran las
    // busquedas recientes).
    var searchResults by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var recentPlaces by remember { mutableStateOf(PlaceSearch.getRecent(context)) }
    val searchScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    fun performSearch() {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            searchResults = null
            searchError = null
            return
        }
        keyboardController?.hide()
        searchScope.launch {
            isSearching = true
            searchError = null
            try {
                searchResults = PlaceSearch.search(context, q)
            } catch (_: Exception) {
                searchError = "Sin conexión para buscar. Revisa el internet e inténtalo de nuevo."
                searchResults = emptyList()
            } finally {
                isSearching = false
            }
        }
    }

    // Preset Destinations around vehicle area (Nuevo Leon / Monterrey / San Nicolas).
    // Sin distancias ni tiempos inventados: la distancia se calcula con el
    // GPS real y el ETA solo existe cuando hay una ruta real calculada.
    val defaultDestinations = remember {
        listOf(
            NavDestination("Macroplaza Monterrey", "Centro / Turismo", LatLng(25.6691, -100.3099)),
            NavDestination("Parque Fundidora", "Paseo / Eventos", LatLng(25.6783, -100.2842)),
            NavDestination("Soriana Hiper San Nicolas", "Supermercado", LatLng(25.7420, -100.2980)),
            NavDestination("Gasolinera Pemex", "Combustible", LatLng(25.7350, -100.2850)),
            NavDestination("OXXO Las Americas", "Tienda de Conveniencia", LatLng(25.7310, -100.2920)),
            NavDestination("HEB Universidad", "Supermercado", LatLng(25.7260, -100.3120))
        )
    }

    // Distancia real (haversine) desde la posición GPS actual hasta un destino.
    // Null si todavía no hay fix de GPS: no se inventa ninguna distancia.
    fun distanceKmTo(dest: LatLng): Float? {
        if (!vehicleLoc.hasFix) return null
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(dest.latitude - vehicleLoc.latitude)
        val dLon = Math.toRadians(dest.longitude - vehicleLoc.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(vehicleLoc.latitude)) * Math.cos(Math.toRadians(dest.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (earthRadius * c / 1000.0).toFloat()
    }

    var selectedDestination by remember { mutableStateOf(defaultDestinations.first()) }

    // Elegir un destino (buscado o reciente): la ruta de Google se
    // calcula sola por el LaunchedEffect(selectedDestination) y el boton
    // "Navegar en Pantalla" arranca la navegacion guiada.
    fun pickPlace(dest: NavDestination, saveRecent: PlaceResult? = null) {
        if (saveRecent != null) {
            PlaceSearch.saveRecent(context, saveRecent)
            recentPlaces = PlaceSearch.getRecent(context)
        }
        selectedDestination = dest
        searchQuery = dest.name
        showSearchOverlay = false
        try {
            CarVoiceAssistant.getInstance(context).speak("Destino seleccionado: ${dest.name}")
        } catch (_: Exception) {}
    }

    // Formatted real-time clock and date
    var currentTimeStr by remember { mutableStateOf("4:30 PM") }
    var currentDateStr by remember { mutableStateOf("Martes, 12 Octubre") }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("es", "MX"))
        while (isActive) {
            val now = Date()
            currentTimeStr = timeFormat.format(now).uppercase()
            val rawDate = dateFormat.format(now)
            currentDateStr = rawDate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            delay(1000)
        }
    }

    // ── Mapa Google Maps ──────────────────────────────────────────────────
    // Posicion inicial: el GPS real si hay fix; si no, Macroplaza (el
    // destino inicial) para no arrancar en el (0,0) del oceano.
    val initialLatLng = if (vehicleLoc.hasFix) {
        LatLng(vehicleLoc.latitude, vehicleLoc.longitude)
    } else {
        LatLng(25.6691, -100.3099)
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLatLng, zoomLevel.toFloat())
    }
    val carPos = LatLng(vehicleLoc.latitude, vehicleLoc.longitude)
    val carMarkerState = remember { MarkerState(position = initialLatLng) }
    val destMarkerState = remember { MarkerState(position = selectedDestination.geoPoint) }

    // Iconos de marcador (Fiesta azul del auto y pin rojo del destino).
    val fiestaIcon: BitmapDescriptor = remember(context) {
        BitmapDescriptorFactory.fromBitmap(createFiestaMarkerBitmap(context))
    }
    val destPinIcon: BitmapDescriptor = remember(context) {
        BitmapDescriptorFactory.fromBitmap(createDestinationPinBitmap(context))
    }

    // La polilínea solo muestra la ruta REAL calculada (Google Routes API).
    // Sin ruta no se dibuja ninguna línea inventada entre el auto y el
    // destino. Mientras se avanza se recorta a lo que falta por recorrer.
    val routePoints = remember(vehicleLoc.latitude, vehicleLoc.longitude, activeRoute) {
        val route = activeRoute
        when {
            route == null -> emptyList()
            vehicleLoc.hasFix -> CarRouteManager.remainingRoadPoints(carPos, route)
            else -> route.roadPoints
        }
    }

    // La camara sigue al GPS en vivo y aplica zoom, inclinacion 3D y rumbo.
    // Sin fix de GPS no se mueve (se queda en la posicion inicial).
    val targetTilt = if (is3DTilt) 60f else 0f
    val targetBearing = if (isCourseUp) compassHeading else 0f
    LaunchedEffect(carPos, zoomLevel, targetTilt, targetBearing, recenterCounter) {
        if (!vehicleLoc.hasFix) return@LaunchedEffect
        cameraPositionState.animate(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.builder()
                    .target(carPos)
                    .zoom(zoomLevel.toFloat())
                    .bearing(targetBearing)
                    .tilt(targetTilt)
                    .build()
            ),
            500
        )
    }

    // El marcador del auto sigue al GPS en vivo.
    LaunchedEffect(carPos) {
        carMarkerState.position = carPos
    }

    // Auto-engage 3D tilt when navigating along route
    LaunchedEffect(isNavigating) {
        if (isNavigating && !is3DTilt) {
            is3DTilt = true
        }
    }

    // Calculate real road network route (Google Routes API) whenever destination is picked
    LaunchedEffect(selectedDestination) {
        destMarkerState.position = selectedDestination.geoPoint
        val currentGeo = LatLng(vehicleLoc.latitude, vehicleLoc.longitude)
        CarRouteManager.calculateRoute(context, currentGeo, selectedDestination.geoPoint, selectedDestination.name)
    }

    // ── ROOT CONTAINER ──────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090E1D))
    ) {
        // ── 1. Full-Bleed Map Canvas (Google Maps) ────────────────────────────
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = MapType.NORMAL,
                isTrafficEnabled = isTrafficLayerActive
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                compassEnabled = false,
                mapToolbarEnabled = false
            )
        ) {
            // Vehicle Marker: Miniature Blue Ford Fiesta. flat = true para
            // que gire con el mapa; en Course-Up apunta hacia arriba (0f),
            // en North-Up gira segun el rumbo del compas.
            Marker(
                state = carMarkerState,
                icon = fiestaIcon,
                anchor = Offset(0.5f, 0.5f),
                flat = true,
                rotation = if (isCourseUp) 0f else compassHeading
            )
            // Destination Marker Pin
            Marker(
                state = destMarkerState,
                title = selectedDestination.name,
                icon = destPinIcon
            )
            // Route Polyline (Glowing Neon Cyan) - solo ruta real calculada
            if (routePoints.size >= 2) {
                Polyline(
                    points = routePoints,
                    color = Color(0xFF00E5FF),
                    width = 14f,
                    jointType = JointType.ROUND,
                    startCap = RoundCap(),
                    endCap = RoundCap()
                )
            }
        }

        // ── 2. Top Integrated Status Bar (Matching Sketch - NO "autosense") ──────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.90f))
                .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Car Icon + Ford Fiesta (Strictly NO "autosense")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "Ford Fiesta",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Ford Fiesta",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Navegacion Detallada",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                // Center: Time & Date (Matching Sketch)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentTimeStr,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Text(
                        text = currentDateStr,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Right: Status Icons (LTE, BT, Vol, WiFi, Bat, Weather) + Exit Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 4G LTE
                    Text(
                        text = "4G LTE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF10B981)
                    )
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(15.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Volumen",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(15.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "WiFi",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(15.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Bateria",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (vehicleLoc.hasFix) "${vehicleLoc.satellites} sat · ${vehicleLoc.accuracyMeters.toInt()} m" else "Sin GPS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFBBF24)
                    )

                    // Exit / Back Button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar Mapa Full",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ── 3. Top Search Bar: "Busca puntos de interes" (Matching Sketch) ───────
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 66.dp)
                .width(280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.90f))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                .clickable {
                    // Al abrir, limpiar la busqueda anterior para que se
                    // vean los lugares guardados y las busquedas recientes.
                    searchResults = null
                    searchError = null
                    showSearchOverlay = true
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Buscar",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (searchQuery.isNotBlank()) searchQuery else "Busca puntos de interes",
                    fontSize = 11.sp,
                    color = if (searchQuery.isNotBlank()) Color.White else Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (activeRoute != null) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Borrar ruta",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                CarRouteManager.clearRoute()
                                searchQuery = ""
                            }
                    )
                }
            }
        }

        // ── 4. Left Floating Action Dock (Zoom In, 3D, Zoom Out, Recenter) ──────
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0F172A).copy(alpha = 0.90f))
                .border(1.dp, Color(0xFF334155).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Zoom In (+)
            IconButton(
                onClick = {
                    zoomLevel = (zoomLevel + 1.0).coerceAtMost(19.0)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Acercar Mapa",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Toggle 3D Perspective Tilt
            IconButton(
                onClick = { is3DTilt = !is3DTilt },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (is3DTilt) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Perspectiva 3D",
                    tint = if (is3DTilt) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Zoom Out (-)
            IconButton(
                onClick = {
                    zoomLevel = (zoomLevel - 1.0).coerceAtLeast(10.0)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Alejar Mapa",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Divider(color = Color(0xFF334155).copy(alpha = 0.5f), modifier = Modifier.width(22.dp))

            // Recenter GPS Button
            IconButton(
                onClick = {
                    zoomLevel = 16.0
                    recenterCounter++
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recentrar GPS",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ── 5. Right Floating Controls: Compass, Vertical Zoom Slider, Settings, Traffic ─
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compass (Top-Right of sketch)
            Surface(
                onClick = { isCourseUp = !isCourseUp },
                shape = CircleShape,
                color = Color(0xFF0F172A).copy(alpha = 0.90f),
                border = BorderStroke(1.dp, if (isCourseUp) Color(0xFF00E5FF) else Color(0xFF334155)),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "Orientacion Brujula",
                        tint = if (isCourseUp) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(if (isCourseUp) 0f else (360f - compassHeading) % 360f)
                    )
                }
            }

            // Vertical Zoom Slider Track (Matching vertical slider in sketch)
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.90f))
                    .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, _ ->
                            val fraction = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            val newZoom = 10.0 + fraction * 9.0
                            zoomLevel = newZoom
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                val thumbFraction = ((zoomLevel - 10.0) / 9.0).toFloat().coerceIn(0f, 1f)
                // Slider vertical filled bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(thumbFraction)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF0284C7))
                            ),
                            RoundedCornerShape(16.dp)
                        )
                )
                // Slider Thumb Knob
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = (thumbFraction * 105).dp)
                        .height(20.dp)
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                )
            }

            // Map Settings Gear Button
            Surface(
                onClick = { showSettingsDialog = true },
                shape = CircleShape,
                color = Color(0xFF0F172A).copy(alpha = 0.90f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Ajustes de Mapa",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Traffic OTA Updates / Route Recalculate Button (Matching sketch)
            Surface(
                onClick = {
                    isTrafficLayerActive = !isTrafficLayerActive
                    Toast.makeText(
                        context,
                        if (isTrafficLayerActive) "Trafico en el mapa Activado" else "Trafico en el mapa Desactivado",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                shape = CircleShape,
                color = if (isTrafficLayerActive) Color(0xFF10B981).copy(alpha = 0.22f) else Color(0xFF0F172A).copy(alpha = 0.90f),
                border = BorderStroke(1.dp, if (isTrafficLayerActive) Color(0xFF10B981) else Color(0xFF334155)),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Traffic,
                        contentDescription = "Actualizaciones de Trafico OTA",
                        tint = if (isTrafficLayerActive) Color(0xFF10B981) else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── 6. Bottom HUD: ETA Badge & Action Button (Matching Sketch) ───────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bottom-Left: Velocidad en vivo + ETA / Distancia restante
                // (Matching "13.50 min" in sketch). La velocidad es la del
                // GPS real (km/h); la distancia y el ETA son lo que FALTA,
                // calculados desde la posicion actual, no los totales fijos.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Velocidad en vivo (GPS real, km/h)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = liveSpeedKmh?.let { "${it.toInt()}" } ?: "—",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = "km/h",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }

                    // ETA / Distancia restante
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = liveRemainingEtaSec?.let { String.format("%.1f min", (it / 60.0).coerceAtLeast(1.0)) }
                                        ?: activeRoute?.etaMinutesFormatted ?: "—",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Text(
                                    text = if (activeRoute != null) {
                                        val km = liveRemainingMeters?.let { it / 1000.0 }
                                            ?: (activeRoute!!.totalDistanceMeters / 1000.0)
                                        String.format("%.1f km", km) + " · Ruta Google Maps"
                                    } else "Sin ruta · ${(distanceKmTo(selectedDestination.geoPoint)?.let { String.format("%.1f km", it) } ?: "—")}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }
                }

                // Bottom Actions: Google Maps Navigation Button + In-App Route Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Google Maps Button (Real Google Maps Routing)
                    Surface(
                        onClick = {
                            CarRouteManager.openInGoogleMaps(context, selectedDestination.geoPoint, selectedDestination.name)
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.5.dp, Color(0xFF34A853)),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = "Google Maps",
                                tint = Color(0xFF34A853),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Google Maps",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Native Map Navigation Button
                    Surface(
                        onClick = {
                            if (!isNavigating) {
                                val route = activeRoute
                                if (route != null) {
                                    CarRouteManager.startNavigation(context, route)
                                } else {
                                    val currentGeo = LatLng(vehicleLoc.latitude, vehicleLoc.longitude)
                                    CarRouteManager.calculateRoute(context, currentGeo, selectedDestination.geoPoint, selectedDestination.name) { calculated ->
                                        if (calculated != null) {
                                            CarRouteManager.startNavigation(context, calculated)
                                        }
                                    }
                                }
                            } else {
                                CarRouteManager.stopNavigation()
                                searchQuery = ""
                                Toast.makeText(context, "Navegación finalizada y ruta eliminada.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isNavigating) Color(0xFF10B981) else Color(0xFF0284C7),
                        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isNavigating) Icons.Default.CheckCircle else Icons.Default.NearMe,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isNavigating) "En Ruta" else "Navegar en Pantalla",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Invisible spacer for balance
                Spacer(modifier = Modifier.width(40.dp))
            }
        }

        // ── 7. Search Overlay Modal: Quick Destination Selection ─────────────────
        if (showSearchOverlay) {
            // Ventana de dialogo propia (no un Box sobre el mapa): asi el
            // campo de texto recibe el foco y el teclado se abre aunque la
            // pantalla del auto no lo muestre al tocar el campo.
            Dialog(
                onDismissRequest = { showSearchOverlay = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                val dialogKeyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    delay(200)
                    searchFocusRequester.requestFocus()
                    dialogKeyboardController?.show()
                }
                Card(
                    modifier = Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(20.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Seleccionar Destino",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            IconButton(onClick = { showSearchOverlay = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // ── Buscar cualquier lugar (Google Places) ──
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                if (it.isBlank()) {
                                    searchResults = null
                                    searchError = null
                                }
                            },
                            placeholder = {
                                Text(
                                    text = "Busca un lugar: tienda, dirección, ciudad...",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                            trailingIcon = {
                                IconButton(onClick = { performSearch() }) {
                                    Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color(0xFF00E5FF))
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00E5FF),
                                unfocusedBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFF00E5FF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { performSearch() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF0F172A))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Buscar", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            when {
                                isSearching -> {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF00E5FF),
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Text(text = "Buscando...", fontSize = 13.sp, color = Color.White)
                                        }
                                    }
                                }
                                searchError != null -> {
                                    item {
                                        Text(
                                            text = searchError ?: "",
                                            fontSize = 12.sp,
                                            color = Color(0xFFFF8A95),
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                                searchResults != null -> {
                                    val results = searchResults ?: emptyList()
                                    if (results.isEmpty()) {
                                        item {
                                            Text(
                                                text = "Sin resultados. Intenta con otro nombre o una dirección.",
                                                fontSize = 12.sp,
                                                color = Color(0xFF94A3B8),
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    } else {
                                        items(results) { place ->
                                            val dest = place.toDestination()
                                            DestinationRow(
                                                dest = dest,
                                                isSelected = dest == selectedDestination,
                                                distanceText = distanceKmTo(place.geoPoint)?.let { String.format("%.1f km", it) } ?: "Sin GPS",
                                                onClick = { pickPlace(dest, saveRecent = place) }
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    // Solo los lugares que el usuario busco
                                    // (Recientes). Los lugares fijos anteriores
                                    // ya no se muestran.
                                    if (recentPlaces.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = "Recientes",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF94A3B8),
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        items(recentPlaces) { place ->
                                            val dest = place.toDestination()
                                            DestinationRow(
                                                dest = dest,
                                                isSelected = dest == selectedDestination,
                                                distanceText = distanceKmTo(place.geoPoint)?.let { String.format("%.1f km", it) } ?: "Sin GPS",
                                                onClick = { pickPlace(dest, saveRecent = place) }
                                            )
                                        }
                                    } else {
                                        item {
                                            Text(
                                                text = "Escribe arriba el lugar que buscas y toca Buscar. Los lugares que elijas aparecerán aquí en Recientes.",
                                                fontSize = 12.sp,
                                                color = Color(0xFF94A3B8),
                                                modifier = Modifier.padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 8. Settings Dialog: Map Preferences ─────────────────────────────────
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = Color(0xFF0F172A),
                title = {
                    Text("Ajustes de Mapa", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Trafico en Tiempo Real", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            Switch(
                                checked = isTrafficLayerActive,
                                onCheckedChange = { isTrafficLayerActive = it }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Perspectiva 3D Automatica", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            Switch(
                                checked = is3DTilt,
                                onCheckedChange = { is3DTilt = it }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Orientacion Course-Up (Brujula)", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            Switch(
                                checked = isCourseUp,
                                onCheckedChange = { isCourseUp = it }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Invertir Brujula 180 Grados", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            var isInvertedState by remember { mutableStateOf(VehicleCompassManager.isHeadingInverted) }
                            Switch(
                                checked = isInvertedState,
                                onCheckedChange = {
                                    val newVal = VehicleCompassManager.toggleHeadingInversion()
                                    isInvertedState = newVal
                                }
                            )
                        }

                        // Google Maps no descarga mapas offline dentro de la
                        // app: las areas sin conexion se administran desde
                        // la app Google Maps (perfil -> Mapas sin conexion).
                        Text(
                            text = "Mapas sin conexión: se descargan desde la app Google Maps (perfil → Mapas sin conexión).",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("Aceptar", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

/**
 * Creates high-precision miniature blue Ford Fiesta marker with halo and shadow.
 */
private fun createFiestaMarkerBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val targetSizePx = (46 * density).toInt()

    return try {
        val origBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_nav_fiesta_marker)
        if (origBitmap != null) {
            val pad = (6 * density).toInt()
            val totalSize = targetSizePx + pad * 2
            val output = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)

            val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(90, 0, 229, 255)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawOval(
                pad.toFloat(),
                pad.toFloat() + 3f * density,
                (totalSize - pad).toFloat(),
                (totalSize - pad).toFloat() + 3f * density,
                haloPaint
            )

            val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(140, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawOval(
                pad.toFloat() + 4f * density,
                pad.toFloat() + 6f * density,
                (totalSize - pad).toFloat() - 4f * density,
                (totalSize - pad).toFloat() + 6f * density,
                shadowPaint
            )

            val scaledFiesta = Bitmap.createScaledBitmap(origBitmap, targetSizePx, targetSizePx, true)
            canvas.drawBitmap(scaledFiesta, pad.toFloat(), pad.toFloat(), null)

            output
        } else {
            createDefaultCircleMarkerBitmap()
        }
    } catch (_: Exception) {
        createDefaultCircleMarkerBitmap()
    }
}

/**
 * Creates destination pin marker bitmap.
 */
private fun createDestinationPinBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val w = (32 * density).toInt()
    val h = (40 * density).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val pinPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#EF4444") // Vibrant Red
        style = android.graphics.Paint.Style.FILL
    }
    val whitePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }

    // Pin head
    canvas.drawCircle(w / 2f, h * 0.35f, w * 0.32f, pinPaint)
    // White inner dot
    canvas.drawCircle(w / 2f, h * 0.35f, w * 0.12f, whitePaint)

    // Pin tip
    val path = android.graphics.Path().apply {
        moveTo(w * 0.25f, h * 0.45f)
        lineTo(w / 2f, h.toFloat() - 2f)
        lineTo(w * 0.75f, h * 0.45f)
        close()
    }
    canvas.drawPath(path, pinPaint)

    return bitmap
}

/**
 * Fallback circle marker if bitmap fails to decode.
 */
private fun createDefaultCircleMarkerBitmap(): Bitmap {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    return bitmap
}
