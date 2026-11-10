package com.x.carlauncher.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.x.carlauncher.R
import com.x.carlauncher.location.VehicleCompassManager
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.navigation.CarRouteManager
import com.x.carlauncher.navigation.OfflineMapManager
import com.x.carlauncher.obd.ObdManager
import com.x.carlauncher.ui.theme.XTheme
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NavigationMapCard(
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    onMapClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val vehicleLoc by VehicleLocationManager.locationState.collectAsState()
    val obdData by ObdManager.obdData.collectAsState()
    val compassHeading by VehicleCompassManager.compassHeading.collectAsState()
    val cardinalDir by VehicleCompassManager.cardinalDirection.collectAsState()
    val activeRoute by CarRouteManager.activeRoute.collectAsState()
    val isNavigating by CarRouteManager.isNavigating.collectAsState()
    val currentInstruction by CarRouteManager.currentInstruction.collectAsState()

    var isCourseUp by remember { mutableStateOf(true) }
    var recenterCounter by remember { mutableStateOf(0) }

    val liveSpeedKmh = remember(vehicleLoc.speedMph, obdData.speedMph, obdData.isConnected) {
        if (obdData.isConnected && obdData.speedMph > 0f) {
            obdData.speedMph * 1.60934f
        } else {
            vehicleLoc.speedMph * 1.60934f
        }
    }

    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var carMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var destMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var routePolylineRef by remember { mutableStateOf<Polyline?>(null) }

    val vehicleFiestaIcon = remember(context) { createFiestaMarkerMini(context) }
    val destPinIcon = remember(context) { createDestPinMini(context) }

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
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude))

            // Polyline de ruta
            val polyline = Polyline(this).apply {
                outlinePaint.color = android.graphics.Color.parseColor("#00E5FF")
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(emptyList())
            }
            overlays.add(0, polyline)
            routePolylineRef = polyline

            // Marcador de destino
            val destMarker = Marker(this).apply {
                position = GeoPoint(0.0, 0.0)
                icon = destPinIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setInfoWindow(null)
                isEnabled = false
            }
            overlays.add(destMarker)
            destMarkerRef = destMarker

            // Auto Fiesta
            val carMarker = Marker(this).apply {
                position = GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
                icon = vehicleFiestaIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                rotation = 0f
                setInfoWindow(null)
                isDraggable = false
                isEnabled = vehicleLoc.hasFix
            }
            overlays.add(carMarker)
            carMarkerRef = carMarker

            mapRef = this
        }
    }

    val effectiveCarPos = remember(vehicleLoc.latitude, vehicleLoc.longitude) {
        GeoPoint(vehicleLoc.latitude, vehicleLoc.longitude)
    }

    LaunchedEffect(effectiveCarPos.latitude, effectiveCarPos.longitude, activeRoute) {
        carMarkerRef?.isEnabled = vehicleLoc.hasFix
        carMarkerRef?.position = effectiveCarPos
        val route = activeRoute
        if (route != null && vehicleLoc.hasFix) {
            routePolylineRef?.setPoints(CarRouteManager.remainingRoadPoints(effectiveCarPos, route))
        } else if (route != null) {
            routePolylineRef?.setPoints(route.roadPoints)
        } else {
            routePolylineRef?.setPoints(emptyList())
        }
        if (vehicleLoc.hasFix && (vehicleLoc.latitude != 0.0 || vehicleLoc.longitude != 0.0)) {
            mapRef?.controller?.animateTo(effectiveCarPos)
        }
        mapRef?.invalidate()
    }

    LaunchedEffect(activeRoute) {
        val route = activeRoute
        if (route != null) {
            destMarkerRef?.isEnabled = true
            destMarkerRef?.position = route.destinationPoint
            destMarkerRef?.title = route.destinationName
            routePolylineRef?.setPoints(route.roadPoints)
        } else {
            destMarkerRef?.isEnabled = false
            routePolylineRef?.setPoints(emptyList())
        }
        mapRef?.invalidate()
    }

    LaunchedEffect(compassHeading, isCourseUp) {
        val map = mapRef ?: return@LaunchedEffect
        val marker = carMarkerRef ?: return@LaunchedEffect
        if (isCourseUp) {
            val targetOrientation = (360f - compassHeading) % 360f
            map.setMapOrientation(targetOrientation, false)
            marker.rotation = 0f
        } else {
            map.setMapOrientation(0f, false)
            marker.rotation = compassHeading
        }
        map.invalidate()
    }

    LaunchedEffect(recenterCounter) {
        if (recenterCounter > 0 && (vehicleLoc.latitude != 0.0 || vehicleLoc.longitude != 0.0)) {
            mapRef?.controller?.animateTo(effectiveCarPos)
            mapRef?.controller?.setZoom(16.5)
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, XTheme.colors.secondary.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Mapa 100% nativo que siempre renderiza y sincroniza en vivo
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize()
            )

            // Gradiente decorativo superior
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(Brush.verticalGradient(colors = listOf(Color(0x33000000), Color.Transparent)))
                    .align(Alignment.TopCenter)
            )

            // Elementos de la interfaz superior (Pills de Estado)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                            .clickable { onToggleExpand?.invoke() ?: onMapClick() }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(Color(0xFF38BDF8), CircleShape))
                        Text(text = "Navegación GPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            val dotColor = if (vehicleLoc.hasFix) Color(0xFF10B981) else Color(0xFFFBBF24)
                            Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = if (vehicleLoc.hasFix) "GPS Conectado" else "Buscando satélites...", fontSize = 10.sp, color = dotColor)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                                .combinedClickable(onClick = { isCourseUp = !isCourseUp; recenterCounter++ })
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Explore, null, tint = if (isCourseUp) Color(0xFF00E5FF) else Color(0xFF94A3B8), modifier = Modifier.size(13.dp).rotate(if (isCourseUp) 0f else (360f - compassHeading) % 360f))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = " °", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Marcador de velocidad e instrucción de navegación inferior (HUD)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0F172A).copy(alpha = 0.88f)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "${liveSpeedKmh.toInt()}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "km/h", fontSize = 9.sp, color = Color(0xFF94A3B8))
                        }
                    }

                    if (activeRoute != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.90f),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f)),
                            modifier = Modifier.clickable { onToggleExpand?.invoke() ?: onMapClick() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isNavigating && currentInstruction.isNotBlank()) {
                                        currentInstruction
                                    } else {
                                        "${activeRoute!!.destinationName} (${activeRoute!!.etaMinutesFormatted})"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun createFiestaMarkerMini(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val targetSizePx = (36 * density).toInt()
    return try {
        val origBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_nav_fiesta_marker)
        if (origBitmap != null) {
            val pad = (4 * density).toInt()
            val totalSize = targetSizePx + pad * 2
            val output = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(output)
            val shadowPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#40000000")
                isAntiAlias = true
            }
            canvas.drawCircle(totalSize / 2f, totalSize / 2f + (2 * density), totalSize / 2.3f, shadowPaint)
            val scaled = Bitmap.createScaledBitmap(origBitmap, targetSizePx, targetSizePx, true)
            canvas.drawBitmap(scaled, pad.toFloat(), pad.toFloat(), null)
            BitmapDrawable(context.resources, output)
        } else {
            createFallbackCircleMini(context)
        }
    } catch (_: Exception) {
        createFallbackCircleMini(context)
    }
}

private fun createFallbackCircleMini(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        isAntiAlias = true
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    return BitmapDrawable(context.resources, bmp)
}

private fun createDestPinMini(context: Context): Drawable {
    val density = context.resources.displayMetrics.density
    val w = (24 * density).toInt()
    val h = (34 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f, h.toFloat())
        lineTo(w * 0.15f, h * 0.45f)
        arcTo(w * 0.1f, 0f, w * 0.9f, h * 0.7f, 180f, 180f, false)
        lineTo(w * 0.85f, h * 0.45f)
        close()
    }
    val pinPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#EF4444")
        isAntiAlias = true
    }
    canvas.drawPath(path, pinPaint)
    val dotPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }
    canvas.drawCircle(w / 2f, h * 0.35f, w * 0.2f, dotPaint)
    return BitmapDrawable(context.resources, bmp)
}