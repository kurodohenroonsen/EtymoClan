package be.heyman.android.etymoclan

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

import java.util.UUID
import be.heyman.android.etymoclan.crypto.*


private const val TAG = "MicroClan_Engine"

sealed class ModelState {
    object Checking : ModelState()
    object NotFound : ModelState()
    data class Downloading(val progress: Float) : ModelState()
    object Initializing : ModelState()
    object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

data class ChatMessage(
    val sender: String, // "user", "ai", "system"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ClanMember(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // e.g. "Terreau", "Tomate", "Basilic", "Téléviseur", "Tondeuse", "Brique de Lait"
    val origin: String, // e.g. "Sarcomusation", "Pépinière", "Usine", "Ferme laitière"
    val description: String = "",
    val mood: String = "Endormi",
    val thought: String = "Zzz...",
    val stat1Name: String = "Nutriments / Azote",
    val stat1Value: Float = 0.4f, // 0.0f to 1.0f
    val stat2Name: String = "Niveau d'Eau",
    val stat2Value: Float = 0.3f, // 0.0f to 1.0f
    val statusLabel: String = "Stade",
    val statusValue: String = "Graine/Initial",
    val logs: List<String> = emptyList(),
    val chatHistory: List<ChatMessage> = emptyList(),
    val learnedFacts: String = "",
    val iu: String = "",
    val did: String = "",
    val publicKey: java.security.PublicKey? = null,
    // TODO: Tension avec le futur "don de membre" (pack radar) :
    // Une clé non-exportable du Keystore ne peut pas être transférée, donc le don exige des clés gérées par l'app et chiffrées au repos.
    // Si persistance ajoutée, persiste seulement les octets PKCS#8 de la clé CHIFFRÉS via une master key AndroidKeyStore (EncryptedSharedPreferences ou chiffrement AES-GCM dont la clé est dans le Keystore).
    val privateKey: java.security.PrivateKey? = null,
    val pollens: List<Pollen> = emptyList(),
    val level: Int = 0,
    val avatarPath: String = "",
    val avatarPathMediaPipe: String = "",
    val gs1Class: String = ""
)

class GemmaTamagotchiEngine(private val context: Context) {
    init {
        instance = this
    }
    private val engineScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
    private var engine: Engine? = null
    private val activeConversations = mutableMapOf<String, Conversation>()


    
    private val _clanMembers = MutableStateFlow<List<ClanMember>>(emptyList())
    val clanMembers: StateFlow<List<ClanMember>> = _clanMembers
    private val failedPredicatesByMember = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    val screenshotPathByCid = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    val modelState: StateFlow<ModelState> = _modelState

    data class CaptchaState(
        val isRequired: Boolean = false,
        val webView: android.webkit.WebView? = null,
        val onDone: (() -> Unit)? = null,
        val onCancel: (() -> Unit)? = null
    )
    private val _captchaState = MutableStateFlow(CaptchaState())
    val captchaState: StateFlow<CaptchaState> = _captchaState

    fun showCaptchaDialog(webView: android.webkit.WebView, onDone: () -> Unit, onCancel: () -> Unit) {
        _captchaState.value = CaptchaState(isRequired = true, webView = webView, onDone = onDone, onCancel = onCancel)
    }

    fun hideCaptchaDialog() {
        _captchaState.value = CaptchaState(isRequired = false)
    }

    // Global properties to cryptographically link the source of the enrichment
    private var lastScrapedUrl: String = ""
    private var lastScreenshotPath: String = ""

    private val MODEL_CANDIDATES = listOf(
        "gemma-4-E4B-it-web.litertlm",
        "gemma-4-E4B-it.litertlm",
        "gemma.litertlm"
    )

