package com.x.carlauncher.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.x.carlauncher.contacts.CarContact
import com.x.carlauncher.contacts.ContactsRepository
import com.x.carlauncher.ui.theme.XTheme
import com.x.carlauncher.voice.CarVoiceAssistant

/**
 * Pantalla completa de Contactos y Marcador Telefonico Automotriz para X CarOS.
 * Integra la agenda completa del dispositivo, busqueda instantanea en tiempo real,
 * favoritos, marcador numerico tactile grande, llamada directa y chat de WhatsApp.
 * Diseno de alto contraste automotriz, puro ASCII sin tildes ni caracteres especiales.
 */
@Composable
fun ExpandedContactsScreen(
    voiceAssistant: CarVoiceAssistant,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = XTheme.colors
    val focusManager = LocalFocusManager.current

    val allContacts by ContactsRepository.contacts.collectAsState()
    val isLoading by ContactsRepository.isLoading.collectAsState()
    val hasPermission by ContactsRepository.hasPermission.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Todos, 1 = Favoritos
    var dialedNumber by remember { mutableStateOf("") }

    // Inicializar o refrescar al montar
    LaunchedEffect(Unit) {
        ContactsRepository.loadContacts(context)
    }

    // Filtrado de contactos
    val filteredContacts = remember(allContacts, searchQuery, selectedTab) {
        val query = ContactsRepository.stripAccents(searchQuery).lowercase().trim()
        allContacts.filter { contact ->
            val matchesTab = if (selectedTab == 1) contact.isFavorite else true
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                val cleanName = contact.name.lowercase()
                val cleanNum = contact.number.filter { it.isDigit() }
                cleanName.contains(query) || cleanNum.contains(query)
            }
            matchesTab && matchesQuery
        }
    }

    // Coincidencia inteligente en tiempo real con el marcador
    val dialerMatchedContact = remember(dialedNumber, allContacts) {
        val cleanDial = dialedNumber.filter { it.isDigit() }
        if (cleanDial.length >= 3) {
            allContacts.firstOrNull { it.number.filter { c -> c.isDigit() }.contains(cleanDial) }
        } else null
    }

    val backgroundBrush = remember(colors) {
        Brush.verticalGradient(
            listOf(
                colors.background,
                Color(0xFF0F172A)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ════════════════════════════════════════════════════════════════════
            // 1. PANEL PRINCIPAL: AGENDA Y BUSCADOR DE CONTACTOS (~62% ancho)
            // ════════════════════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .weight(0.62f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0B1329))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {

                    // ── Barra superior: Titulo + Filtros + Boton Actualizar ───────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onBackToHome,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Volver al Inicio",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Contactos",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Contactos",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${filteredContacts.size} contactos disponibles",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        // Selector de pestañas: Todos vs Favoritos
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ContactFilterChip(
                                label = "Todos",
                                isSelected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            ContactFilterChip(
                                label = "Favoritos",
                                isSelected = selectedTab == 1,
                                onClick = { selectedTab = 1 }
                            )

                            IconButton(
                                onClick = { ContactsRepository.loadContacts(context) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Recargar contactos",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Barra de busqueda rapida automotriz ───────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )

                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(Color(0xFF38BDF8)),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = { focusManager.clearFocus() }
                                ),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Buscar por nombre o numero...",
                                            color = Color(0xFF64748B),
                                            fontSize = 12.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Limpiar busqueda",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Lista de Contactos Automotriz ─────────────────────────────
                    if (!hasPermission) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "Permiso de Contactos Requerido",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Para mostrar tus contactos en la pantalla del auto, concede el permiso.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { ContactsRepository.loadContacts(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Actualizar Permisos", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    } else if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF10B981),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else if (filteredContacts.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonSearch,
                                    contentDescription = null,
                                    tint = Color(0xFF475569),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No se encontraron contactos para '$searchQuery'"
                                    else if (selectedTab == 1) "No tienes contactos favoritos aun. Toca la estrella para agregar."
                                    else "No hay contactos sincronizados en este dispositivo.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = filteredContacts,
                                key = { "${it.id}_${it.number}" }
                            ) { contact ->
                                ContactItemCard(
                                    contact = contact,
                                    onCall = {
                                        ContactsRepository.callContact(context, contact.number)
                                    },
                                    onWhatsApp = {
                                        ContactsRepository.openWhatsApp(context, contact.number)
                                    },
                                    onToggleFavorite = {
                                        ContactsRepository.toggleFavorite(context, contact.id)
                                    },
                                    onSelectToDialer = {
                                        dialedNumber = contact.number.filter { it.isDigit() || it == '+' }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ════════════════════════════════════════════════════════════════════
            // 2. PANEL DERECHO: TECLADO Y MARCADOR DIRECTO (~38% ancho)
            // ════════════════════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0B1329))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {

                    // ── Pantalla del numero marcado + Contacto coincidente ────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = dialerMatchedContact?.name ?: if (dialedNumber.isEmpty()) "Marca un numero" else "Numero marcado",
                            fontSize = 11.sp,
                            fontWeight = if (dialerMatchedContact != null) FontWeight.Bold else FontWeight.Normal,
                            color = if (dialerMatchedContact != null) Color(0xFF38BDF8) else Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (dialedNumber.isEmpty()) "---" else dialedNumber,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (dialedNumber.isEmpty()) Color(0xFF475569) else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (dialedNumber.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        if (dialedNumber.isNotEmpty()) {
                                            dialedNumber = dialedNumber.dropLast(1)
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = "Borrar",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Matriz del teclado numerico automotriz (3x4) ──────────────
                    val keys = listOf(
                        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                        listOf("*" to "", "0" to "+", "#" to "")
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        keys.forEach { rowKeys ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowKeys.forEach { (digit, sub) ->
                                    DialKeyButton(
                                        digit = digit,
                                        letters = sub,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp),
                                        onClick = {
                                            if (dialedNumber.length < 20) {
                                                dialedNumber += digit
                                            }
                                        },
                                        onLongClick = {
                                            if (digit == "0") {
                                                dialedNumber += "+"
                                            } else if (digit == "*") {
                                                dialedNumber = ""
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Boton principal de Llamada Automotriz ───────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (dialedNumber.isNotBlank()) {
                                    ContactsRepository.callContact(context, dialedNumber)
                                } else {
                                    Toast.makeText(context, "Ingresa un numero para llamar", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "Llamar",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Llamar",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Boton de llamada por voz
                        Surface(
                            onClick = {
                                voiceAssistant.speak("A quien deseas llamar?")
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Llamar por voz",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SUBCOMPONENTES AUXILIARES DE LA PANTALLA DE CONTACTOS
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun ContactFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFF10B981) else Color(0xFF334155)),
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFF94A3B8)
            )
        }
    }
}

private val AVATAR_PALETTE = listOf(
    Color(0xFF0284C7), // Sky Blue
    Color(0xFF10B981), // Emerald Green
    Color(0xFFA855F7), // Purple Violet
    Color(0xFFF59E0B), // Amber Orange
    Color(0xFFEC4899), // Pink Rose
    Color(0xFF06B6D4)  // Cyan
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContactItemCard(
    contact: CarContact,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectToDialer: () -> Unit
) {
    val avatarBg = AVATAR_PALETTE[contact.colorIndex % AVATAR_PALETTE.size]

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelectToDialer,
                onLongClick = onCall
            ),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF131D33),
        border = BorderStroke(1.dp, if (contact.isFavorite) Color(0x66F59E0B) else Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            // Avatar + Info de Contacto
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Avatar circular con iniciales
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(avatarBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.initials,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = contact.name,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (contact.isFavorite) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Favorito",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = contact.formattedNumber,
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1
                        )
                        Text(
                            text = "• ${contact.typeLabel}",
                            fontSize = 9.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }

            // Acciones rapidas: Estrella, WhatsApp, Llamar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Boton Favorito
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (contact.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorito",
                        tint = if (contact.isFavorite) Color(0xFFF59E0B) else Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Boton WhatsApp
                IconButton(
                    onClick = onWhatsApp,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "WhatsApp",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Boton Llamada Directa
                IconButton(
                    onClick = onCall,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Llamar",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DialKeyButton(
    digit: String,
    letters: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Surface(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 16.sp
            )
            if (letters.isNotBlank()) {
                Text(
                    text = letters,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF94A3B8),
                    lineHeight = 8.sp
                )
            }
        }
    }
}
