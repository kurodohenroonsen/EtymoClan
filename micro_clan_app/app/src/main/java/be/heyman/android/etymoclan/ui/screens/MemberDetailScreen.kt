package be.heyman.android.etymoclan.ui.screens

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.heyman.android.etymoclan.ClanMember
import be.heyman.android.etymoclan.R
import java.io.File

@Composable
fun MemberDetailScreen(
    member: ClanMember,
    isMuted: Boolean,
    onMuteToggle: () -> Unit,
    onBackClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    otherMembers: List<ClanMember> = emptyList(),
    onCrossPollinate: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {

    val avatarRes = when (member.type) {
        "Terreau" -> R.drawable.avatar_terreau
        "Tomate" -> R.drawable.avatar_tomate
        "Basilic" -> R.drawable.avatar_basilic
        else -> R.drawable.avatar_egg
    }
    
    val themeColor = when (member.type) {
        "Terreau" -> Color(0xFF8D6E63)
        "Tomate" -> Color(0xFFEF5350)
        "Basilic" -> Color(0xFF66BB6A)
        "Téléviseur" -> Color(0xFF2196F3)
        "Tondeuse" -> Color(0xFFFF9800)
        "Brique de Lait" -> Color(0xFFE0E0E0)
        else -> Color(0xFF9C27B0)
    }
    var showMediaPipeStyle by remember { mutableStateOf(false) }

    val localFileMnn = if (member.avatarPath.isNotEmpty()) File(member.avatarPath) else null
    val bitmapMnn = remember(member.avatarPath) {
        if (localFileMnn != null && localFileMnn.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(localFileMnn.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val localFileMediaPipe = if (member.avatarPathMediaPipe.isNotEmpty()) File(member.avatarPathMediaPipe) else null
    val bitmapMediaPipe = remember(member.avatarPathMediaPipe) {
        if (localFileMediaPipe != null && localFileMediaPipe.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(localFileMediaPipe.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val bitmap = if (showMediaPipeStyle) bitmapMediaPipe else bitmapMnn


    val coroutineScope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf("chat") }
    var fullscreenImageFile by remember { mutableStateOf<File?>(null) }

    // MNN Avatar Generation Simulator
    var isGeneratingImage by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableStateOf(0f) }
    var imageStyleIndex by remember { mutableStateOf(0) }

    LaunchedEffect(isGeneratingImage) {
        if (isGeneratingImage) {
            generationProgress = 0f
            while (generationProgress < 1.0f) {
                kotlinx.coroutines.delay(100)
                generationProgress += 0.05f
            }
            isGeneratingImage = false
            imageStyleIndex = (imageStyleIndex + 1) % 3
            Log.i("MicroClan_MNN", "Génération locale d'avatar MNN terminée ! Style appliqué : $imageStyleIndex")
        }
    }

    val colorFilter = remember(imageStyleIndex) {
        when (imageStyleIndex) {
            1 -> ColorFilter.colorMatrix(ColorMatrix().apply {
                setToScale(1.1f, 0.9f, 0.6f, 1.0f) // Teinte dorée / sarcomusation
            })
            2 -> ColorFilter.colorMatrix(ColorMatrix().apply {
                setToScale(0.7f, 1.2f, 0.7f, 1.0f) // Teinte verte luminescente
            })
            else -> null // Style original
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // En-tête
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("← Clan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = member.type,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onMuteToggle) {
                Text(
                    text = if (isMuted) "🔇" else "🔊",
                    fontSize = 20.sp
                )
            }

            IconButton(onClick = onDeleteClick) {
                Text(
                    text = "🗑️",
                    fontSize = 20.sp
                )
            }
            
            Box(
                modifier = Modifier
                    .background(themeColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = member.mood,
                    color = themeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Section stats Tamagotchi
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616), RoundedCornerShape(16.dp))
                .padding(12.dp)
                .border(0.5.dp, Color.DarkGray, RoundedCornerShape(16.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Tamagotchi Avatar",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .border(2.dp, themeColor, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                        colorFilter = colorFilter
                    )
                } else {
                    Image(
                        painter = painterResource(id = avatarRes),
                        contentDescription = "Tamagotchi Avatar",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                            .border(2.dp, themeColor, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                        colorFilter = colorFilter
                    )
                }
                if (isGeneratingImage) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { generationProgress },
                            color = themeColor,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${member.statusLabel} : ${member.statusValue}",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MNN",
                        color = if (!showMediaPipeStyle) themeColor else Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(if (!showMediaPipeStyle) themeColor.copy(alpha = 0.15f) else Color(0xFF222222), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                            .clickable { showMediaPipeStyle = false }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "MediaPipe",
                        color = if (showMediaPipeStyle) Color(0xFF00FFD0) else Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(if (showMediaPipeStyle) Color(0xFF00FFD0).copy(alpha = 0.15f) else Color(0xFF222222), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                            .clickable { showMediaPipeStyle = true }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                // Barre nutriments / Stat 1
                Text(
                    text = "${member.stat1Name} : ${(member.stat1Value * 100).toInt()}%",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { member.stat1Value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = themeColor,
                    trackColor = Color(0xFF333333)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Barre eau / Stat 2
                Text(
                    text = "${member.stat2Name} : ${(member.stat2Value * 100).toInt()}%",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { member.stat2Value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF4FC3F7),
                    trackColor = Color(0xFF333333)
                )
            }
        }

        val isBusy = member.mood in listOf("Réflexion", "Génération", "Recherche", "Scraping", "Classification", "Indexation", "Appel Outil")
        if (isBusy) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(themeColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .border(1.dp, themeColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = themeColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Statut : ${member.mood}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = member.thought,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TRAVAIL EN COURS ⚡",
                    color = themeColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(themeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        if (otherMembers.isNotEmpty()) {
            var showPollinateDialog by remember { mutableStateOf(false) }
            Button(
                onClick = { showPollinateDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("🌸 Pollinisation Croisée (Évolution Niv. 2)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (showPollinateDialog) {
                AlertDialog(
                    onDismissRequest = { showPollinateDialog = false },
                    title = { Text("Sélectionner un partenaire", color = Color.White) },
                    text = {
                        Column {
                            otherMembers.forEach { other ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onCrossPollinate(other.id)
                                            showPollinateDialog = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val otherAvatar = when (other.type) {
                                        "Terreau" -> R.drawable.avatar_terreau
                                        "Tomate" -> R.drawable.avatar_tomate
                                        "Basilic" -> R.drawable.avatar_basilic
                                        else -> R.drawable.avatar_egg
                                    }
                                    Image(
                                        painter = painterResource(id = otherAvatar),
                                        contentDescription = other.type,
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(other.type, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("Niveau ${other.level} • ${other.statusValue}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showPollinateDialog = false }) {
                            Text("Annuler", color = Color.Gray)
                        }
                    },
                    containerColor = Color(0xFF1E1E1E)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { activeTab = "chat" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == "chat") themeColor else Color(0xFF222222)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text("💬 Chat", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = { activeTab = "ledger" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == "ledger") themeColor else Color(0xFF222222)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text("📜 Index", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = { activeTab = "activity" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == "activity") themeColor else Color(0xFF222222)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text("📡 Activité", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = { activeTab = "comparator" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == "comparator") themeColor else Color(0xFF222222)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text("🎨 Comparatif", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (isGeneratingImage) {
            Text(
                text = "Stable Diffusion local MNN : calcul de l'avatar...",
                color = themeColor,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp, start = 8.dp),
                fontStyle = FontStyle.Italic
            )
        }

        if (member.description.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161616), RoundedCornerShape(12.dp))
                    .border(0.5.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "📋 Fiche Produit :",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = member.description,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        if (member.learnedFacts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(Color(0xFF1E1C15), RoundedCornerShape(12.dp))
                    .border(0.5.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = "🧠 Connaissances acquises :",
                    color = Color(0xFFD4AF37),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = member.learnedFacts,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (activeTab == "chat") {
            ChatScreen(
                member = member,
                themeColor = themeColor,
                onSendMessage = onSendMessage,
                modifier = Modifier.weight(1f)
            )
        } else if (activeTab == "ledger") {
            PollenLedgerScreen(
                pollens = member.pollens,
                themeColor = themeColor,
                onImageClick = { fullscreenImageFile = it },
                modifier = Modifier.weight(1f)
            )
        } else if (activeTab == "activity") {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                    .border(0.5.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📡 Flux d'activités & Outils",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    if (isBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = themeColor,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Actif",
                                color = themeColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                if (member.logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Aucun log d'activité disponible.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(member.logs) { log ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF161616), RoundedCornerShape(8.dp))
                                    .border(0.5.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val logColor = when {
                                    log.contains("Outil") || log.contains("Appel") -> Color(0xFFFFB74D) // Orange for tools
                                    log.contains("Échec") || log.contains("Erreur") -> Color(0xFFEF5350) // Red for errors
                                    log.contains("Évolution") || log.contains("Avatars") || log.contains("succès") -> Color(0xFF81C784) // Green for evolution
                                    log.contains("Recherche") || log.contains("Google") || log.contains("Visite") -> Color(0xFF4FC3F7) // Blue for search
                                    else -> Color.LightGray
                                }
                                
                                Text(
                                    text = log,
                                    color = logColor,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Display Comparison view of MNN Sana vs MediaPipe Stable Diffusion
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF161616), RoundedCornerShape(12.dp))
                    .border(0.5.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Générations Locales - Niveau ${member.level}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // MNN Card
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MNN (Sana-1.6B)", color = themeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, themeColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmapMnn != null) {
                                Image(
                                    bitmap = bitmapMnn.asImageBitmap(),
                                    contentDescription = "MNN Avatar",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = avatarRes),
                                    contentDescription = "MNN Placeholder",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Esthétique : Néon-Vectoriel", color = Color.Gray, fontSize = 10.sp)
                    }

                    // MediaPipe Card
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MediaPipe (SD v1.5)", color = Color(0xFF00FFD0), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Color(0xFF00FFD0).copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmapMediaPipe != null) {
                                Image(
                                    bitmap = bitmapMediaPipe.asImageBitmap(),
                                    contentDescription = "MediaPipe Avatar",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.avatar_egg),
                                    contentDescription = "MediaPipe Placeholder",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Esthétique : Atmosphérique", color = Color.Gray, fontSize = 10.sp)
                    }
                }
            }
        }

        fullscreenImageFile?.let { file ->
            FullscreenScreenshotDialog(
                file = file,
                onDismissRequest = { fullscreenImageFile = null }
            )
        }
    }
}
