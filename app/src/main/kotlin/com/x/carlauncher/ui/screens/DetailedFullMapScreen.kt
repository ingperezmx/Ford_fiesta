package com.x.carlauncher.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.x.carlauncher.R
import com.x.carlauncher.location.VehicleCompassManager
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.navigation.ActiveNavRoute
import com.x.carlauncher.navigation.CarRouteManager
import com.x.carlauncher.navigation.MapDownloadWorker
import com.x.carlauncher.navigation.OfflineMapManager
import com.x.carlauncher.voice.CarVoiceAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Destination Preset data for quick navigation selection.
 */
data class NavDestination(
    val name: String,
    val category: String,
    val geoPoint: GeoPoint
)

/**
 * Full-Screen Detailed Navigation Screen ("MAPA FULL") for X CarOS.
 * Replaces legacy app drawer module with a native, high-precision automotive GPS map screen.
 * - Top Bar: Car icon + Ford Fiesta (strictly NO "autosense"), Time, Date, LTE/BT/WiFi/Battery/Temp status.
 * - Top Search Bar: "Busca puntos de interes" with instant destination presets.
 * - Left Floating Controls: Zoom In, 3D Perspective Tilt, Zoom Out, Recenter GPS.
 * - Right Floating Controls: Compass North/Course-Up, Vertical Zoom Slider, Settings, Traffic OTA Updates.
 * - Bottom HUD: ETA Badge (13.50 min), "Navegar a su Destino" Action Button.
 * - Native OpenStreetMap with Cyan Route Polyline and miniature blue Ford Fiesta marker.
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
    val currentInstruction by CarRouteManager.currentInstruction.collectAsState()

    // Screen & Navigation States
    var isCourseUp by remember { mutableStateOf(true) }
    var is3DTilt by remember { mutableStateOf(false) }
    var zoomLevel by remember { mutableDoubleStateOf(15.5) }
    var isTrafficLayerActive by remember { mutableStateOf(true) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Preset Destinations around vehicle area (Nuevo Leon / Monterrey / San Nicolas).
    // Sin distancias ni tiempos inventados: la distancia se calcula con el
    // GPS real y el ETA solo existe cuando hay una ruta real calculada.
    val defaultDestinations = remember {
        listOf(
            NavDestination("Macroplaza Monterrey", "Centro / Turismo", GeoPoint(25.6691, -100.3099)),
            NavDestination("Parque Fundidora", "Paseo / Eventos", GeoPoint(25.6783, -100.2842)),
            NavDestination("Soriana Hiper San Nicolas", "Supermercado", GeoPoint(25.7420, -100.2980)),
            NavDestination("Gasolinera Pemex", "Combustible", GeoPoint(25.7350, -100.2850)),
            NavDestination("OXXO Las Americas", "Tienda de Conveniencia", GeoPoint(25.7310, -100.2920)),
            NavDestination("HEB Universidad", "Supermercado", GeoPoint(25.7260, -100.3120))
        )
    }

    // Distancia real (haversine) desde la posición GPS actual hasta un destino.
    // Null si todavía no hay fix de GPS: no se inventa ninguna distancia.
    fun distanceKmTo(dest: GeoPoint): Float? {
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

    // Map references
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var carMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var destMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var routePolylineRef by remember { mutableStateOf<Polyline?>(null) }

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

    // 3D Perspective Tilt Animation
    val tiltAngle by animateFloatAsState(
        targetValue = if (is3DTilt) 52f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "tilt3D"
    )
    val scaleFactor by animateFloatAsState(
        targetValue = if (is3DTilt) 2.35f else 1.0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "scale3D"
    )
    val transY by animateDpAsState(
        targetValue = if (is3DTilt) 52.dp else 0.dp,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "transY3D"
    )

    // Vehicle markers
    val vehicleFiestaIcon = remember(context) { createFiestaMarkerDrawableDetailed(context) }
    val destPinIcon = remember(context) { createDestinationPinDrawable(context) }

    // La polilínea solo muestra la ruta REAL calculada (OSRM). Sin ruta
    // no se dibuja ninguna línea inventada entre el auto y el destino.

    val mapView = remember {
        val osmdroidBase = File(context.filesDir, "osmdroid").apply { if (!exists()) mkdirs() }
        val osmdroidCache = File(context.cacheDir, "osmdroid/tiles").apply { if (!exists()) mkdirs() }

        Configuration.getInstance().userAgentValue = "PabloCarOS-Auto/1.0"
        Configuration.getInstance().osmdroidBasePath = osmdroidBase
        Configuration.getInstance().osmdroidTileCache = osmdroidCache

        MapView(context).apply {
            setTileSource(OfflineMapManager.AUTOMOTIVE_TILE_SOURCE)
            isTilesScaledToDpi = true
            setBuiltInZoomControls(false)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            controller.setZoom(zoomLevel)
            controller.setCenter(GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude))

            // Vehicle Marker: Miniature Blue Ford Fiesta
            val carMarker = Marker(this).apply {
                position = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
                icon = vehicleFiestaIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                rotation = 0f
                setInfoWindow(null)
                isDraggable = false
            }
            overlays.add(carMarker)
            carMarkerRef = carMarker

            // Destination Marker Pin
            val destMarker = Marker(this).apply {
                position = selectedDestination.geoPoint
                icon = destPinIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
                title = selectedDestination.name
            }
            overlays.add(destMarker)
            destMarkerRef = destMarker

            // Route Polyline (Glowing Neon Cyan) - solo ruta real calculada
            val polyline = Polyline(this).apply {
                outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                outlinePaint.strokeWidth = 14f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(emptyList())
            }
            overlays.add(0, polyline)
            routePolylineRef = polyline

            mapRef = this
        }
    }

    // Synchronize vehicle marker with live real GPS only
    val effectiveCarPos = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
    LaunchedEffect(effectiveCarPos.latitude, effectiveCarPos.longitude) {
        carMarkerRef?.position = effectiveCarPos
        if (isNavigating) {
            mapRef?.controller?.animateTo(effectiveCarPos)
        } else {
            mapRef?.controller?.animateTo(effectiveCarPos)
        }
        mapRef?.invalidate()
    }

    // Auto-engage 3D tilt when navigating along route
    LaunchedEffect(isNavigating) {
        if (isNavigating && !is3DTilt) {
            is3DTilt = true
        }
    }

    // Sync compass orientation (real heading from sensors / GPS)
    val effectiveHeading = compassHeading
    LaunchedEffect(effectiveHeading, isCourseUp) {
        val map = mapRef ?: return@LaunchedEffect
        val marker = carMarkerRef ?: return@LaunchedEffect
        if (isCourseUp) {
            val targetOrientation = (360f - effectiveHeading) % 360f
            map.setMapOrientation(targetOrientation, false)
            marker.rotation = 0f
        } else {
            map.setMapOrientation(0f, false)
            marker.rotation = effectiveHeading
        }
        map.invalidate()
    }

    // Calculate real road network route (OSRM) whenever destination is picked
    LaunchedEffect(selectedDestination) {
        destMarkerRef?.position = selectedDestination.geoPoint
        destMarkerRef?.title = selectedDestination.name
        val currentGeo = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
        CarRouteManager.calculateRoute(currentGeo, selectedDestination.geoPoint, selectedDestination.name)
        mapRef?.invalidate()
    }

    // Update real street polyline overlay whenever activeRoute updates
    LaunchedEffect(activeRoute) {
        val route = activeRoute ?: return@LaunchedEffect
        destMarkerRef?.position = route.destinationPoint
        destMarkerRef?.title = route.destinationName
        routePolylineRef?.setPoints(route.roadPoints)
        mapRef?.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }

    // ── ROOT CONTAINER ──────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090E1D))
    ) {
        // ── 1. Full-Bleed Map Canvas with 3D Tilt Projection ────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (tiltAngle > 0.2f) {
                            rotationX = tiltAngle
                            cameraDistance = 14f * density
                            transformOrigin = TransformOrigin(0.5f, 0.74f)
                            scaleX = scaleFactor
                            scaleY = scaleFactor
                            translationY = transY.toPx()
                        }
                    }
            )

            // Atmospheric horizon vanishing gradient (when 3D is active)
            if (tiltAngle > 2f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF0F172A).copy(alpha = (tiltAngle / 52f) * 0.98f),
                                    Color(0xFF0F172A).copy(alpha = (tiltAngle / 52f) * 0.75f),
                                    Color(0xFF1E293B).copy(alpha = (tiltAngle / 52f) * 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                        .align(Alignment.TopCenter)
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
                .clickable { showSearchOverlay = true }
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
                    overflow = TextOverflow.Ellipsis
                )
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
                    mapRef?.controller?.zoomIn()
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
                    mapRef?.controller?.zoomOut()
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
                    val currentGeo = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
                    mapRef?.controller?.animateTo(currentGeo)
                    mapRef?.controller?.setZoom(16.0)
                    zoomLevel = 16.0
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
                            mapRef?.controller?.setZoom(newZoom)
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
                        if (isTrafficLayerActive) "Trafico OTA Activado (Actualizado)" else "Trafico OTA Desactivado",
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
                // Bottom-Left ETA / Direction Badge (Matching "13.50 min" in sketch)
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
                                text = activeRoute?.etaMinutesFormatted ?: "—",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = if (activeRoute != null) "${activeRoute!!.distanceKmFormatted} · Ruta Real OSRM" else "Sin ruta · ${(distanceKmTo(selectedDestination.geoPoint)?.let { String.format("%.1f km", it) } ?: "—")}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF10B981)
                            )
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
                                    val currentGeo = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
                                    CarRouteManager.calculateRoute(currentGeo, selectedDestination.geoPoint, selectedDestination.name) { calculated ->
                                        if (calculated != null) {
                                            CarRouteManager.startNavigation(context, calculated)
                                        }
                                    }
                                }
                            } else {
                                CarRouteManager.stopNavigation()
                                Toast.makeText(context, "Navegacion finalizada.", Toast.LENGTH_SHORT).show()
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { showSearchOverlay = false },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(enabled = false) {},
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

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            items(defaultDestinations) { dest ->
                                Surface(
                                    onClick = {
                                        selectedDestination = dest
                                        searchQuery = dest.name
                                        showSearchOverlay = false
                                        try {
                                            CarVoiceAssistant.getInstance(context).speak("Destino seleccionado: ${dest.name}")
                                        } catch (_: Exception) {}
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (dest == selectedDestination) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
                                    border = BorderStroke(
                                        1.dp,
                                        if (dest == selectedDestination) Color(0xFF00E5FF) else Color(0xFF334155)
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
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                            Column {
                                                Text(
                                                    text = dest.name,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = dest.category,
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF94A3B8)
                                                )
                                            }
                                        }
                                        Text(
                                            text = distanceKmTo(dest.geoPoint)?.let { String.format("%.1f km", it) } ?: "Sin GPS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF10B981)
                                        )
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
                            Text("Trafico OTA en Tiempo Real", color = Color(0xFFCBD5E1), fontSize = 13.sp)
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

                        // Offline Map Download Section for Monterrey & Nuevo Leon
                        val isDownloadingMap by OfflineMapManager.isDownloading.collectAsState()
                        val downloadProgress by OfflineMapManager.downloadProgress.collectAsState()
                        val downloadStatus by OfflineMapManager.downloadStatus.collectAsState()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Mapas Offline Monterrey",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = downloadStatus,
                                        color = if (isDownloadingMap) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (mapRef != null) {
                                            OfflineMapManager.downloadMonterreyArea(context, mapRef!!)
                                        } else {
                                            Toast.makeText(context, "Mapa inicializandose...", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = !isDownloadingMap,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        text = if (isDownloadingMap) "$downloadProgress%" else "Descargar",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (isDownloadingMap) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0xFF0F172A)
                                )
                            }
                        }
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
private fun createFiestaMarkerDrawableDetailed(context: Context): Drawable {
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

            BitmapDrawable(context.resources, output)
        } else {
            createDefaultCircleMarker(context)
        }
    } catch (_: Exception) {
        createDefaultCircleMarker(context)
    }
}

/**
 * Creates destination pin marker drawable.
 */
private fun createDestinationPinDrawable(context: Context): Drawable {
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

    return BitmapDrawable(context.resources, bitmap)
}

/**
 * Fallback circle marker if bitmap fails to decode.
 */
private fun createDefaultCircleMarker(context: Context): Drawable {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    return BitmapDrawable(context.resources, bitmap)
}
