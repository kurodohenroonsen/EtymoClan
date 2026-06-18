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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.heyman.android.etymoclan.crypto.CryptoManager
import be.heyman.android.etymoclan.crypto.Pollen
import be.heyman.android.etymoclan.crypto.PollenFactory
import java.io.File
import kotlinx.coroutines.launch


@Composable
fun PollenLedgerScreen(
    pollens: List<Pollen>,
    themeColor: Color,
    onImageClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Registre décentralisé des Pollens (Standard Zéro) :",
            color = Color.LightGray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(pollens.asReversed()) { pollen ->
                PollenCard(
                    pollen = pollen,
                    themeColor = themeColor,
                    onImageClick = onImageClick
                )
            }
        }
    }
}

@Composable
fun PollenCard(pollen: Pollen, themeColor: Color, onImageClick: (File) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var verifState by remember { mutableStateOf("idle") } // "idle", "verifying", "success", "error"
    val coroutineScope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                0.5.dp,
                if (verifState == "success") Color(0xFF10B981) else Color.Gray.copy(alpha = 0.2f),
                RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(themeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = pollen.motivation.uppercase(),
                        color = themeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = if (pollen.created.length >= 19) pollen.created.substring(11, 19) + " UTC" else pollen.created,
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body
            Text(
                text = pollen.body.value,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Content ID (CID)
            Text(
                text = "CID : ${pollen.id}",
                color = Color.Gray,
                fontSize = 9.sp,
                maxLines = 1,
                fontStyle = FontStyle.Italic
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Verification Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (verifState != "verifying") {
                            verifState = "verifying"
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(800)
                                val canonicalJson = PollenFactory.getCanonicalJson(
                                    motivation = pollen.motivation,
                                    creator = pollen.creator,
                                    created = pollen.created,
                                    target = pollen.target,
                                    bodyType = pollen.body.type,
                                    bodyValue = pollen.body.value,
                                    traceInputs = pollen.trace?.inputs,
                                    tracePrompt = pollen.trace?.prompt,
                                    traceDuration = pollen.trace?.durationMs,
                                    traceModel = pollen.trace?.model
                                )
                                val success = CryptoManager.verifySignatureWithDid(
                                    did = pollen.creator,
                                    data = canonicalJson.toByteArray(Charsets.UTF_8),
                                    signatureStr = pollen.proof?.proofValue ?: ""
                                )
                                verifState = if (success) "success" else "error"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (verifState) {
                            "success" -> Color(0xFF1B5E20)
                            "error" -> Color(0xFFC62828)
                            else -> Color(0xFF2A2A2A)
                        }
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    when (verifState) {
                        "verifying" -> {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        "success" -> {
                            Text("✅ Signature Validée", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF81C784))
                        }
                        "error" -> {
                            Text("❌ Signature Invalide", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        else -> {
                            Text("🔐 Vérifier la Confiance", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                        }
                    }
                }

                Text(
                    text = if (expanded) "Masquer Détails ▲" else "Détails Techniques ▼",
                    color = themeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Text("Cible (Target IU) : ${pollen.target}", color = Color.LightGray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text("Auteur (Creator DID) : ${pollen.creator}", color = Color.LightGray, fontSize = 10.sp)

                pollen.trace?.let { trace ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Trace de Genèse (Provenance) :",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("• Modèle : ${trace.model}", color = Color.Gray, fontSize = 10.sp)
                    Text("• Durée : ${trace.durationMs} ms", color = Color.Gray, fontSize = 10.sp)
                    Text("• Prompt : \"${trace.prompt}\"", color = Color.Gray, fontSize = 10.sp)
                    if (trace.inputs.isNotEmpty()) {
                        Text("• Entrées : ${trace.inputs.joinToString(", ")}", color = Color.Gray, fontSize = 10.sp)
                        
                        // Render local screenshot thumbnails if present in inputs
                        val screenshotInputs = trace.inputs.filter { it.startsWith("file:") }
                        if (screenshotInputs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (pollen.body.type == "EvolutionAvatar") "Avatars d'Évolution (cliquer pour zoomer) :" else "Preuves Visuelles / OCR (cliquer pour zoomer) :",
                                color = Color(0xFFFFD54F),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            screenshotInputs.forEach { input ->
                                val filePath = input.substringAfter("file:")
                                val actualPath = if (filePath.startsWith("urn:cid:")) {
                                    be.heyman.android.etymoclan.GemmaTamagotchiEngine.getInstance()?.screenshotPathByCid?.get(filePath) ?: filePath
                                } else {
                                    filePath
                                }
                                val file = File(actualPath)
                                if (file.exists()) {
                                    val thumbnailBitmap = remember(file) {
                                        try {
                                            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                    if (thumbnailBitmap != null) {
                                        Image(
                                            bitmap = thumbnailBitmap.asImageBitmap(),
                                            contentDescription = "Pollen Image Thumbnail",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .padding(bottom = 8.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                                .clickable { onImageClick(file) },
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                pollen.proof?.let { proof ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Preuve d'Intégrité (W3C Data Integrity) :",
                        color = Color(0xFFFFD54F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("• Cryptosuite : ${proof.cryptosuite}", color = Color.Gray, fontSize = 10.sp)
                    Text("• Purpose : ${proof.proofPurpose}", color = Color.Gray, fontSize = 10.sp)
                    Text("• Signature : ${proof.proofValue}", color = Color.Gray, fontSize = 9.sp, maxLines = 2)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "JSON-LD Canonique (Standard Zéro) :",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val rawJsonLd = remember(pollen) {
                    val canonical = PollenFactory.getCanonicalJson(
                        motivation = pollen.motivation,
                        creator = pollen.creator,
                        created = pollen.created,
                        target = pollen.target,
                        bodyType = pollen.body.type,
                        bodyValue = pollen.body.value,
                        traceInputs = pollen.trace?.inputs,
                        tracePrompt = pollen.trace?.prompt,
                        traceDuration = pollen.trace?.durationMs,
                        traceModel = pollen.trace?.model
                    )
                    try {
                        org.json.JSONObject(canonical).toString(2)
                    } catch (e: Exception) {
                        canonical
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                        .border(0.5.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = rawJsonLd,
                        color = Color(0xFF00FFD0),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
