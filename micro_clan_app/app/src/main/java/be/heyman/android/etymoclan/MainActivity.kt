package be.heyman.android.etymoclan

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import be.heyman.android.etymoclan.nfc.NfcReceiver
import be.heyman.android.etymoclan.theme.EtymoClanTheme
import be.heyman.android.etymoclan.ui.screens.DashboardScreen
import be.heyman.android.etymoclan.ui.screens.MemberDetailScreen
import be.heyman.android.etymoclan.ui.viewmodels.DashboardViewModel
import be.heyman.android.etymoclan.ui.viewmodels.ChatViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "MicroClan_MainActivity"

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFilters: Array<IntentFilter>? = null
    private var techLists: Array<Array<String>>? = null

    private lateinit var gemmaEngine: GemmaTamagotchiEngine
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var activeMemberId: String? = null

    private lateinit var nfcReceiver: NfcReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Démarrage de l'activité principale MainActivity")
        
        // Activer le dessin complet de la WebView pour la capture d'écran pleine hauteur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.WebView.enableSlowWholeDocumentDraw()
        }
        
        gemmaEngine = GemmaTamagotchiEngine(this)
        gemmaEngine.initialize()

        // Enregistrement du récepteur de debug découplé
        nfcReceiver = NfcReceiver(
            onNfcPayload = { payload ->
                lifecycleScope.launch {
                    gemmaEngine.onNfcPayloadReceived(payload)
                }
            },
            onSendMessage = { message ->
                lifecycleScope.launch {
                    val members = gemmaEngine.clanMembers.value
                    val targetId = activeMemberId ?: members.firstOrNull()?.id
                    if (targetId != null && message.isNotEmpty()) {
                        gemmaEngine.interactWithMember(targetId, message)
                    }
                }
            }
        )

        val filter = NfcReceiver.getIntentFilter()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nfcReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(nfcReceiver, filter)
        }

        // Initialisation du Text-To-Speech (Offline TTS)
        tts = TextToSpeech(this, this)

        setupNfc()
        enableEdgeToEdge()

        setContent {
            EtymoClanTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C0C0C)
                ) {
                    val modelState by gemmaEngine.modelState.collectAsState()
                    val captchaState by gemmaEngine.captchaState.collectAsState()

                    if (captchaState.isRequired && captchaState.webView != null) {
                        Dialog(
                            onDismissRequest = { captchaState.onCancel?.invoke() },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .fillMaxHeight(0.85f)
                                    .padding(16.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF1E1E1E)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = captchaState.title,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Text(
                                        text = captchaState.subtitle,
                                        fontSize = 12.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .background(Color.White, RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp))
                                    ) {
                                        androidx.compose.ui.viewinterop.AndroidView(
                                            factory = {
                                                val wv = captchaState.webView!!
                                                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                                wv.layoutParams = android.view.ViewGroup.LayoutParams(
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                                wv.isClickable = true
                                                wv.isFocusable = true
                                                wv.isFocusableInTouchMode = true
                                                wv.requestFocus()
                                                wv
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 16.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { captchaState.onCancel?.invoke() }) {
                                            Text("Annuler", color = Color.Gray)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(onClick = { captchaState.onDone?.invoke() }) {
                                            Text("Terminé", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (modelState != ModelState.Ready) {
                        ModelDownloadModal(
                            modelState = modelState,
                            onDownloadClick = {
                                gemmaEngine.downloadModel(
                                    onProgress = {},
                                    onComplete = { success, _ ->
                                        if (success) {
                                            gemmaEngine.initialize()
                                        }
                                    }
                                )
                            },
                            onSkipClick = {
                                gemmaEngine.skipToSimulation()
                            }
                        )
                    } else {
                        val clanMembers by gemmaEngine.clanMembers.collectAsState()
                        var selectedMemberId by remember { mutableStateOf<String?>(null) }
                        var isMuted by remember { mutableStateOf(false) }
                        
                        LaunchedEffect(selectedMemberId) {
                            activeMemberId = selectedMemberId
                        }
                        
                        if (selectedMemberId == null) {
                            val dashboardViewModel: DashboardViewModel = viewModel()
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onMemberClick = { memberId ->
                                    Log.d(TAG, "Navigation vers le chat du membre : $memberId")
                                    selectedMemberId = memberId
                                }
                            )
                        } else {
                            val member = clanMembers.find { it.id == selectedMemberId }
                            if (member != null) {
                                BackHandler {
                                    Log.d(TAG, "Navigation vers le tableau de bord")
                                    selectedMemberId = null
                                }
                                
                                // Gestion réactive du TextToSpeech (TTS)
                                var lastSpokenMsgId by remember { mutableStateOf("") }
                                LaunchedEffect(member.chatHistory.size) {
                                    val lastMsg = member.chatHistory.lastOrNull()
                                    if (lastMsg != null && lastMsg.sender == "ai" && !isMuted) {
                                        val msgId = "${lastMsg.timestamp}_${lastMsg.text.hashCode()}"
                                        if (lastSpokenMsgId != msgId) {
                                            lastSpokenMsgId = msgId
                                            speakOffline(lastMsg.text)
                                        }
                                    }
                                }

                                 val chatViewModel: ChatViewModel = viewModel()
                                 LaunchedEffect(member.id) {
                                     chatViewModel.selectMember(member.id)
                                 }
                                 val otherMembers by chatViewModel.otherMembers.collectAsState()

                                 MemberDetailScreen(
                                     member = member,
                                     isMuted = isMuted,
                                     onMuteToggle = {
                                         isMuted = !isMuted
                                         Log.d(TAG, "TTS Mute toggle : $isMuted")
                                         if (isMuted) stopSpeaking()
                                     },
                                     onBackClick = {
                                         selectedMemberId = null
                                         stopSpeaking()
                                     },
                                     onDeleteClick = {
                                         Log.i(TAG, "Suppression demandée pour le membre : ${member.id}")
                                         gemmaEngine.deleteMember(member.id)
                                         selectedMemberId = null
                                         stopSpeaking()
                                     },
                                     onSendMessage = { text ->
                                         lifecycleScope.launch {
                                             gemmaEngine.interactWithMember(member.id, text)
                                         }
                                     },
                                     otherMembers = otherMembers,
                                     onCrossPollinate = { otherId ->
                                         chatViewModel.pollinateWith(otherId)
                                     }
                                 )
                            } else {
                                selectedMemberId = null
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Langue Française non supportée par le moteur TTS de l'appareil.")
            } else {
                isTtsReady = true
                Log.i(TAG, "TextToSpeech offline initialisé avec succès en Français !")
            }
        } else {
            Log.e(TAG, "Échec de l'initialisation du TextToSpeech.")
        }
    }

    private fun speakOffline(text: String) {
        if (isTtsReady && tts != null) {
            Log.d(TAG, "TTS Offline prononce : '$text'")
            val cleanText = text.replace(Regex("\\[.*?\\]"), "").trim()
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "MicroClanTTS")
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
    }

    private fun setupNfc() {
        Log.i(TAG, "Configuration du module NFC Foreground Dispatch...")
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w(TAG, "NfcAdapter n'est pas disponible sur cet appareil.")
            return
        }

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try { addDataType("*/*") } catch (_: IntentFilter.MalformedMimeTypeException) {
                Log.e(TAG, "MalformedMimeTypeException lors du setup NFC")
            }
        }
        intentFilters = arrayOf(ndef, IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED), IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        techLists = arrayOf(arrayOf(Ndef::class.java.name), arrayOf(NdefFormatable::class.java.name))
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val text = NfcReceiver.parseNfcIntent(intent)
        if (text != null) {
            lifecycleScope.launch {
                gemmaEngine.onNfcPayloadReceived(text)
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(nfcReceiver)
        } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun ModelDownloadModal(
    modelState: ModelState,
    onDownloadClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0C).copy(alpha = 0.95f))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFD4AF37).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "✨ ORCHESTRATEUR DE MODÈLE IA ✨",
                color = Color(0xFFD4AF37),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            when (modelState) {
                is ModelState.Checking -> {
                    CircularProgressIndicator(color = Color(0xFFD4AF37))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Vérification de l'intégrité du modèle...", color = Color.White, fontSize = 14.sp)
                }
                is ModelState.NotFound -> {
                    Text(
                        text = "Modèle local Gemma absent",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ce compagnon utilise un modèle de langage Gemma 4B s'exécutant à 100% localement sur votre appareil. Le modèle (~2.5 Go) doit être téléchargé pour fonctionner en mode intelligent.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onDownloadClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Télécharger le modèle (~2.5 Go)", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSkipClick) {
                        Text("Ignorer (Activer mode Simulation)", color = Color.Gray)
                    }
                }
                is ModelState.Downloading -> {
                    val progress = modelState.progress
                    Text("Téléchargement en cours...", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFFD4AF37),
                        trackColor = Color(0xFF222222)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(progress * 100).toInt()}% du fichier modèle chargé",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
                is ModelState.Initializing -> {
                    CircularProgressIndicator(color = Color(0xFFD4AF37))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Initialisation et chargement de Gemma local en mémoire...",
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                is ModelState.Error -> {
                    Text(
                        text = "Échec du téléchargement",
                        color = Color.Red,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = modelState.message,
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onDownloadClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Réessayer le téléchargement", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onSkipClick) {
                        Text("Ignorer et utiliser la simulation", color = Color.Gray)
                    }
                }
                else -> {}
            }
        }
    }
}
