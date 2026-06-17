package be.heyman.android.etymoclan.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.heyman.android.etymoclan.ClanMember
import be.heyman.android.etymoclan.R
import be.heyman.android.etymoclan.ui.viewmodels.DashboardViewModel
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File


@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onMemberClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val members by viewModel.members.collectAsState()
    var showMediaPipe by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // En-tête
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EtymoClan",
                    color = Color(0xFFD4AF37), // Or
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Standard Zéro • Connaissance Fédérée",
                    color = Color(0xFF00FFD0), // Turquoise
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF1B5E20), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("LOCAL AI ACTIVE", color = Color(0xFF81C784), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Liste des membres / Guide de départ
        if (members.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.avatar_egg),
                    contentDescription = "Egg incubator guide",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFF8BC34A).copy(alpha = 0.3f), RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Le Clan attend son réveil",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Approchez une carte NFC (Terreau sarcomusation, Tomate...) du téléphone pour lui insuffler la vie localement.",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Membres éveillés (${members.size})",
                        color = Color.LightGray,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (showMediaPipe) "Style: MediaPipe ⚡" else "Style: MNN 🎨",
                        color = if (showMediaPipe) Color(0xFF00FFD0) else Color(0xFFD4AF37),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                            .border(0.5.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { showMediaPipe = !showMediaPipe }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            items(members) { member ->
                ClanMemberCard(member = member, showMediaPipe = showMediaPipe, onClick = { onMemberClick(member.id) })
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        }

        // Zone de simulation NFC
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161616), RoundedCornerShape(18.dp))
                .padding(12.dp)
                .border(0.5.dp, Color.DarkGray, RoundedCornerShape(18.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Console de Simulation NFC (Debug)",
                color = Color.LightGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { viewModel.simulateScanTerreau() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF795548)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                ) {
                    Text("+ Terreau", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.simulateScanTomate() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                ) {
                    Text("+ Tomate", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.simulateScanBasilic() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                ) {
                    Text("+ Basilic", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { viewModel.simulateScanMoinette(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                ) {
                    Text("+ Moinette", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ClanMemberCard(member: ClanMember, showMediaPipe: Boolean, onClick: () -> Unit) {
    val avatarRes = when (member.type) {
        "Terreau" -> R.drawable.avatar_terreau
        "Tomate" -> R.drawable.avatar_tomate
        "Basilic" -> R.drawable.avatar_basilic
        else -> R.drawable.avatar_egg
    }
    
    val accentColor = when (member.type) {
        "Terreau" -> Color(0xFF8D6E63)
        "Tomate" -> Color(0xFFEF5350)
        "Basilic" -> Color(0xFF66BB6A)
        "Téléviseur" -> Color(0xFF2196F3)
        "Tondeuse" -> Color(0xFFFF9800)
        "Brique de Lait" -> Color(0xFFE0E0E0)
        else -> Color(0xFF9C27B0)
    }

    val path = if (showMediaPipe && member.avatarPathMediaPipe.isNotEmpty()) {
        member.avatarPathMediaPipe
    } else {
        member.avatarPath
    }
    
    val localFile = if (path.isNotEmpty()) File(path) else null
    val bitmap = remember(path) {
        if (localFile != null && localFile.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(localFile.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .border(0.5.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = member.type,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(id = avatarRes),
                    contentDescription = member.type,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.type,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(accentColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = member.statusValue,
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "Origine: ${member.origin}",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                if (member.iu.isNotEmpty()) {
                    Text(
                        text = member.iu,
                        color = Color(0xFF00FFD0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "« ${member.thought} »",
                    color = Color(0xFFFFD54F),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2
                )
            }
            
            val isBusy = member.mood in listOf("Réflexion", "Génération", "Recherche", "Scraping", "Classification", "Indexation", "Appel Outil")
            val moodBg = if (isBusy) accentColor.copy(alpha = 0.2f) else Color(0xFF333333)
            val moodColor = if (isBusy) accentColor else Color(0xFF81C784)
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Box(
                    modifier = Modifier
                        .background(moodBg, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = member.mood + if (isBusy) " ⚙️" else "",
                        color = moodColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
