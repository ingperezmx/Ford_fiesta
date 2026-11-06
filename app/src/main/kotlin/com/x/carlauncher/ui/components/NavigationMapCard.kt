package com.x.carlauncher.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.location.VehicleCompassManager
import com.x.carlauncher.location.VehicleLocationManager
import com.x.carlauncher.obd.ObdManager
import com.x.carlauncher.ui.theme.XTheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng

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

    var isCourseUp by remember { mutableStateOf(true) }
    var recenterCounter by remember { mutableStateOf(0) }

    val liveSpeedKmh = remember(vehicleLoc.speedMph, obdData.speedMph, obdData.isConnected) {
        if (obdData.isConnected && obdData.speedMph > 0f) {
            obdData.speedMph * 1.60934f
        } else {
            vehicleLoc.speedMph * 1.60934f
        }
    }

    // Coordenadas de hardware reales del coche
    val currentLatLng = remember(vehicleLoc.latitude, vehicleLoc.longitude) {
        LatLng(vehicleLoc.latitude, vehicleLoc.longitude)
    }

    // Configuración de la Cámara de Google Maps
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(currentLatLng, 16f)
    }

    // Seguir la ubicación real y aplicar rumbo del compás
    LaunchedEffect(currentLatLng, compassHeading, isCourseUp) {
        val bearingRotation = if (isCourseUp) compassHeading else 0f
        cameraPositionState.animate(
            com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(
                CameraPosition.builder()
                    .target(currentLatLng)
                    .zoom(16f)
                    .bearing(bearingRotation)
                    .tilt(if (isCourseUp) 45f else 0f)
                    .build()
            ),
            1000
        )
    }

    LaunchedEffect(recenterCounter) {
        if (recenterCounter > 0) {
            cameraPositionState.animate(
                com.google.android.gms.maps.CameraUpdateFactory.newCameraPosition(
                    CameraPosition.fromLatLngZoom(currentLatLng, 16.5f)
                )
            )
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, XTheme.colors.secondary.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            
            // ─── VISOR REAL NATIVO DE GOOGLE MAPS ───
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = vehicleLoc.hasFix,
                    mapType = MapType.NORMAL
                ),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    compassEnabled = false
                )
            )

            // Gradientes decorativos
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
                        Text(text = "Google Maps", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                            Text(text = "$cardinalDir ${compassHeading.toInt()}°", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Marcador de velocidad inferior (HUD)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0F172A).copy(alpha = 0.88f)) {
                        Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "${liveSpeedKmh.toInt()}", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "km/h", fontSize = 9.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }
    }
}