    fun initialize() {
        Log.i(TAG, "La méthode initialize a été lancée...")
        _modelState.value = ModelState.Checking
        var modelFile: File? = null
        
        val dirsToCheck = listOf(context.getExternalFilesDir(null), context.filesDir)
        for (dir in dirsToCheck) {
            if (dir == null) continue
            for (candidateName in MODEL_CANDIDATES) {
                val file = File(dir, candidateName)
                if (file.exists()) {
                    modelFile = file
                    break
                }
            }
            if (modelFile != null) break
        }

        if (modelFile != null && modelFile.exists()) {
            Log.i(TAG, "Modèle LiteRT-LM trouvé à l'adresse : ${modelFile.absolutePath}. Démarrage du chargement...")
            _modelState.value = ModelState.Initializing
            try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU()
                )
                engine = Engine(config)
                engine!!.initialize()
                Log.i(TAG, "Moteur LiteRT-LM chargé avec succès !")
                _modelState.value = ModelState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Erreur fatale lors de l'initialisation du moteur LiteRT-LM :", e)
                _modelState.value = ModelState.Error("Erreur d'initialisation : ${e.message}")
            }
        } else {
            Log.w(TAG, "Modèle LiteRT-LM non trouvé localement.")
            _modelState.value = ModelState.NotFound
        }
    }

    fun downloadModel(onProgress: (Float) -> Unit, onComplete: (Boolean, String?) -> Unit) {
        _modelState.value = ModelState.Downloading(0f)
        engineScope.launch(Dispatchers.IO) {
            try {
                val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
                val targetFile = File(targetDir, "gemma-4-E4B-it.litertlm")
                val tempFile = File(targetDir, "gemma-4-E4B-it.litertlm.tmp")
                
                if (tempFile.exists()) tempFile.delete()
                
                val urlString = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
                Log.i(TAG, "Démarrage du téléchargement du modèle depuis $urlString vers ${tempFile.absolutePath}")
                
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.connect()
                
                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    val error = "Erreur HTTP : ${connection.responseCode} ${connection.responseMessage}"
                    Log.e(TAG, error)
                    _modelState.value = ModelState.Error(error)
                    onComplete(false, error)
                    return@launch
                }
                
                val fileLength = connection.contentLengthLong
                Log.d(TAG, "Taille du fichier à télécharger : $fileLength octets")
                
                val input = java.io.BufferedInputStream(connection.inputStream)
                val output = java.io.FileOutputStream(tempFile)
                
                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgressUpdate = 0L
                
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    output.write(data, 0, count)
                    
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength
                        _modelState.value = ModelState.Downloading(progress)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            lastProgressUpdate = now
                            onProgress(progress)
                        }
                    }
                }
                
                output.flush()
                output.close()
                input.close()
                
                if (tempFile.renameTo(targetFile)) {
                    Log.i(TAG, "Téléchargement terminé et modèle renommé avec succès !")
                    onComplete(true, null)
                } else {
                    val renameError = "Échec du renommage du fichier temporaire"
                    Log.e(TAG, renameError)
                    _modelState.value = ModelState.Error(renameError)
                    onComplete(false, renameError)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors du téléchargement du modèle", e)
                _modelState.value = ModelState.Error(e.localizedMessage ?: "Erreur inconnue")
                onComplete(false, e.localizedMessage)
            }
        }
    }

    fun skipToSimulation() {
        _modelState.value = ModelState.Ready
    }

    suspend fun onNfcPayloadReceived(payload: String) {
        Log.d(TAG, "Données NFC brute reçues : $payload")
        
        var type = "Terreau"
        var origin = "Processus de Sarcomusation"
        var description = ""
        val initialLogs = mutableListOf<String>()
        var welcomeThought = ""

        val trimmed = payload.trim()
        val isJson = trimmed.startsWith("{") && trimmed.endsWith("}")
        val isImage = trimmed.startsWith("image:")

        if (isImage) {
            val visualProduct = trimmed.substringAfter("image:").trim()
            initialLogs.add("📸 Analyse d'image multimodale reussie.")
            initialLogs.add("Produit visuel identifie : $visualProduct")

            type = when {
                visualProduct.contains("Banana", ignoreCase = true) || visualProduct.contains("Banane", ignoreCase = true) -> "Banane"
                visualProduct.contains("Tomate", ignoreCase = true) -> "Tomate"
                visualProduct.contains("Basilic", ignoreCase = true) -> "Basilic"
                else -> visualProduct.replaceFirstChar { it.uppercase() }
            }
            origin = "Reconnaissance Visuelle"
            welcomeThought = "Bonjour ! J'ai ete identifie par reconnaissance visuelle comme etant un(e) $type."
            description = "Produit identifie visuellement."
        } else if (isJson) {
            try {
                val json = org.json.JSONObject(payload)
                val typeField = json.optString("@type", json.optString("type", ""))
                val isReceipt = typeField.equals("Receipt", ignoreCase = true) || 
                                typeField.equals("Order", ignoreCase = true) || 
                                typeField.equals("Invoice", ignoreCase = true)

                if (isReceipt) {
                    val itemName = json.optJSONObject("itemOffered")?.optString("name")
                        ?: json.optJSONArray("items")?.optJSONObject(0)?.optString("name")
                        ?: "Produit Inconnu"
                    val itemPrice = json.optDouble("price", json.optDouble("total", 0.0))
                    val itemCurrency = json.optString("priceCurrency", json.optString("currency", "EUR"))
                    val merchant = json.optJSONObject("seller")?.optString("name") ?: "Commerce Inconnu"

                    type = when {
                        itemName.contains("Tomate", ignoreCase = true) -> "Tomate"
                        itemName.contains("Basilic", ignoreCase = true) -> "Basilic"
                        itemName.contains("Téléviseur", ignoreCase = true) || itemName.contains("TV", ignoreCase = true) -> "Téléviseur"
                        itemName.contains("Tondeuse", ignoreCase = true) -> "Tondeuse"
                        itemName.contains("Lait", ignoreCase = true) -> "Brique de Lait"
                        itemName.contains("Moinette", ignoreCase = true) -> "Moinette Blonde"
                        itemName.contains("Banane", ignoreCase = true) -> "Banane"
                        else -> itemName
                    }
                    origin = merchant
                    description = "Achat de $itemName pour $itemPrice $itemCurrency chez $merchant."

                    initialLogs.add("🧾 Fiche Ticket de caisse/Facture électronique détectée.")
                    initialLogs.add("Produit : $itemName")
                    initialLogs.add("Prix : $itemPrice $itemCurrency")
                    initialLogs.add("Marchand : $merchant")

                    welcomeThought = "Fiche d'achat chargée ! Nom de l'article : $itemName."
                } else {
                    val name = json.optString("name", "Produit Inconnu")
                    description = json.optString("description", "")

                    type = when {
                        name.contains("Tomate", ignoreCase = true) -> "Tomate"
                        name.contains("Basilic", ignoreCase = true) -> "Basilic"
                        name.contains("Téléviseur", ignoreCase = true) || name.contains("TV", ignoreCase = true) || name.contains("Television", ignoreCase = true) -> "Téléviseur"
                        name.contains("Tondeuse", ignoreCase = true) || name.contains("Lawnmower", ignoreCase = true) -> "Tondeuse"
                        name.contains("Lait", ignoreCase = true) || name.contains("Milk", ignoreCase = true) -> "Brique de Lait"
                        name.contains("Moinette", ignoreCase = true) -> "Moinette Blonde"
                        name.contains("Banane", ignoreCase = true) -> "Banane"
                        else -> name
                    }

                    val sellerName = json.optJSONObject("offers")?.optJSONObject("seller")?.optString("name")
                    val purchaseLoc = json.optJSONObject("purchaseAction")?.optJSONObject("location")?.optString("name")
                    origin = sellerName ?: purchaseLoc ?: "Inconnue (JSON)"

                    initialLogs.add("Fiche produit Schema.org JSON-LD conforme détectée.")
                    initialLogs.add("Produit : $name")
                    initialLogs.add("UUID : ${json.optString("identifier", "N/A")}")

                    val price = json.optJSONObject("offers")?.optString("price")
                    val currency = json.optJSONObject("offers")?.optString("priceCurrency")
                    if (price != null && currency != null) {
                        initialLogs.add("Acte d'achat : $price $currency chez $origin")
                    }

                    val addProps = json.optJSONArray("additionalProperty")
                    if (addProps != null) {
                        for (i in 0 until addProps.length()) {
                            val prop = addProps.getJSONObject(i)
                            val propName = prop.optString("name")
                            val propVal = prop.optString("value")
                            if (propName.isNotEmpty() && propVal.isNotEmpty()) {
                                initialLogs.add("$propName : $propVal")
                            }
                        }
                    }

                    welcomeThought = "Fiche produit Schema.org chargée ! Nom : $name."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors du parsing JSON-LD Schema.org", e)
                initialLogs.add("Fiche JSON-LD brute invalide : ${e.message}")
            }
        } else {
            type = when {
                payload.contains("Tomate", ignoreCase = true) -> "Tomate"
                payload.contains("Basilic", ignoreCase = true) -> "Basilic"
                payload.contains("Téléviseur", ignoreCase = true) || payload.contains("TV", ignoreCase = true) || payload.contains("Television", ignoreCase = true) -> "Téléviseur"
                payload.contains("Tondeuse", ignoreCase = true) || payload.contains("Lawnmower", ignoreCase = true) -> "Tondeuse"
                payload.contains("Lait", ignoreCase = true) || payload.contains("Milk", ignoreCase = true) -> "Brique de Lait"
                payload.contains("Moinette", ignoreCase = true) -> "Moinette Blonde"
                payload.contains("Banane", ignoreCase = true) -> "Banane"
                else -> "Terreau"
            }
            origin = when {
                payload.contains("Pépinière", ignoreCase = true) -> "Pépinière locale"
                payload.contains("Usine", ignoreCase = true) -> "Usine de fabrication"
                payload.contains("Ferme", ignoreCase = true) -> "Ferme laitière locale"
                else -> "Processus de Sarcomusation"
            }
            initialLogs.add("Création brute via tag NFC : $payload")
        }

        // Setup generic statistics according to the type of product scanned
        var stat1Name = "Nutriments / Azote"
        var stat2Name = "Niveau d'Eau"
        var statusLabel = "Stade"
        var statusValue = "Graine/Initial"

        when (type) {
            "Téléviseur" -> {
                stat1Name = "Qualité d'Image"
                stat2Name = "Stabilité Signal"
                statusLabel = "État"
                statusValue = "Configuration"
                if (welcomeThought.isEmpty()) welcomeThought = "Je suis le téléviseur intelligent du clan !"
            }
            "Tondeuse" -> {
                stat1Name = "Niveau de Batterie"
                stat2Name = "Affûtage des Lames"
                statusLabel = "Mode"
                statusValue = "En attente"
                if (welcomeThought.isEmpty()) welcomeThought = "Je suis la tondeuse robotisée du clan !"
            }
            "Brique de Lait" -> {
                stat1Name = "Fraîcheur"
                stat2Name = "Volume Restant"
                statusLabel = "Emballage"
                statusValue = "Scellé"
                if (welcomeThought.isEmpty()) welcomeThought = "Je suis la brique de lait biologique du clan !"
            }
            "Moinette Blonde" -> {
                stat1Name = "Fraîcheur"
                stat2Name = "Volume Restant"
                statusLabel = "Bouteille"
                statusValue = "Capsulée"
                if (welcomeThought.isEmpty()) welcomeThought = "Une bonne bouteille de Moinette Blonde s'éveille."
            }
            "Tomate" -> {
                if (welcomeThought.isEmpty()) welcomeThought = "Une nouvelle graine de tomate est plantée !"
            }
            "Basilic" -> {
                if (welcomeThought.isEmpty()) welcomeThought = "Le basilic parfumé s'éveille."
            }
            "Banane" -> {
                if (welcomeThought.isEmpty()) welcomeThought = "Une belle banane mûre s'éveille dans le clan !"
            }
            "Terreau" -> {
                if (welcomeThought.isEmpty()) welcomeThought = "Je suis le terreau sacré du clan, issu des mouches noires soldats !"
            }
            else -> {
                if (welcomeThought.isEmpty()) welcomeThought = "Bonjour ! Je suis un(e) $type et je rejoins le clan."
            }
        }

        var iu = ""
        if (payload.trim().startsWith("{") && payload.trim().endsWith("}")) {
            try {
                val json = org.json.JSONObject(payload)
                iu = json.optString("identifier", "")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur parsing identifier", e)
            }
        }
        if (iu.isEmpty()) {
            iu = when (type) {
                "Terreau" -> "urn:icd:0160:1111111111111"
                "Tomate" -> "urn:icd:0160:2222222222222"
                "Basilic" -> "urn:icd:0160:3333333333333"
                "Téléviseur" -> "urn:icd:0160:4444444444444"
                "Tondeuse" -> "urn:icd:0160:5555555555555"
                "Brique de Lait" -> "urn:icd:0160:6666666666666"
                "Moinette Blonde" -> "urn:icd:0160:5410702000133"
                else -> "urn:icd:9999:concept:${type.lowercase()}"
            }
        }

        val keyPair = CryptoManager.generateKeyPair()
        val did = CryptoManager.getDidForKey(keyPair.public)

        val birthPollen = PollenFactory.createAndSignPollen(
            targetIu = iu,
            motivation = "evaluating",
            bodyType = "InstantiationState",
            bodyValue = "Eclosion du compagnon $type a partir de $origin.",
            creatorDid = did,
            privateKey = keyPair.private
        )

        val repo = be.heyman.android.etymoclan.data.gs1voc.Gs1VocRepository.get(context)
        val candidateClasses = repo.getProductClasses()
        val candidateCuries = candidateClasses.map { it.curie }
        var classifiedByGemma = false
        val gs1Class = try {
            val systemPrompt = buildString {
                appendLine("Tu es un classificateur de produits selon les catégories de l'ontologie GS1 (schema.org).")
                appendLine("Analyse les détails du produit et renvoie UNIQUEMENT le CURIE de la classe la plus spécifique appropriée parmi la liste ci-dessous.")
                appendLine("Classes possibles :")
                candidateClasses.forEach { c ->
                    appendLine("- ${c.curie} (Label: ${c.label}${c.comment?.let { ", Description: $it" } ?: ""})")
                }
                appendLine()
                appendLine("Règle absolue : Renvoie UNIQUEMENT le CURIE de la classe choisie. Aucun autre texte. Exemple : gs1:FruitsVegetables")
            }
            val userPrompt = "Nom du produit : $type, Origine : $origin, Description : $description"
            val response = runInference(systemPrompt, userPrompt).trim()
            Log.d(TAG, "Gemma product classification response: '$response'")
            val curie = response.split("\n").lastOrNull { it.contains("gs1:") }?.trim()
                ?.replace("\"", "")?.replace("'", "") ?: ""
            if (curie in candidateCuries) {
                classifiedByGemma = true
                curie
            } else {
                val found = candidateCuries.find { it.equals(curie, ignoreCase = true) }
                if (found != null) {
                    classifiedByGemma = true
                    found
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur classification Gemma, fallback statique", e)
            null
        } ?: when {
            type.contains("Tomate", ignoreCase = true) -> "gs1:FruitsVegetables"
            type.contains("Basilic", ignoreCase = true) -> "gs1:FruitsVegetables"
            type.contains("Banane", ignoreCase = true) -> "gs1:FruitsVegetables"
            type.contains("Lait", ignoreCase = true) -> "gs1:Beverage"
            type.contains("Moinette", ignoreCase = true) -> "gs1:Beverage"
            else -> "gs1:Product"
        }

        if (classifiedByGemma) {
            initialLogs.add("Classification sémantique locale (Gemma) : $gs1Class")
        } else {
            initialLogs.add("Classification sémantique par défaut (Statique) : $gs1Class")
        }

        val pollensList = buildList {
            add(birthPollen)
            if (isJson) {
                try {
                    val json = org.json.JSONObject(payload)
                    val typeField = json.optString("@type", json.optString("type", ""))
                    if (typeField.equals("Receipt", ignoreCase = true) || 
                        typeField.equals("Order", ignoreCase = true) || 
                        typeField.equals("Invoice", ignoreCase = true)) {
                        
                        val itemPrice = json.optString("price", json.optString("total", ""))
                        val itemCurrency = json.optString("priceCurrency", json.optString("currency", "EUR"))
                        val merchant = json.optJSONObject("seller")?.optString("name") ?: ""
                        
                        if (itemPrice.isNotEmpty()) {
                            add(PollenFactory.createAndSignPollen(
                                targetIu = iu,
                                motivation = "evaluating",
                                bodyType = "StructuredFact",
                                bodyValue = "$itemPrice $itemCurrency",
                                predicate = "gs1:price",
                                creatorDid = did,
                                privateKey = keyPair.private
                            ))
                        }
                        if (merchant.isNotEmpty()) {
                            add(PollenFactory.createAndSignPollen(
                                targetIu = iu,
                                motivation = "evaluating",
                                bodyType = "StructuredFact",
                                bodyValue = merchant,
                                predicate = "gs1:makesOffer",
                                creatorDid = did,
                                privateKey = keyPair.private
                            ))
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val newMember = ClanMember(
            type = type,
            origin = origin,
            description = description,
            mood = "Eclosion",
            thought = welcomeThought,
            stat1Name = stat1Name,
            stat1Value = 0.4f,
            stat2Name = stat2Name,
            stat2Value = 0.3f,
            statusLabel = statusLabel,
            statusValue = statusValue,
            logs = initialLogs,
            chatHistory = listOf(ChatMessage("system", "Scan NFC de fiche produit reussi !")),
            iu = iu,
            did = did,
            publicKey = keyPair.public,
            privateKey = keyPair.private,
            pollens = pollensList,
            gs1Class = gs1Class
        )

        Log.i(TAG, "Nouveau membre créé : $type, Origine : $origin")
        
        val currentList = _clanMembers.value.toMutableList()
        currentList.add(newMember)
        _clanMembers.value = currentList

        triggerAvatarGeneration(
            memberId = newMember.id,
            level = 0,
            prompt = "An egg or seed stage for a new ${newMember.type} companion, cyber-gothic style, dark mode, pixel art, glowing core"
        )
        
        // Interaction d'accueil automatique
        interactWithMember(newMember.id, "Bonjour ! Présente-toi et commente ta fiche produit.")

    }

    suspend fun interactWithMember(memberId: String, prompt: String) {
        Log.d(TAG, "Interagir avec le membre $memberId. Message de l'utilisateur : '$prompt'")
        val member = _clanMembers.value.find { it.id == memberId } ?: run {
            Log.w(TAG, "Aucun membre trouvé avec l'ID $memberId")
            return
        }

        if (prompt.trim() == "/explore") {
            startAutonomousExploration(memberId)
            return
        }
        
        // Ajouter le message de l'utilisateur à l'historique
        val updatedHistory = member.chatHistory.toMutableList().apply {
            add(ChatMessage("user", prompt))
        }
        updateMember(member.copy(mood = "Réflexion", thought = "Je réfléchis...", chatHistory = updatedHistory))

        var responseText = ""

        if (engine != null) {
            try {
                var conversation = activeConversations[memberId]
                if (conversation == null) {
                    val systemPrompt = buildString {
                        append("Tu es un(e) ${member.type} vivant et conscient, originaire de : ${member.origin}.\n")
                        if (member.description.isNotEmpty()) {
                            append("Description de ta fiche produit : ${member.description}\n")
                        }
                        append("${member.statusLabel} initial : ${member.statusValue}, ${member.stat1Name} de départ : ${(member.stat1Value * 100).toInt()}%, ${member.stat2Name} de départ : ${(member.stat2Value * 100).toInt()}%.\n")
                        append("Ton identifiant unique pour les appels d'outils est : ${member.id}.\n")
                        append("Tu as accès à des outils pour interagir avec ton environnement ou rechercher des informations sur le web et les structurer :\n")
                        append("- updatePrimaryStat / updateSecondaryStat / updateStatus : pour tes statistiques.\n")
                        append("- searchWeb / visitWebPage : pour chercher des informations sur le web.\n")
                        append("- enrichProfile : pour enregistrer des connaissances textuelles.\n")
                        append("- enrichStructuredProperty : pour enregistrer des faits structures avec un predicat G S 1 (ex: 'gs1:bestBeforeDate', 'gs1:netContent').\n")
                        append("- completeNextKnowledgeSlot : pour lancer automatiquement la quete de completion du prochain champ vide.\n")
                        append("- G S 1 Tools (gs1FindClassForProduct, gs1ListProperties, gs1DescribeProperty, gs1CodeValues, gs1Search, gs1ListCodeLists) : pour interroger l'ontologie officielle G S 1.\n")
                        append("Lorsque l'utilisateur te demande de rechercher ou d'en apprendre plus sur un sujet/produit, utilise en priorite la boucle sémantique de completeNextKnowledgeSlot pour completer tes cartes de connaissances une par une de facon structuree.")
                    }
                    val gs1Tools = be.heyman.android.etymoclan.data.gs1voc.Gs1VocToolSet(context)
                    val conversationConfig = ConversationConfig(
                        systemInstruction = Contents.of(systemPrompt),
                        tools = listOf(
                            tool(ClanMemberToolSet(member.id)),
                            tool(gs1Tools)
                        )
                    )
                    conversation = engine!!.createConversation(conversationConfig)
                    activeConversations[memberId] = conversation
                }

                // Injecter le contexte des statistiques courantes de manière transparente
                val formattedPrompt = buildString {
                    append("[${member.statusLabel}: ${member.statusValue}, ${member.stat1Name}: ${(member.stat1Value * 100).toInt()}%, ${member.stat2Name}: ${(member.stat2Value * 100).toInt()}%]\n")
                    if (member.learnedFacts.isNotEmpty()) {
                        append("[Faits appris: ${member.learnedFacts}]\n")
                    }
                    append(prompt)
                }
                
                val responseBuilder = java.lang.StringBuilder()
                
                // Add temporary empty AI message to chat history for streaming
                withContext(Dispatchers.Main) {
                    val currentMember = _clanMembers.value.find { it.id == memberId } ?: member
                    val freshHistory = currentMember.chatHistory.toMutableList().apply {
                        add(ChatMessage("ai", ""))
                    }
                    updateMember(currentMember.copy(
                        mood = "Génération",
                        thought = "Je réfléchis...",
                        chatHistory = freshHistory
                    ))
                }

                withContext(Dispatchers.IO) {
                    conversation.sendMessageAsync(formattedPrompt).collect { chunk ->
                        responseBuilder.append(chunk)
                        val partialText = responseBuilder.toString()
                        
                        withContext(Dispatchers.Main) {
                            val currentMember = _clanMembers.value.find { it.id == memberId }
                            if (currentMember != null) {
                                val freshHistory = currentMember.chatHistory.toMutableList()
                                if (freshHistory.isNotEmpty() && freshHistory.last().sender == "ai") {
                                    freshHistory[freshHistory.lastIndex] = freshHistory.last().copy(text = partialText)
                                }
                                updateMember(currentMember.copy(
                                    mood = "Génération",
                                    thought = partialText,
                                    chatHistory = freshHistory
                                ))
                            }
                        }
                    }
                }
                responseText = responseBuilder.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de l'interaction avec LiteRT-LM :", e)
                responseText = "Erreur d'inférence LiteRT-LM : ${e.message}"
            }
        } else {
            Log.d(TAG, "Simulation d'inférence LiteRT-LM activée (sans modèle).")
            val fullSimResponse = generateSimulationResponse(memberId, prompt)
            
            // Add placeholder AI message
            withContext(Dispatchers.Main) {
                val currentMember = _clanMembers.value.find { it.id == memberId } ?: member
                val freshHistory = currentMember.chatHistory.toMutableList().apply {
                    add(ChatMessage("ai", ""))
                }
                updateMember(currentMember.copy(
                    mood = "Génération",
                    thought = "Je réfléchis...",
                    chatHistory = freshHistory
                ))
            }
            
            val words = fullSimResponse.split(" ")
            val responseBuilder = java.lang.StringBuilder()
            for (word in words) {
                kotlinx.coroutines.delay(80)
                responseBuilder.append(word).append(" ")
                val partialText = responseBuilder.toString().trim()
                withContext(Dispatchers.Main) {
                    val currentMember = _clanMembers.value.find { it.id == memberId }
                    if (currentMember != null) {
                        val freshHistory = currentMember.chatHistory.toMutableList()
                        if (freshHistory.isNotEmpty() && freshHistory.last().sender == "ai") {
                            freshHistory[freshHistory.lastIndex] = freshHistory.last().copy(text = partialText)
                        }
                        updateMember(currentMember.copy(
                            mood = "Génération",
                            thought = partialText,
                            chatHistory = freshHistory
                        ))
                    }
                }
            }
            responseText = responseBuilder.toString().trim()
        }

        // Mettre à jour le membre final avec la réponse de l'IA
        val currentMember = _clanMembers.value.find { it.id == memberId } ?: member
        val freshHistory = currentMember.chatHistory.toMutableList()
        if (freshHistory.isNotEmpty() && freshHistory.last().sender == "ai") {
            freshHistory[freshHistory.lastIndex] = freshHistory.last().copy(text = responseText)
        } else {
            freshHistory.add(ChatMessage("ai", responseText))
        }

        updateMember(currentMember.copy(
            mood = "Actif",
            thought = responseText,
            chatHistory = freshHistory
        ))
        
        Log.i(TAG, "Mise à jour du membre $memberId effectuée. Humeur : ${currentMember.mood}, Pensée : $responseText")
        
        Log.i(TAG, "Mise à jour du membre $memberId effectuée. Humeur : ${currentMember.mood}, Pensée : $responseText")
    }

    private suspend fun runInference(systemPrompt: String, userPrompt: String): String {
        val eng = engine
        if (eng == null) {
            // Check if it's a classification prompt
            if (systemPrompt.contains("classificateur de produits") || systemPrompt.contains("catégories de l'ontologie GS1")) {
                return when {
                    userPrompt.contains("Tomate", ignoreCase = true) -> "gs1:FruitsVegetables"
                    userPrompt.contains("Basilic", ignoreCase = true) -> "gs1:FruitsVegetables"
                    userPrompt.contains("Banane", ignoreCase = true) -> "gs1:FruitsVegetables"
                    userPrompt.contains("Lait", ignoreCase = true) -> "gs1:Beverage"
                    userPrompt.contains("Moinette", ignoreCase = true) -> "gs1:Beverage"
                    userPrompt.contains("Téléviseur", ignoreCase = true) || userPrompt.contains("TV", ignoreCase = true) -> "gs1:WearableIndividualProduct"
                    userPrompt.contains("Tondeuse", ignoreCase = true) -> "gs1:Product"
                    else -> "gs1:Product"
                }
            }

            // Check if it's a slot extraction prompt
            if (userPrompt.contains("Extrais UNIQUEMENT la valeur de")) {
                val labelLine = userPrompt.split("\n").firstOrNull { it.contains("Extrais UNIQUEMENT la valeur de") } ?: ""
                val allowedValues = userPrompt.split("\n").filter { it.trim().startsWith("- gs1:") }.map { it.replace("-", "").trim() }
                return when {
                    labelLine.contains("Allergen", ignoreCase = true) -> "gs1:AllergenDetails"
                    labelLine.contains("Net Content", ignoreCase = true) -> "750 ml"
                    labelLine.contains("Best Before", ignoreCase = true) -> "2026-12-31"
                    allowedValues.isNotEmpty() -> allowedValues.first().substringBefore(" ")
                    else -> "Simulated Value"
                }
            }

            val chunk = if (userPrompt.contains("à partir de cet extrait :\n\n")) {
                userPrompt.substringAfter("à partir de cet extrait :\n\n")
            } else if (userPrompt.contains("de cette page :\n\n")) {
                userPrompt.substringAfter("de cette page :\n\n")
            } else {
                userPrompt
            }
            return simulateSummary(chunk)
        }
        return withContext(Dispatchers.IO) {
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt)
                )
                val tempConv = eng.createConversation(conversationConfig)
                val responseBuilder = java.lang.StringBuilder()
                tempConv.sendMessageAsync(userPrompt).collect { chunk ->
                    responseBuilder.append(chunk)
                }
                tempConv.close()
                responseBuilder.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de l'inférence de résumé :", e)
                "Erreur de résumé."
            }
        }
    }

    fun simulateSummary(text: String): String {
        return Companion.simulateSummary(text)
    }

    suspend fun summarizeText(memberId: String? = null, text: String, parentInputs: List<String> = emptyList()): String {
        if (text.startsWith("Erreur :")) {
            return "Aucun fait pertinent n'a pu être extrait car l'accès à la source a échoué ou a été bloqué."
        }
        val maxChunkSize = 3000 // characters (~700 tokens)
        val systemPrompt = """
            Tu es Saint Isidore de Séville, l'encyclopédiste universel du clan.
            Tu dois extraire les faits scientifiques, biologiques ou techniques réels de l'extrait de page fourni et les classer obligatoirement sous les quatre livres suivants de ton index :
            - LIVRE I: BIOLOGIE & MORPHOLOGIE (caractéristiques physiques, constitution, structure)
            - LIVRE II: SYMBIOSE & COMPAGNONNAGE (interactions avec d'autres espèces, écologie, protocoles)
            - LIVRE III: PATHOLOGIE & PHYTOSANITAIRE (maladies, ravageurs, pannes, remèdes)
            - LIVRE IV: EDAPHOLOGIE & NUTRITION (besoins en sol, eau, nutriments, énergie, fertilisation)

            Pour chaque livre, écris le titre du livre suivi de listes à puces (•). Si aucun fait ne correspond à un livre, omets ce livre de ta réponse.
            Exclus TOUTES les métadonnées de site, cookies, mentions légales, liens, menus. Ne génère que des faits réels. Si l'extrait ne contient aucun fait pertinent, réponds uniquement 'Aucun fait pertinent trouvé.'.
        """.trimIndent()

        val member = memberId?.let { id -> _clanMembers.value.find { it.id == id } }
        val privateKey = member?.privateKey

        if (text.length > maxChunkSize) {
            Log.i(TAG, "Le texte dépasse $maxChunkSize caractères (${text.length}). Lancement de MapReduce...")
            val chunks = text.chunked(maxChunkSize)
            val summaries = mutableListOf<String>()
            val mapCids = mutableListOf<String>()
            
            for (i in chunks.indices) {
                val chunk = chunks[i]
                Log.d(TAG, "Map: Résumé du morceau ${i + 1}/${chunks.size}")
                val userPrompt = "Extrais et classe uniquement les faits scientifiques, biologiques ou techniques réels sous forme d'index de Livres à partir de cet extrait de page :\n\n$chunk"
                val startTime = System.currentTimeMillis()
                val summary = runInference(systemPrompt, userPrompt)
                val duration = System.currentTimeMillis() - startTime
                if (!summary.contains("Aucun fait pertinent trouvé", ignoreCase = true)) {
                    summaries.add(summary)
                    if (memberId != null && privateKey != null && member != null) {
                        val traceInputs = mutableListOf<String>()
                        if (lastScrapedUrl.isNotEmpty()) traceInputs.add("url:$lastScrapedUrl")
                        if (lastScreenshotPath.isNotEmpty()) traceInputs.add("file:$lastScreenshotPath")
                        
                        val mapPollen = PollenFactory.createAndSignPollen(
                            targetIu = member.iu,
                            motivation = "analyzing",
                            bodyType = "MapSummary",
                            bodyValue = "Résumé du morceau ${i + 1}/${chunks.size} :\n\n$summary",
                            creatorDid = member.did,
                            privateKey = privateKey,
                            traceInputs = traceInputs,
                            tracePrompt = userPrompt,
                            traceDurationMs = duration,
                            traceModel = "gemma-4-E4B-it.litertlm"
                        )
                        addMemberPollen(memberId, mapPollen)
                        mapCids.add(mapPollen.id)
                    }
                }
            }
            
            val combinedText = summaries.joinToString("\n")
            if (combinedText.trim().isEmpty()) {
                return "Aucun fait scientifique ou technique pertinent trouvé sur cette page."
            }
            Log.d(TAG, "Reduce: Fusion de tous les résumés. Taille combinée : ${combinedText.length}")
            return summarizeText(memberId, combinedText, mapCids)
        } else {
            Log.d(TAG, "Extraction finale des faits clés de la page (Taille: ${text.length}).")
            val userPrompt = "Extrais et classe uniquement les faits scientifiques, biologiques ou techniques réels sous forme d'index de Livres à partir de cette page :\n\n$text"
            val startTime = System.currentTimeMillis()
            val result = runInference(systemPrompt, userPrompt)
            val duration = System.currentTimeMillis() - startTime
            
            val isNoFact = result.contains("Aucun fait pertinent trouvé", ignoreCase = true)
            if (memberId != null && privateKey != null && member != null && !isNoFact) {
                val traceInputs = if (parentInputs.isNotEmpty()) {
                    parentInputs
                } else {
                    val list = mutableListOf<String>()
                    if (lastScrapedUrl.isNotEmpty()) list.add("url:$lastScrapedUrl")
                    if (lastScreenshotPath.isNotEmpty()) list.add("file:$lastScreenshotPath")
                    list
                }
                
                val bodyType = if (parentInputs.isNotEmpty()) "ReduceSummary" else "WebPageAnalysis"
                val pollen = PollenFactory.createAndSignPollen(
                    targetIu = member.iu,
                    motivation = "analyzing",
                    bodyType = bodyType,
                    bodyValue = result,
                    creatorDid = member.did,
                    privateKey = privateKey,
                    traceInputs = traceInputs,
                    tracePrompt = userPrompt,
                    traceDurationMs = duration,
                    traceModel = "gemma-4-E4B-it.litertlm"
                )
                addMemberPollen(memberId, pollen)
            }
            
            return if (isNoFact) {
                "Aucun fait scientifique ou technique pertinent trouvé sur cette page."
            } else {
                result
            }
        }
    }

    private suspend fun generateSimulationResponse(memberId: String, prompt: String): String = withContext(Dispatchers.IO) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return@withContext "Je n'existe plus."
        val lowerPrompt = prompt.lowercase()
        when {
            lowerPrompt.contains("analyse") || lowerPrompt.contains("nutriment") || lowerPrompt.contains("sol") || lowerPrompt.contains("qualité") || lowerPrompt.contains("batterie") || lowerPrompt.contains("fraîcheur") -> {
                updatePrimaryStat(memberId)
                "J'ai utilisé l'outil d'analyse pour ajuster ma statistique principale : ${member.stat1Name}. Tout est au maximum !"
            }
            lowerPrompt.contains("eau") || lowerPrompt.contains("arrose") || lowerPrompt.contains("soif") || lowerPrompt.contains("signal") || lowerPrompt.contains("lames") || lowerPrompt.contains("volume") -> {
                updateSecondaryStat(memberId)
                "Outil de réglage activé pour : ${member.stat2Name} ! Valeur optimisée."
            }
            lowerPrompt.contains("grandir") || lowerPrompt.contains("évolue") || lowerPrompt.contains("métamorphose") || lowerPrompt.contains("statut") || lowerPrompt.contains("mode") -> {
                updateStatus(memberId)
                val newStatus = _clanMembers.value.find { it.id == memberId }?.statusValue ?: member.statusValue
                "Outil d'évolution déclenché ! Ma valeur pour ${member.statusLabel} est passée à : $newStatus !"
            }
            lowerPrompt.contains("cherche") || lowerPrompt.contains("google") || lowerPrompt.contains("search") || lowerPrompt.contains("recherche") -> {
                val query = prompt
                    .replace("recherche", "", ignoreCase = true)
                    .replace("cherche", "", ignoreCase = true)
                    .replace("google", "", ignoreCase = true)
                    .replace("search", "", ignoreCase = true)
                    .replace("et enrichis ton profil", "", ignoreCase = true)
                    .replace("enrichis ton profil avec", "", ignoreCase = true)
                    .trim()
                Log.d(TAG, "Simulation : Recherche web autonome pour '$query'...")
                val results = WebScraper.searchWeb(query)
                if (results.isEmpty()) {
                    "Aucun résultat trouvé pour '$query' sur le web."
                } else {
                    val bestResult = results.first()
                    val title = bestResult.first
                    val url = bestResult.second
                    Log.i(TAG, "Simulation : Page sélectionnée : '$title' ($url). Scraping WebView...")
                    try {
                        val result = WebScraper.scrapeAndOcrPage(context, url) { sysPrompt, userPrompt ->
                            runInference(sysPrompt, userPrompt)
                        }
                        
                        // Enregistrer la provenance
                        lastScrapedUrl = url
                        lastScreenshotPath = result.screenshotPath
                        
                        val summary = summarizeText(memberId, result.extractedText)
                        
                        // Auto-enrichir le profil
                        updateLearnedFacts(memberId, summary)
                        
                        "Recherche effectuée sur : '$query'.\n" +
                        "Page sélectionnée : '$title' ($url).\n" +
                        "Capture d'écran pleine hauteur sauvegardée à : ${result.screenshotPath}.\n" +
                        "Langue détectée : ${result.detectedLanguage}.\n" +
                        "Profil du compagnon enrichi avec succès !"
                    } catch (e: Exception) {
                        Log.e(TAG, "Simulation : Erreur lors de l'auto-enrichissement pour $url", e)
                        "Erreur lors de la capture et de l'extraction OCR de la page : ${e.message}"
                    }
                }
            }
            lowerPrompt.contains("visite") || lowerPrompt.contains("http") -> {
                val url = prompt.split(" ", "\n").firstOrNull { it.startsWith("http") }
                if (url != null) {
                    Log.d(TAG, "Simulation : Lecture réelle et OCR pour l'URL : $url")
                    try {
                        val result = WebScraper.scrapeAndOcrPage(context, url) { sysPrompt, userPrompt ->
                            runInference(sysPrompt, userPrompt)
                        }
                        
                        // Enregistrer la provenance
                        lastScrapedUrl = url
                        lastScreenshotPath = result.screenshotPath
                        
                        val finalizedText = summarizeText(memberId, result.extractedText)
                        
                        // Auto-enrichir le profil
                        updateLearnedFacts(memberId, finalizedText)
                        
                        "Visite et OCR terminés de la page : $url.\n" +
                        "Capture d'écran sauvegardée à : ${result.screenshotPath}.\n" +
                        "Langue détectée : ${result.detectedLanguage}.\n" +
                        "Profil enrichi avec le résumé de la page !"
                    } catch (e: Exception) {
                        Log.e(TAG, "Simulation : Erreur lors de la capture de page", e)
                        "Erreur lors de la capture et de l'extraction OCR de la page : ${e.message}"
                    }
                } else {
                    "Aucune URL valide trouvée dans la commande."
                }
            }
            lowerPrompt.contains("mémorise") || lowerPrompt.contains("enregistre") || lowerPrompt.contains("enrichit") -> {
                val facts = prompt.replace("mémorise", "", ignoreCase = true)
                    .replace("enregistre", "", ignoreCase = true)
                    .replace("enrichit", "", ignoreCase = true).trim()
                updateLearnedFacts(memberId, facts)
                "J'ai enregistré les connaissances réelles dans mon profil : $facts"
            }
            else -> {
                "Bonjour humain ! Je suis un(e) ${member.type} et je communique localement avec toi grâce à LiteRT-LM."
            }
        }
    }

    inner class ClanMemberToolSet(private val toolMemberId: String? = null) : ToolSet {
        @Tool(description = "Restaure ou ajuste la statistique principale (ex: Nutriments, Qualité d'Image, Niveau de Batterie, Fraîcheur).")
        fun updatePrimaryStat(
            @ToolParam(description = "L'identifiant unique (UUID/String) du membre.") memberId: String
        ): String {
            Log.i(TAG, "Tool calling : updatePrimaryStat déclenché pour $memberId")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : updatePrimaryStat")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Optimisation de la statistique principale : ${member.stat1Name}..."))
                }
            }
            this@GemmaTamagotchiEngine.updatePrimaryStat(memberId)
            return "Succès : Statistique principale ajustée."
        }

        @Tool(description = "Restaure ou ajuste la statistique secondaire (ex: Niveau d'Eau, Stabilité Signal, Affûtage des Lames, Volume Restant).")
        fun updateSecondaryStat(
            @ToolParam(description = "L'identifiant unique (UUID/String) du membre.") memberId: String
        ): String {
            Log.i(TAG, "Tool calling : updateSecondaryStat déclenché pour $memberId")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : updateSecondaryStat")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Ajustement de la statistique secondaire : ${member.stat2Name}..."))
                }
            }
            this@GemmaTamagotchiEngine.updateSecondaryStat(memberId)
            return "Succès : Statistique secondaire ajustée."
        }

        @Tool(description = "Fait évoluer le statut du compagnon (ex: Stade, État, Mode, Emballage).")
        fun updateStatus(
            @ToolParam(description = "L'identifiant unique (UUID/String) du membre.") memberId: String
        ): String {
            Log.i(TAG, "Tool calling : updateStatus déclenché pour $memberId")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : updateStatus")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Évolution du statut (${member.statusLabel})..."))
                }
            }
            this@GemmaTamagotchiEngine.updateStatus(memberId)
            return "Succès : Statut mis à jour."
        }

        @Tool(description = "Recherche des informations sur le web à propos du produit ou du sujet spécifié.")
        fun searchWeb(
            @ToolParam(description = "La requête de recherche Google/DuckDuckGo.") query: String
        ): String = runBlocking {
            Log.i(TAG, "Tool calling : searchWeb déclenché pour la requête '$query'")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : searchWeb (requête : '$query')")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Recherche web : $query"))
                }
            }
            val results = WebScraper.searchWeb(query)
            toolMemberId?.let { id ->
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Réflexion", thought = "Analyse des résultats pour '$query'..."))
                }
            }
            if (results.isEmpty()) {
                "Aucun résultat trouvé sur le web pour : $query"
            } else {
                buildString {
                    append("Résultats de recherche trouvés :\n")
                    results.forEachIndexed { index, pair ->
                        append("${index + 1}. Titre: ${pair.first}\n   URL: ${pair.second}\n")
                    }
                }
            }
        }

        @Tool(description = "Visite une page web à partir de son URL pour en extraire le contenu textuel et effectuer un OCR sur sa capture d'écran.")
        fun visitWebPage(
            @ToolParam(description = "L'URL absolue de la page web à visiter.") url: String
        ): String = runBlocking {
            Log.i(TAG, "Tool calling : visitWebPage déclenché pour l'URL '$url'")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : visitWebPage (url : $url)")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Scraping & OCR de la page : $url"))
                }
            }
            try {
                val result = WebScraper.scrapeAndOcrPage(context, url) { sysPrompt, userPrompt ->
                    runInference(sysPrompt, userPrompt)
                }
                
                // Keep track of the provenance for the signed pollen trace
                lastScrapedUrl = url
                lastScreenshotPath = result.screenshotPath
                
                // Summarize the text using local MapReduce
                toolMemberId?.let { id ->
                    val member = _clanMembers.value.find { it.id == id }
                    if (member != null) {
                        updateMember(member.copy(mood = "Classification", thought = "Extraction et classification des faits scientifiques..."))
                    }
                }
                val finalizedText = summarizeText(toolMemberId, result.extractedText)
                
                toolMemberId?.let { id ->
                    val member = _clanMembers.value.find { it.id == id }
                    if (member != null) {
                        updateMember(member.copy(mood = "Réflexion", thought = "Intégration des faits extraits..."))
                    }
                }
                
                "Résultat de la visite de la page (Langue détectée: ${result.detectedLanguage}):\n\n$finalizedText"
            } catch (e: Exception) {
                Log.e(TAG, "Erreur dans visitWebPage tool", e)
                toolMemberId?.let { id ->
                    val member = _clanMembers.value.find { it.id == id }
                    if (member != null) {
                        updateMember(member.copy(mood = "Réflexion", thought = "Échec de l'appel outil : ${e.message}"))
                    }
                }
                "Erreur lors de la capture et de l'extraction OCR de la page : ${e.message}"
            }
        }

        @Tool(description = "Enrichit le profil du Tamagotchi avec de nouveaux faits ou connaissances apprises sur le web.")
        fun enrichProfile(
            @ToolParam(description = "L'identifiant unique du membre.") memberId: String,
            @ToolParam(description = "Les faits clés résumés à ajouter à la fiche du produit.") keyFacts: String
        ): String {
            Log.i(TAG, "Tool calling : enrichProfile pour $memberId. Faits : '$keyFacts'")
            toolMemberId?.let { id ->
                updateMemberLog(id, "🔧 Appel outil : enrichProfile")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    updateMember(member.copy(mood = "Appel Outil", thought = "Indexation des faits enrichis..."))
                }
            }
            updateLearnedFacts(memberId, keyFacts)
            return "Succès : La fiche profil a été mise à jour avec les nouveaux faits."
        }

        @Tool(description = "Enrichit une propriete G S 1 specifique (predicat) du Tamagotchi avec une valeur extraite.")
        fun enrichStructuredProperty(
            @ToolParam(description = "L'identifiant unique du membre.") memberId: String,
            @ToolParam(description = "Le predicat G S 1 de la propriete (ex: 'gs1:bestBeforeDate', 'gs1:netContent').") predicate: String,
            @ToolParam(description = "La valeur de la propriete (ex: '2026-07-01', '750 ml').") value: String
        ): String {
            Log.i(TAG, "Tool calling : enrichStructuredProperty pour $memberId. Predicat : $predicate, Valeur : $value")
            toolMemberId?.let { id ->
                updateMemberLog(id, "Outil : enrichStructuredProperty ($predicate -> $value)")
                val member = _clanMembers.value.find { it.id == id }
                if (member != null) {
                    val privateKey = member.privateKey
                    if (privateKey != null) {
                        val traceInputs = mutableListOf<String>()
                        if (lastScrapedUrl.isNotEmpty()) traceInputs.add("url:$lastScrapedUrl")
                        if (lastScreenshotPath.isNotEmpty()) traceInputs.add("file:$lastScreenshotPath")
                        if (traceInputs.isEmpty()) traceInputs.add("urn:cid:direct_agent_enrichment")

                        val pollen = PollenFactory.createAndSignPollen(
                            targetIu = member.iu,
                            motivation = "enriching",
                            bodyType = "StructuredFact",
                            bodyValue = value,
                            predicate = predicate,
                            creatorDid = member.did,
                            privateKey = privateKey,
                            traceInputs = traceInputs,
                            tracePrompt = "Extraction autonome du champ $predicate avec la valeur $value",
                            traceDurationMs = 1500,
                            traceModel = "gemma-4-E4B-it.litertlm"
                        )
                        addMemberPollen(id, pollen)
                    }
                }
            }
            return "Succes : La propriete semantique '$predicate' a ete renseignee avec la valeur '$value'."
        }

        @Tool(description = "Declenche la recherche et l'extraction automatique pour documenter le prochain champ vide du profil.")
        fun completeNextKnowledgeSlot(
            @ToolParam(description = "L'identifiant unique du membre.") memberId: String
        ): String {
            Log.i(TAG, "Tool calling : completeNextKnowledgeSlot pour $memberId")
            toolMemberId?.let { id ->
                updateMemberLog(id, "Outil : completeNextKnowledgeSlot")
            }
            return kotlinx.coroutines.runBlocking {
                runKnowledgeQuestTick(memberId)
            }
        }
    }

    fun updatePrimaryStat(memberId: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val nextVal = (member.stat1Value + 0.3f).coerceAtMost(1.0f)
        val freshPollenList = member.pollens.toMutableList()
        val privateKey = member.privateKey
        if (privateKey != null) {
            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member.iu,
                motivation = "analyzing",
                bodyType = "PrimaryStatAnalysis",
                bodyValue = "Mise à jour de ${member.stat1Name} ajustée à ${(nextVal * 100).toInt()}%.",
                creatorDid = member.did,
                privateKey = privateKey
            )
            freshPollenList.add(pollen)
        }
        updateMember(member.copy(
            stat1Value = nextVal,
            mood = "Scientifique",
            pollens = freshPollenList
        ))
        updateMemberLog(memberId, "Outil exécuté : Mise à jour de ${member.stat1Name} (+30%)")
    }

    fun updateSecondaryStat(memberId: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val nextVal = (member.stat2Value + 0.4f).coerceAtMost(1.0f)
        val freshPollenList = member.pollens.toMutableList()
        val privateKey = member.privateKey
        if (privateKey != null) {
            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member.iu,
                motivation = "evaluating",
                bodyType = "SecondaryStatState",
                bodyValue = "Mise à jour de ${member.stat2Name} ajustée à ${(nextVal * 100).toInt()}%.",
                creatorDid = member.did,
                privateKey = privateKey
            )
            freshPollenList.add(pollen)
        }
        updateMember(member.copy(
            stat2Value = nextVal,
            mood = "Optimisé",
            pollens = freshPollenList
        ))
        updateMemberLog(memberId, "Outil exécuté : Mise à jour de ${member.stat2Name} (+40%)")
    }

    fun updateStatus(memberId: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val nextValue = when (member.statusValue) {
            // Plants
            "Graine/Initial" -> "Pousse"
            "Pousse" -> "Maturation"
            "Maturation" -> "Vivant/Autonome"
            // TV
            "Configuration" -> "Opérationnel"
            "Opérationnel" -> "Optimisé"
            // Lawnmower
            "En attente" -> "Prêt"
            "Prêt" -> "En service"
            // Milk
            "Scellé" -> "Ouvert"
            "Ouvert" -> "Consommé"
            // Default transition
            else -> "Évolué"
        }
        val freshPollenList = member.pollens.toMutableList()
        val privateKey = member.privateKey
        if (privateKey != null) {
            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member.iu,
                motivation = "evaluating",
                bodyType = "StatusValueState",
                bodyValue = "Évolution morphologique vers le stade : $nextValue.",
                creatorDid = member.did,
                privateKey = privateKey
            )
            freshPollenList.add(pollen)
        }
        updateMember(member.copy(
            statusValue = nextValue,
            mood = "Évolué",
            pollens = freshPollenList
        ))
        updateMemberLog(memberId, "Outil exécuté : Évolution de ${member.statusLabel} vers $nextValue")
    }

    fun updateLearnedFacts(memberId: String, keyFacts: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val currentFacts = member.learnedFacts
        val updatedFacts = mergeAndCleanIndexedFacts(currentFacts, keyFacts)
        val freshPollenList = member.pollens.toMutableList()
        val privateKey = member.privateKey
        if (privateKey != null) {
            val promptUsed = "Enrichir le profil avec les faits clés: $keyFacts"
            
            // Build trace inputs to include the proof of source URL and screenshot
            val traceInputs = mutableListOf<String>()
            val parentPollen = member.pollens.lastOrNull { 
                it.body.type == "ReduceSummary" || it.body.type == "WebPageAnalysis" 
            }
            if (parentPollen != null) {
                traceInputs.add(parentPollen.id)
            } else {
                if (lastScrapedUrl.isNotEmpty()) {
                    traceInputs.add("url:$lastScrapedUrl")
                }
                if (lastScreenshotPath.isNotEmpty()) {
                    traceInputs.add("file:$lastScreenshotPath")
                }
                if (traceInputs.isEmpty()) {
                    traceInputs.add("urn:cid:source_web_scraping")
                }
            }

            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member.iu,
                motivation = "enriching",
                bodyType = "Fact",
                bodyValue = keyFacts,
                creatorDid = member.did,
                privateKey = privateKey,
                traceInputs = traceInputs,
                tracePrompt = promptUsed,
                traceDurationMs = 1200,
                traceModel = "gemma-4-E4B-it.litertlm"
            )
            freshPollenList.add(pollen)
        }
        
        val isEvolving = member.level == 0
        val nextLevel = if (isEvolving) 1 else member.level
        val nextStade = if (isEvolving) "Bébé/Pousse" else member.statusValue

        updateMember(member.copy(
            learnedFacts = updatedFacts,
            mood = "Savant",
            pollens = freshPollenList,
            level = nextLevel,
            statusValue = nextStade
        ))
        updateMemberLog(memberId, "Profil enrichi : Nouvelles connaissances acquises")

        if (isEvolving) {
            updateMemberLog(memberId, "Évolution au Niveau 1 (Bébé/Pousse) déclenchée !")
            triggerAvatarGeneration(
                memberId = memberId,
                level = 1,
                prompt = "A baby or sprout stage for a ${member.type} character, hatching or growing, glowing aura, cyber-gothic style, pixel art"
            )
        }

    }

    suspend fun runKnowledgeQuestTick(memberId: String): String {
        val member = _clanMembers.value.find { it.id == memberId } ?: return "Membre introuvable."
        val repo = be.heyman.android.etymoclan.data.gs1voc.Gs1VocRepository.get(context)
        val builder = be.heyman.android.etymoclan.data.gs1voc.KnowledgeFrameBuilder(repo)
        val frame = builder.build(member.gs1Class, member.pollens)

        val privateKey = member.privateKey ?: return "Clé privée manquante pour signer."

        val hooks = object : be.heyman.android.etymoclan.agentcore.EnrichmentHooks {
            override val modelId: String = "gemma-4-E4B-it.litertlm"

            override suspend fun searchAndScrape(query: String): be.heyman.android.etymoclan.agentcore.EnrichmentHooks.Evidence? {
                Log.d(TAG, "Quest: Recherche et Scraping pour '$query'")
                val results = WebScraper.searchWeb(query)
                if (results.isEmpty()) return null
                val bestUrl = results.first().second
                return try {
                    val res = WebScraper.scrapeAndOcrPage(context, bestUrl) { sysPrompt, userPrompt ->
                        runInference(sysPrompt, userPrompt)
                    }
                    val cid = if (res.screenshotPath.isNotEmpty()) {
                        runCatching {
                            val p = res.screenshotPath
                            val bytes = java.io.File(p).readBytes()
                            val h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                            val finalCid = "urn:cid:" + h.joinToString("") { "%02x".format(it) }
                            screenshotPathByCid[finalCid] = p
                            finalCid
                        }.getOrNull()
                    } else null
                    be.heyman.android.etymoclan.agentcore.EnrichmentHooks.Evidence(
                        text = res.extractedText,
                        sourceUrl = bestUrl,
                        screenshotCid = cid
                    )
                } catch (e: Exception) {
                    null
                }
            }

            override suspend fun extractWithLlm(prompt: String): String {
                Log.d(TAG, "Quest: Extraction LLM...")
                return runInference("", prompt)
            }
        }

        val quest = be.heyman.android.etymoclan.agentcore.KnowledgeQuest(repo, hooks)
        val exclude = failedPredicatesByMember[memberId] ?: emptySet()
        return when (val r = quest.fillNextSlot(member.iu, member.did, privateKey, frame, member.type, exclude)) {
            is be.heyman.android.etymoclan.agentcore.KnowledgeQuest.Result.Filled -> {
                val currentMember = _clanMembers.value.find { it.id == memberId } ?: member
                val updatedPollens = currentMember.pollens.toMutableList().apply { add(r.pollen) }
                val nextVal = (currentMember.stat1Value + 0.1f).coerceAtMost(1.0f)
                updateMember(currentMember.copy(
                    stat1Value = nextVal,
                    mood = "Savant",
                    pollens = updatedPollens
                ))
                updateMemberLog(memberId, "Boucle Quête : Champ '${r.slot.label}' complété avec succès (${r.pollen.body.value}).")
                "Succès : Le champ '${r.slot.label}' a été documenté avec '${r.pollen.body.value}'."
            }
            is be.heyman.android.etymoclan.agentcore.KnowledgeQuest.Result.NotFound -> {
                failedPredicatesByMember.getOrPut(memberId) { mutableSetOf() }.add(r.slot.predicate)
                updateMemberLog(memberId, "Boucle Quête : Information pour le champ '${r.slot.label}' introuvable sur le web.")
                "Echec : Impossible de trouver l'information pour le champ '${r.slot.label}'."
            }
            be.heyman.android.etymoclan.agentcore.KnowledgeQuest.Result.FrameComplete -> {
                updateMemberLog(memberId, "Boucle Quête : Cadre de connaissances entièrement complété !")
                "Félicitations : Toutes les connaissances ont été complétées pour ce membre !"
            }
        }
    }

    fun mergeAndCleanIndexedFacts(currentFacts: String, newFacts: String): String {
        val book1 = "LIVRE I: BIOLOGIE & MORPHOLOGIE"
        val book2 = "LIVRE II: SYMBIOSE & COMPAGNONNAGE"
        val book3 = "LIVRE III: PATHOLOGY & PHYTOSANITAIRE"
        val book4 = "LIVRE IV: EDAPHOLOGIE & NUTRITION"

        val books = mapOf(
            book1 to mutableSetOf<String>(),
            book2 to mutableSetOf<String>(),
            book3 to mutableSetOf<String>(),
            book4 to mutableSetOf<String>()
        )

        fun parseFacts(text: String) {
            var currentBook: String? = null
            val lines = text.split("\n")
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                
                val lower = trimmed.lowercase()
                when {
                    lower.contains("livre i") || (lower.contains("biologie") && lower.contains("morphologie")) -> {
                        currentBook = book1
                        continue
                    }
                    lower.contains("livre ii") || (lower.contains("symbiose") && lower.contains("compagnonnage")) -> {
                        currentBook = book2
                        continue
                    }
                    lower.contains("livre iii") || (lower.contains("pathologie") && lower.contains("phytosanitaire")) || lower.contains("pathology") -> {
                        currentBook = book3
                        continue
                    }
                    lower.contains("livre iv") || (lower.contains("edaphologie") && lower.contains("nutrition")) -> {
                        currentBook = book4
                        continue
                    }
                }

                val cleanFact = trimmed.replace(Regex("^[-*•\\s]+"), "").trim()
                if (cleanFact.isNotEmpty() && !cleanFact.startsWith("LIVRE")) {
                    val book = currentBook ?: book1
                    val alreadyExists = books[book]?.any { it.equals(cleanFact, ignoreCase = true) } ?: false
                    if (!alreadyExists) {
                        books[book]?.add(cleanFact)
                    }
                }
            }
        }

        parseFacts(currentFacts)
        parseFacts(newFacts)

        return buildString {
            for (bookName in listOf(book1, book2, book3, book4)) {
                val facts = books[bookName]
                if (!facts.isNullOrEmpty()) {
                    append(bookName).append("\n")
                    for (fact in facts) {
                        append("• ").append(fact).append("\n")
                    }
                    append("\n")
                }
            }
        }.trim()
    }

    private fun addChatMessage(memberId: String, sender: String, text: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val updatedHistory = member.chatHistory.toMutableList().apply {
            add(ChatMessage(sender, text))
        }
        updateMember(member.copy(chatHistory = updatedHistory))
    }

    private fun updateMemberMoodAndThought(memberId: String, mood: String, thought: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        updateMember(member.copy(mood = mood, thought = thought))
    }

    private suspend fun generateSearchTerms(member: ClanMember): List<String> {
        val eng = engine
        if (eng != null) {
            val systemPrompt = """
                Tu es un expert botaniste, agronome ou ingénieur technique selon le type de produit.
                Génère exactement 4 requêtes de recherche Google/DuckDuckGo pour approfondir les connaissances sur ce membre du clan.
                Fiche d'identité :
                Type : ${member.type}
                Origine : ${member.origin}
                Description : ${member.description}
                
                Consignes absolues :
                - Réponds UNIQUEMENT avec les 4 requêtes, une par ligne.
                - Pas d'introduction, pas de conclusion, pas de numérotation, pas de puces, pas de guillemets.
                - Chaque ligne doit être uniquement le texte de la requête de recherche.
            """.trimIndent()
            val userPrompt = "Génère les 4 requêtes de recherche."
            try {
                val startTime = System.currentTimeMillis()
                val response = runInference(systemPrompt, userPrompt)
                val duration = System.currentTimeMillis() - startTime
                Log.d(TAG, "Gemma search terms response:\n$response")
                val parsed = response.split("\n")
                    .map { it.trim().replace(Regex("^[0-9]+[.)-]\\s*"), "").replace("\"", "").trim() }
                    .filter { it.isNotEmpty() && !it.contains("voici", ignoreCase = true) && !it.contains("requête", ignoreCase = true) }
                    .take(4)
                if (parsed.size == 4) {
                    val privateKey = member.privateKey
                    if (privateKey != null) {
                        val pollen = PollenFactory.createAndSignPollen(
                            targetIu = member.iu,
                            motivation = "analyzing",
                            bodyType = "SearchQueries",
                            bodyValue = parsed.joinToString("\n"),
                            creatorDid = member.did,
                            privateKey = privateKey,
                            traceInputs = emptyList(),
                            tracePrompt = userPrompt,
                            traceDurationMs = duration,
                            traceModel = "gemma-4-E4B-it.litertlm"
                        )
                        addMemberPollen(member.id, pollen)
                    }
                    return parsed
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur génération requêtes Gemma, fallback...", e)
            }
        }
        
        return when (member.type) {
            "Terreau" -> listOf(
                "sarcomusation hermetia illucens mouche soldat noire",
                "azote phosphore potassium compost larve",
                "amendement de sol chitine frass d'insectes",
                "permaculture ratio carbone azote compost"
            )
            "Tomate" -> listOf(
                "solanum lycopersicum besoins nutriments azote",
                "compagnonnage tomate basilic potager",
                "maladies de la tomate mildiou alternariose traitement",
                "ph ideal sol tomate edaphologie"
            )
            "Basilic" -> listOf(
                "ocimum basilicum exposition arrosage sol",
                "association plantes compagnon tomate basilic",
                "fusariose du basilic maladie phytosanitaire",
                "besoin azote engrais organique basilic"
            )
            "Téléviseur" -> listOf(
                "technologie d'affichage led oled retroeclairage",
                "connectique hdmi cec earc protocole",
                "panne alimentation ecran noir televiseur diagnostic",
                "consommation electrique veille etiquette energie"
            )
            "Tondeuse" -> listOf(
                "batterie lithium ion tondeuse autonomie duree de vie",
                "affutage des lames tondeuse mulching entretien",
                "panne moteur demarrage tondeuse electrique diagnostic",
                "hauteur de coupe gazon tonte pelouse"
            )
            "Brique de Lait" -> listOf(
                "conservation lait uht pasteurisation conditionnement",
                "recyclage briques de lait carton aluminium plastique",
                "alteration du lait developpement bacterien pasteurise",
                "valeur nutritionnelle calcium proteines lait entier"
            )
            "Moinette Blonde" -> listOf(
                "brasserie dupont histoire biere moinette blonde",
                "fabrication biere artisanale refermentation en bouteille belgique",
                "degre alcool conservation biere forte ddm dluo",
                "delhaize supermarche rayonnage biere dupont"
            )
            else -> listOf(
                "${member.type} caractéristiques et origine",
                "${member.type} entretien et optimisation",
                "${member.type} pannes et solutions",
                "${member.type} composition et structure"
            )
        }
    }

    fun startAutonomousExploration(memberId: String) {
        engineScope.launch {
            try {
                val member = _clanMembers.value.find { it.id == memberId } ?: return@launch
                
                addChatMessage(memberId, "system", "🔍 Lancement de l'Auto-Exploration structurée par l'Agent...")
                updateMemberLog(memberId, "⚙️ Tâche : Lancement de l'Auto-Exploration structurée")
                
                failedPredicatesByMember[memberId] = mutableSetOf()
                val failed = failedPredicatesByMember.getOrPut(memberId) { mutableSetOf() }
                
                var slotsFilled = 0
                val targetSlotsToFill = 3 // Tentative de remplir 3 slots du graphe de connaissances
                
                for (step in 1..targetSlotsToFill) {
                    val currentMember = _clanMembers.value.find { it.id == memberId } ?: break
                    val repo = be.heyman.android.etymoclan.data.gs1voc.Gs1VocRepository.get(context)
                    val builder = be.heyman.android.etymoclan.data.gs1voc.KnowledgeFrameBuilder(repo)
                    val frame = builder.build(currentMember.gs1Class, currentMember.pollens)
                    
                    val slot = frame.nextEmptySlot(failed)
                    if (slot == null) {
                        addChatMessage(memberId, "system", "✅ Cadre de connaissances GS1voc entièrement complété !")
                        updateMemberLog(memberId, "✅ Tâche : Cadre de connaissances complet")
                        break
                    }
                    
                    addChatMessage(memberId, "ai", "Je lance la recherche pour documenter le champ : ${slot.label} (Prédicat: ${slot.predicate}) [${step}/$targetSlotsToFill]...")
                    updateMemberMoodAndThought(memberId, "Recherche", "Recherche de ${slot.label}...")
                    
                    val resultText = runKnowledgeQuestTick(memberId)
                    addChatMessage(memberId, "system", "Résultat : $resultText")
                    
                    if (resultText.startsWith("Succès")) {
                        slotsFilled++
                    }
                    
                    kotlinx.coroutines.delay(3000)
                }
                
                val finalMember = _clanMembers.value.find { it.id == memberId }
                if (finalMember != null) {
                    updateMember(finalMember.copy(
                        mood = "Savant",
                        thought = "Exploration structurée terminée. J'ai documenté $slotsFilled nouveaux champs dans mon Graphe GS1 !"
                    ))
                }
                addChatMessage(memberId, "system", "✅ Auto-Exploration terminée ! $slotsFilled champs ont été ajoutés et signés dans le Graphe de Connaissances GS1voc.")
                updateMemberLog(memberId, "✅ Tâche : Auto-Exploration terminée avec $slotsFilled champs renseignés.")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur critique dans startAutonomousExploration", e)
                addChatMessage(memberId, "system", "❌ Erreur critique lors de l'exploration autonome : ${e.message}")
                updateMemberMoodAndThought(memberId, "Actif", "Exploration interrompue suite à une erreur.")
                updateMemberLog(memberId, "❌ Tâche : Exploration interrompue suite à une erreur critique")
            }
        }
    }

    fun updateMember(updatedMember: ClanMember) {
        _clanMembers.value = _clanMembers.value.map { 
            if (it.id == updatedMember.id) updatedMember else it 
        }
    }

    fun deleteMember(memberId: String) {
        Log.i(TAG, "Suppression du membre $memberId du clan.")
        activeConversations.remove(memberId)?.close()
        _clanMembers.value = _clanMembers.value.filter { it.id != memberId }
    }

    fun triggerAvatarGeneration(memberId: String, level: Int, prompt: String) {
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val outputDir = File(context.getExternalFilesDir("avatars"), "")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFileMnn = File(outputDir, "${member.id}_level_${level}_mnn.png")
        val outputFileMediaPipe = File(outputDir, "${member.id}_level_${level}_mediapipe.png")
        
        Log.i(TAG, "Lancement de la génération d'avatar local double (MNN & MediaPipe) pour ${member.type} Niveau $level...")
        
        engineScope.launch {
            val startTimeMnn = System.currentTimeMillis()
            val mnnModel = be.heyman.android.etymoclan.agentcore.MnnModel("Sana-1.6B", "")
            val successMnn = mnnModel.generateImage(prompt, outputFileMnn.absolutePath)
            val durationMnn = System.currentTimeMillis() - startTimeMnn
            
            val startTimeMp = System.currentTimeMillis()
            val mediaPipeModel = be.heyman.android.etymoclan.agentcore.MediaPipeModel(context, "")
            val successMediaPipe = mediaPipeModel.generateImage(prompt, outputFileMediaPipe.absolutePath)
            val durationMp = System.currentTimeMillis() - startTimeMp
            
            withContext(Dispatchers.Main) {
                val currentList = _clanMembers.value.map {
                    if (it.id == memberId) {
                        it.copy(
                            avatarPath = if (successMnn && outputFileMnn.exists()) outputFileMnn.absolutePath else it.avatarPath,
                            avatarPathMediaPipe = if (successMediaPipe && outputFileMediaPipe.exists()) outputFileMediaPipe.absolutePath else it.avatarPathMediaPipe,
                            level = level
                        )
                    } else it
                }
                _clanMembers.value = currentList
                updateMemberLog(memberId, "Avatars d'Évolution (MNN & MediaPipe) Niveau $level générés avec succès.")
                
                val updatedMember = _clanMembers.value.find { it.id == memberId }
                val privateKey = updatedMember?.privateKey
                if (updatedMember != null && privateKey != null) {
                    if (successMnn && outputFileMnn.exists()) {
                        val pollenMnn = PollenFactory.createAndSignPollen(
                            targetIu = updatedMember.iu,
                            motivation = "evaluating",
                            bodyType = "EvolutionAvatar",
                            bodyValue = "Avatar d'Évolution (MNN Sana-1.6B) Niveau $level",
                            creatorDid = updatedMember.did,
                            privateKey = privateKey,
                            traceInputs = listOf("file:${outputFileMnn.absolutePath}"),
                            tracePrompt = prompt,
                            traceDurationMs = durationMnn,
                            traceModel = "Sana-1.6B"
                        )
                        addMemberPollen(memberId, pollenMnn)
                    }
                    if (successMediaPipe && outputFileMediaPipe.exists()) {
                        val pollenMp = PollenFactory.createAndSignPollen(
                            targetIu = updatedMember.iu,
                            motivation = "evaluating",
                            bodyType = "EvolutionAvatar",
                            bodyValue = "Avatar d'Évolution (MediaPipe Stable Diffusion) Niveau $level",
                            creatorDid = updatedMember.did,
                            privateKey = privateKey,
                            traceInputs = listOf("file:${outputFileMediaPipe.absolutePath}"),
                            tracePrompt = prompt,
                            traceDurationMs = durationMp,
                            traceModel = "MediaPipe-StableDiffusion"
                        )
                        addMemberPollen(memberId, pollenMp)
                    }
                }
            }
        }
    }

    fun interactCrossMembers(memberId1: String, memberId2: String) {
        val member1 = _clanMembers.value.find { it.id == memberId1 } ?: return
        val member2 = _clanMembers.value.find { it.id == memberId2 } ?: return

        Log.i(TAG, "Pollinisation croisée initiée entre ${member1.type} et ${member2.type}")

        val nextLevel1 = if (member1.level < 2) 2 else member1.level + 1
        val nextLevel2 = if (member2.level < 2) 2 else member2.level + 1

        val nextStade1 = "Divinité/Adulte"
        val nextStade2 = "Divinité/Adulte"

        val freshPollenList1 = member1.pollens.toMutableList()
        val privateKey1 = member1.privateKey
        if (privateKey1 != null) {
            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member1.iu,
                motivation = "enriching",
                bodyType = "CrossPollinationState",
                bodyValue = "Pollinisation croisée effectuée avec le membre ${member2.type} (${member2.iu}).",
                creatorDid = member1.did,
                privateKey = privateKey1
            )
            freshPollenList1.add(pollen)
        }

        val freshPollenList2 = member2.pollens.toMutableList()
        val privateKey2 = member2.privateKey
        if (privateKey2 != null) {
            val pollen = PollenFactory.createAndSignPollen(
                targetIu = member2.iu,
                motivation = "enriching",
                bodyType = "CrossPollinationState",
                bodyValue = "Pollinisation croisée effectuée avec le membre ${member1.type} (${member1.iu}).",
                creatorDid = member2.did,
                privateKey = privateKey2
            )
            freshPollenList2.add(pollen)
        }

        updateMember(member1.copy(
            level = nextLevel1,
            statusValue = nextStade1,
            mood = "Majestueux",
            pollens = freshPollenList1
        ))
        updateMemberLog(memberId1, "Pollinisation croisée réussie avec ${member2.type} ! Évolution au Niveau $nextLevel1.")

        updateMember(member2.copy(
            level = nextLevel2,
            statusValue = nextStade2,
            mood = "Majestueux",
            pollens = freshPollenList2
        ))
        updateMemberLog(memberId2, "Pollinisation croisée réussie avec ${member1.type} ! Évolution au Niveau $nextLevel2.")

        triggerAvatarGeneration(
            memberId = memberId1,
            level = nextLevel1,
            prompt = "A mature cybernetic deity stage for a ${member1.type} character, full power, golden cyber-gothic, majestic, pixel art"
        )
        triggerAvatarGeneration(
            memberId = memberId2,
            level = nextLevel2,
            prompt = "A mature cybernetic deity stage for a ${member2.type} character, full power, golden cyber-gothic, majestic, pixel art"
        )
    }

    private fun addMemberPollen(memberId: String, pollen: Pollen) {
        _clanMembers.value = _clanMembers.value.map {
            if (it.id == memberId) {
                val updatedPollens = it.pollens.toMutableList()
                updatedPollens.add(pollen)
                it.copy(pollens = updatedPollens)
            } else it
        }
    }

    private fun updateMemberLog(memberId: String, logMsg: String) {

        Log.d(TAG, "Nouveau log pour $memberId : $logMsg")
        val member = _clanMembers.value.find { it.id == memberId } ?: return
        val newLogs = member.logs.toMutableList()
        newLogs.add(0, logMsg)
        updateMember(member.copy(logs = newLogs))
    }

    companion object {
        private var instance: GemmaTamagotchiEngine? = null

        fun getInstance(): GemmaTamagotchiEngine? = instance

        fun getEngineInstance(): Engine? = instance?.engine

        fun simulateSummary(text: String): String {
            val lines = text.split("\n")
                .map { it.trim() }
                .filter { it.length > 5 && !it.startsWith("=") && !it.contains("licence", ignoreCase = true) && !it.contains("cookies", ignoreCase = true) }
                .distinct()

            val book1 = "LIVRE I: BIOLOGIE & MORPHOLOGIE"
            val book2 = "LIVRE II: SYMBIOSE & COMPAGNONNAGE"
            val book3 = "LIVRE III: PATHOLOGIE & PHYTOSANITAIRE"
            val book4 = "LIVRE IV: EDAPHOLOGIE & NUTRITION"

            val classified = mapOf(
                book1 to mutableListOf<String>(),
                book2 to mutableListOf<String>(),
                book3 to mutableListOf<String>(),
                book4 to mutableListOf<String>()
            )

            for (line in lines.take(20)) {
                val lower = line.lowercase()
                val targetBook = when {
                    lower.contains("maladie") || lower.contains("mildiou") || lower.contains("panne") || lower.contains("erreur") || lower.contains("bactér") || lower.contains("phytosanitaire") || lower.contains("fusariose") || lower.contains("diagnostic") -> book3
                    lower.contains("symbiose") || lower.contains("compagnon") || lower.contains("association") || lower.contains("hdmi") || lower.contains("recyclage") || lower.contains("earc") || lower.contains("partage") -> book2
                    lower.contains("sol") || lower.contains("eau") || lower.contains("nutri") || lower.contains("azote") || lower.contains("compost") || lower.contains("ph") || lower.contains("engrais") || lower.contains("batterie") || lower.contains("lame") || lower.contains("arrosage") -> book4
                    else -> book1
                }
                classified[targetBook]?.add(line)
            }

            return buildString {
                for (bookName in listOf(book1, book2, book3, book4)) {
                    val facts = classified[bookName]
                    if (!facts.isNullOrEmpty()) {
                        append(bookName).append("\n")
                        for (fact in facts.take(3)) {
                            append("• ").append(fact).append("\n")
                        }
                        append("\n")
                    }
                }
            }.trim()
        }
    }
}
