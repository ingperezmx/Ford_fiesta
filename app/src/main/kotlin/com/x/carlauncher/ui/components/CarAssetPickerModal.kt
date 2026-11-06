package com.x.carlauncher.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.x.carlauncher.R
import com.x.carlauncher.carassets.CarAssetManager
import com.x.carlauncher.carassets.CarSlot
import com.x.carlauncher.ui.theme.ColorBorder
import com.x.carlauncher.ui.theme.ColorGrayMid
import com.x.carlauncher.ui.theme.ColorPrimary
import com.x.carlauncher.ui.theme.ColorSurface
import com.x.carlauncher.ui.theme.ColorSurfaceDeep

@Composable
fun CarAssetPickerModal(
    onDismissRequest: () -> Unit,
    onAssetsUpdated: () -> Unit
) {
    val context = LocalContext.current
    var activeSlot by remember { mutableStateOf<CarSlot?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            activeSlot?.let { slot ->
                val success = CarAssetManager.saveCustomImage(context, slot, selectedUri)
                if (success) {
                    Toast.makeText(context, "${slot.title} actualizada", Toast.LENGTH_SHORT).show()
                    refreshKey++
                    onAssetsUpdated()
                } else {
                    Toast.makeText(context, "Error al guardar la imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = ColorSurfaceDeep,
            border = BorderStroke(1.dp, ColorBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // ── Header row ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(ColorPrimary.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = ColorPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Personalizar Imagenes del Vehiculo",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Configura las capas graficas para cada puerta, capo y maletero",
                                fontSize = 13.sp,
                                color = ColorGrayMid
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Format Guidelines Card ────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(28.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "Especificaciones y Formato Requerido",
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "• Formato: PNG con transparencia Alfa pura (32-bit RGBA, sin fondo blanco ni suelo).\n" +
                                       "• Resolucion exacta: 1024 × 605 pixeles (alineacion pixel a pixel).\n" +
                                       "• Cada puerta/capo/maletero debe contener solo la pieza abierta con el resto 100% transparente.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Slots List ────────────────────────────────────────────────
                key(refreshKey) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(CarSlot.values()) { slot ->
                            SlotItemRow(
                                slot = slot,
                                onSelectClick = {
                                    activeSlot = slot
                                    filePickerLauncher.launch("image/png")
                                },
                                onResetClick = {
                                    CarAssetManager.resetSlot(context, slot)
                                    refreshKey++
                                    onAssetsUpdated()
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Bottom Action Bar ─────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            CarAssetManager.resetAll(context)
                            refreshKey++
                            onAssetsUpdated()
                            Toast.makeText(context, "Todas las imagenes restablecidas a OEM", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Restablecer Todas a Fabrica",
                            color = Color(0xFFEF4444),
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = onDismissRequest,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
                    ) {
                        Text(text = "Listo", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotItemRow(
    slot: CarSlot,
    onSelectClick: () -> Unit,
    onResetClick: () -> Unit
) {
    val context = LocalContext.current
    val isCustom = CarAssetManager.hasCustomImage(context, slot)
    val customBitmap = CarAssetManager.getCustomBitmap(context, slot)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        border = BorderStroke(1.dp, if (isCustom) Color(0xFF10B981).copy(alpha = 0.5f) else ColorBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Thumbnail preview box
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF090D1A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (customBitmap != null) {
                        Image(
                            bitmap = customBitmap,
                            contentDescription = slot.title,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painter = painterResource(slot.defaultDrawableRes),
                            contentDescription = slot.title,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Slot info
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = slot.title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        // Status pill
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isCustom) Color(0xFF065F46) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isCustom) "Personalizada" else "Predeterminada",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCustom) Color(0xFF34D399) else Color(0xFF94A3B8)
                            )
                        }
                    }

                    Text(
                        text = slot.subtitle,
                        fontSize = 12.sp,
                        color = ColorGrayMid
                    )
                }
            }

            // Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isCustom) {
                    IconButton(
                        onClick = onResetClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Restablecer a OEM",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Button(
                    onClick = onSelectClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCustom) Color(0xFF0D9488) else Color(0xFF2563EB)
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCustom) "Cambiar" else "Seleccionar",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

