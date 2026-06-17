package be.heyman.android.etymoclan.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import be.heyman.android.etymoclan.ChatMessage
import be.heyman.android.etymoclan.ClanMember
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    member: ClanMember,
    themeColor: Color,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    // STT Offline
    var isListening by remember { mutableStateOf(false) }
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasMicPermission = granted
            Log.d("MicroClan_STT", "Permission microphone accordée : $granted")
        }
    )

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("MicroClan_STT", "Début de l'écoute vocale...")
                isListening = true
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsd: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening = false
            }
            override fun onError(error: Int) {
                Log.w("MicroClan_STT", "Erreur SpeechRecognizer : $error")
                isListening = false
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    Log.i("MicroClan_STT", "Transcription offline réussie : '$spokenText'")
                    onSendMessage(spokenText)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(Unit) {
        speechRecognizer.setRecognitionListener(listener)
        onDispose {
            speechRecognizer.destroy()
        }
    }

    LaunchedEffect(member.chatHistory.size) {
        if (member.chatHistory.isNotEmpty()) {
            chatListState.animateScrollToItem(member.chatHistory.size - 1)
        }
    }

    val isExploring = member.mood in listOf("Recherche", "Scraping", "Classification", "Indexation")

    Column(modifier = modifier.fillMaxSize()) {
        // Actions rapides (Outils / Function Calling)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onSendMessage("Ajuste ma statistique principale ${member.stat1Name}.")
                },
                enabled = !isExploring,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                val label = member.stat1Name.split("/").firstOrNull()?.trim() ?: "Stat 1"
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Button(
                onClick = {
                    onSendMessage("Ajuste ma statistique secondaire ${member.stat2Name}.")
                },
                enabled = !isExploring,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                val label = member.stat2Name.split("/").firstOrNull()?.trim() ?: "Stat 2"
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Button(
                onClick = {
                    onSendMessage("Fais évoluer mon statut ${member.statusLabel}.")
                },
                enabled = !isExploring,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                Text(member.statusLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                if (!isExploring) {
                    onSendMessage("/explore")
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isExploring) Color(0xFFD4AF37) else Color(0xFF455A64)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isExploring) "🤖 Auto-Exploration en cours (${member.mood})..." else "🔍 Lancer l'Auto-Exploration de l'Agent",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Zone de discussion
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(0.5.dp, Color.DarkGray, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            if (member.chatHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucun message. Entamez la discussion !", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(member.chatHistory) { msg ->
                        ChatBubble(msg = msg, themeColor = themeColor)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Champ d'écriture
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (hasMicPermission) {
                        if (isListening) {
                            Log.d("MicroClan_STT", "Arrêt forcé de l'écoute.")
                            speechRecognizer.stopListening()
                            isListening = false
                        } else {
                            Log.d("MicroClan_STT", "Démarrage de l'écoute vocale...")
                            speechRecognizer.startListening(speechIntent)
                        }
                    } else {
                        Log.i("MicroClan_STT", "Permission microphone manquante. Lancement de la requête...")
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                enabled = !isExploring,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (isListening) Color.Red.copy(alpha = 0.2f)
                        else if (isExploring) Color(0xFF111111)
                        else Color(0xFF222222),
                        RoundedCornerShape(12.dp)
                    )
                    .border(1.dp, if (isListening) Color.Red else Color.Transparent, RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = if (isListening) "🎙️" else "🎤",
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                enabled = !isExploring,
                placeholder = {
                    Text(
                        text = if (isExploring) "Recherche en cours, veuillez patienter..." else "Parler avec ${member.type}...",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = themeColor,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledBorderColor = Color.DarkGray.copy(alpha = 0.5f),
                    disabledTextColor = Color.Gray
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        val toSend = textInput
                        textInput = ""
                        onSendMessage(toSend)
                    }
                },
                enabled = !isExploring && textInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = themeColor,
                    disabledContainerColor = themeColor.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Bold, color = if (isExploring) Color.Gray else Color.White)
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, themeColor: Color) {
    val isUser = msg.sender == "user"
    val isSystem = msg.sender == "system"
    
    val bubbleBg = when {
        isUser -> themeColor.copy(alpha = 0.2f)
        isSystem -> Color(0xFF222222)
        else -> Color(0xFF1E1E1E)
    }

    val bubbleBorder = when {
        isUser -> themeColor.copy(alpha = 0.6f)
        isSystem -> Color.DarkGray
        else -> Color.Gray.copy(alpha = 0.3f)
    }

    val textColor = when {
        isSystem -> Color.Gray
        else -> Color.White
    }

    val timeString = remember(msg.timestamp) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(msg.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(bubbleBg, RoundedCornerShape(12.dp))
                .border(0.5.dp, bubbleBorder, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            Text(
                text = msg.text,
                color = textColor,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isUser) "Moi • $timeString" else if (isSystem) "Système • $timeString" else "Gemma • $timeString",
                color = Color.Gray,
                fontSize = 8.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (isUser) TextAlign.Right else TextAlign.Left
            )
        }
    }
}
