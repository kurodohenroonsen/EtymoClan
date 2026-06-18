package be.heyman.android.etymoclan.agentcore

import be.heyman.android.etymoclan.crypto.Pollen
import be.heyman.android.etymoclan.crypto.PollenFactory
import be.heyman.android.etymoclan.data.gs1voc.Gs1VocRepository
import be.heyman.android.etymoclan.data.gs1voc.KnowledgeFrame
import be.heyman.android.etymoclan.data.gs1voc.KnowledgeSlot
import be.heyman.android.etymoclan.data.gs1voc.SlotValueValidator

/**
 * Remplace l'ancien `enrichProfile` (un Pollen fourre-tout) par une COMPLÉTION
 * structurée : un slot vide à la fois → un Pollen atomique (= un triplet RDF signé).
 *
 * Le cycle :
 *   1. choisir le prochain slot vide (KnowledgeFrame.nextEmptySlot)
 *   2. lire le format attendu (Gs1VocRepository.getProperty -> range/formatHint)
 *   3. si valeur contrôlée -> lister les valeurs autorisées (anti-hallucination)
 *   4. recherche web + scrape CIBLÉS sur CE champ (hooks vers l'engine existant)
 *   5. extraire la valeur via Gemma, contrainte par le format
 *   6. émettre un Pollen atomique avec predicate + AITrace (provenance)
 *
 * Les accès "monde extérieur" (web, LLM) passent par EnrichmentHooks pour rester
 * découplé : branche-les sur GemmaTamagotchiEngine (webSearch / visitPage / summarize).
 */
class KnowledgeQuest(
    private val repo: Gs1VocRepository,
    private val hooks: EnrichmentHooks
) {
    /** Résultat d'une tentative de remplissage de slot. */
    sealed class Result {
        data class Filled(val pollen: Pollen, val slot: KnowledgeSlot) : Result()
        data class NotFound(val slot: KnowledgeSlot) : Result()
        object FrameComplete : Result()
    }

    /**
     * Remplit le prochain slot vide du cadre. À appeler en boucle (1 tick = 1 slot)
     * pour que l'évolution du Tamagotchi soit progressive et lisible.
     */
    suspend fun fillNextSlot(
        memberIu: String,
        memberDid: String,
        privateKey: java.security.PrivateKey,
        frame: KnowledgeFrame,
        productName: String,
        excludePredicates: Set<String> = emptySet(),
        nfcPayload: String = ""
    ): Result {
        val slot = frame.nextEmptySlot(excludePredicates) ?: return Result.FrameComplete
        return fillSpecificSlot(memberIu, memberDid, privateKey, frame, productName, slot.predicate, nfcPayload)
    }

    private fun getPropertyFromAdditionalProperties(json: org.json.JSONObject, keyWord: String): String? {
        val addProps = json.optJSONArray("additionalProperty") ?: return null
        for (i in 0 until addProps.length()) {
            val prop = addProps.getJSONObject(i)
            val pName = prop.optString("name", "")
            val pVal = prop.optString("value", "")
            if (pName.contains(keyWord, ignoreCase = true) && pVal.isNotEmpty()) {
                return pVal
            }
        }
        return null
    }

    private fun getValueFromJsonLd(json: org.json.JSONObject, label: String, predicate: String): String? {
        val cleanLabel = label.lowercase().trim()
        val cleanPred = predicate.lowercase().trim()
        
        if (cleanLabel.contains("gtin") || cleanPred.contains("gtin") || cleanLabel.contains("product id") || cleanPred.contains("productid")) {
            val gtin = json.optString("gtin").takeIf { it.isNotEmpty() } 
                ?: json.optString("identifier").substringAfterLast(":").takeIf { it.isNotEmpty() }
            if (!gtin.isNullOrEmpty()) return gtin
        }
        if (cleanLabel == "product description" || cleanPred.contains("productdescription") || cleanLabel.contains("description")) {
            val desc = json.optString("description")
            if (desc.isNotEmpty()) return desc
        }
        if (cleanLabel == "product name" || cleanPred.contains("productname") || cleanLabel == "name") {
            val name = json.optString("name")
            if (name.isNotEmpty()) return name
        }
        if (cleanLabel.contains("brand") || cleanPred.contains("brand")) {
            val man = json.optJSONObject("manufacturer")
            val manName = man?.optString("name")
            if (!manName.isNullOrEmpty()) return manName
            val brand = json.optJSONObject("brand")
            val brandName = brand?.optString("name")
            if (!brandName.isNullOrEmpty()) return brandName
            val name = json.optString("name")
            if (name.isNotEmpty()) return name
        }
        if (cleanLabel == "functional name" || cleanPred.contains("functionalname")) {
            val cat = json.optString("category")
            if (cat.isNotEmpty()) {
                return cat.substringAfter("/").trim()
            }
            return "Bière"
        }
        if (cleanLabel.contains("production date") || cleanPred.contains("productiondate")) {
            val pd = json.optString("productionDate")
            if (pd.isNotEmpty()) return pd
        }
        if (cleanLabel.contains("manufacturer") || cleanPred.contains("manufacturer")) {
            val man = json.optJSONObject("manufacturer")
            if (man != null) {
                val name = man.optString("name")
                if (name.isNotEmpty()) return name
            }
        }
        if (cleanLabel.contains("price") || cleanPred.contains("price")) {
            val offers = json.optJSONObject("offers")
            if (offers != null) {
                val price = offers.optString("price")
                if (price.isNotEmpty()) return price
            }
        }
        
        if (cleanLabel.contains("allergen") || cleanPred.contains("allergen")) {
            if (cleanLabel.contains("containment") || cleanPred.contains("containment")) {
                val ingredients = getPropertyFromAdditionalProperties(json, "ingrédient")
                return if (ingredients != null && (ingredients.contains("orge", ignoreCase = true) || ingredients.contains("malt", ignoreCase = true))) {
                    "CONTAINS"
                } else {
                    "FREE_FROM"
                }
            }
            val ingredients = getPropertyFromAdditionalProperties(json, "ingrédient")
            if (ingredients != null) return ingredients
        }

        val addProps = json.optJSONArray("additionalProperty")
        if (addProps != null) {
            for (i in 0 until addProps.length()) {
                val prop = addProps.getJSONObject(i)
                val pName = prop.optString("name", "")
                val pVal = prop.optString("value", "")
                val match = when {
                    cleanLabel.contains("alcohol") || cleanPred.contains("alcohol") -> pName.contains("alcool", ignoreCase = true)
                    cleanLabel.contains("net content") || cleanLabel.contains("volume") || cleanPred.contains("netcontent") -> pName.contains("volume", ignoreCase = true) || pName.contains("contenant", ignoreCase = true)
                    cleanLabel.contains("batch") || cleanLabel.contains("lot") || cleanPred.contains("batch") || cleanPred.contains("lot") -> pName.contains("lot", ignoreCase = true)
                    cleanLabel.contains("best before") || cleanLabel.contains("expiration") || cleanLabel.contains("durabilité") || cleanPred.contains("bestbefore") || cleanPred.contains("expiration") -> pName.contains("durabilité", ignoreCase = true) || pName.contains("expiration", ignoreCase = true)
                    cleanLabel.contains("ingredient") || cleanPred.contains("ingredient") -> pName.contains("ingrédient", ignoreCase = true)
                    cleanLabel.contains("service temperature") || cleanPred.contains("service") -> pName.contains("température", ignoreCase = true)
                    cleanLabel.contains("variety") || cleanPred.contains("variety") || cleanLabel.contains("variant") || cleanPred.contains("variant") -> pName.contains("variété", ignoreCase = true)
                    cleanLabel.contains("organic") || cleanPred.contains("organic") -> pName.contains("biologique", ignoreCase = true) || pName.contains("organic", ignoreCase = true)
                    pName.contains(label, ignoreCase = true) || label.contains(pName, ignoreCase = true) -> true
                    else -> false
                }
                if (match && pVal.isNotEmpty()) {
                    return pVal
                }
            }
        }
        return null
    }

    suspend fun fillSpecificSlot(
        memberIu: String,
        memberDid: String,
        privateKey: java.security.PrivateKey,
        frame: KnowledgeFrame,
        productName: String,
        slotPredicate: String,
        nfcPayload: String = ""
    ): Result {
        val slot = frame.slots.find { it.predicate == slotPredicate } ?: return Result.FrameComplete

        // 2. Format attendu (autorité GS1)
        val prop = repo.getProperty(slot.predicate)
        val formatHint = prop?.formatHint() ?: slot.formatHint

        // 3. Valeurs autorisées si code-list
        val allowedValues: List<String> = if (slot.isCodeList && prop != null) {
            val codeListCurie = prop.range.firstOrNull { it.startsWith("gs1:") } ?: ""
            repo.getCodeListValues(codeListCurie, limit = 40)
                .map { "${it.curie} (${it.label})" }
        } else emptyList()

        // 1. Try to extract value from nfcPayload first (ground truth)
        // Skip price and makesOffer as they are signed at scan time by onNfcPayloadReceived
        if (slot.predicate != "gs1:price" && slot.predicate != "gs1:makesOffer" && nfcPayload.isNotEmpty() && nfcPayload.trim().startsWith("{")) {
            try {
                val json = org.json.JSONObject(nfcPayload)
                val rawVal = getValueFromJsonLd(json, slot.label, slot.predicate)
                if (rawVal != null) {
                    val cleanVal = rawVal.trim()
                    var mappedVal: String? = null
                    if (allowedValues.isNotEmpty()) {
                        val matchedCurie = allowedValues.find { curieAndLabel ->
                            val curiePart = curieAndLabel.substringBefore(" ").lowercase()
                            val labelPart = curieAndLabel.substringAfter(" ").lowercase()
                            cleanVal.contains(curiePart) || cleanVal.contains(labelPart) ||
                            (curiePart.contains("barley") && (cleanVal.contains("orge") || cleanVal.contains("malt"))) ||
                            (curiePart.contains("gluten") && cleanVal.contains("gluten")) ||
                            (curiePart.contains("contains") && cleanVal.contains("contains")) ||
                            (curiePart.contains("free_from") && cleanVal.contains("free from")) ||
                            (curiePart.contains("may_contain") && cleanVal.contains("may contain"))
                        }
                        if (matchedCurie != null) {
                            mappedVal = matchedCurie.substringBefore(" ")
                        }
                    } else {
                        if (slot.predicate.contains("percentageOfAlcoholByVolume")) {
                            val match = Regex("""\d+(\.\d+)?""").find(cleanVal)
                            if (match != null) mappedVal = match.value
                        } else {
                            mappedVal = cleanVal
                        }
                    }
                    if (mappedVal != null && mappedVal.isNotEmpty() && (prop == null || SlotValueValidator.isValid(prop, mappedVal, allowedValues))) {
                        android.util.Log.i("KnowledgeQuest", "Direct extraction succeeded from NFC payload for ${slot.label}: $mappedVal")
                        val traceInputs = listOf("urn:cid:nfc_payload")
                        val pollen = PollenFactory.createAndSignPollen(
                            targetIu = memberIu,
                            motivation = "enriching",
                            bodyType = "StructuredFact",
                            bodyValue = mappedVal,
                            predicate = slot.predicate,
                            creatorDid = memberDid,
                            privateKey = privateKey,
                            traceInputs = traceInputs,
                            tracePrompt = "Direct extraction from scanned NFC JSON-LD",
                            traceDurationMs = 0,
                            traceModel = "NFC-Scanner"
                        )
                        return Result.Filled(pollen, slot)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("KnowledgeQuest", "Error parsing NFC payload for direct slot extraction", e)
            }
        }

        // 4. Recherche web ciblée SUR CE CHAMP
        val query = "$productName ${slot.label}"
        val evidence = hooks.searchAndScrape(query) ?: return Result.NotFound(slot)

        // 5. Extraction contrainte par le format
        val extractionPrompt = buildString {
            appendLine("Extrais UNIQUEMENT la valeur de \"${slot.label}\" pour le produit \"$productName\".")
            appendLine("Format attendu : $formatHint.")
            if (allowedValues.isNotEmpty()) {
                appendLine("Choisis EXACTEMENT une des valeurs autorisées ci-dessous (réponds avec l'URI gs1:) :")
                allowedValues.take(40).forEach { appendLine("- $it") }
            }
            appendLine("Si l'information est absente, réponds exactement: ABSENT.")
            appendLine("Source analysée :")
            appendLine(evidence.text.take(4000))
        }
        val start = System.currentTimeMillis()
        val extracted = hooks.extractWithLlm(extractionPrompt).trim()
        val durationMs = System.currentTimeMillis() - start

        if (prop == null || !SlotValueValidator.isValid(prop, extracted, allowedValues)) {
            return Result.NotFound(slot)
        }

        // 6. Pollen atomique = triplet signé + provenance (Trace de Genèse)
        val traceInputs = buildList {
            evidence.sourceUrl?.let { add("url:$it") }
            evidence.screenshotCid?.let { add("file:$it") }
            evidence.ocrScreenshotCid?.let { add("file:$it") }
            if (isEmpty()) add("urn:cid:source_web_scraping")
        }
        val pollen = PollenFactory.createAndSignPollen(
            targetIu = memberIu,
            motivation = "enriching",
            bodyType = "StructuredFact",
            bodyValue = extracted,
            predicate = slot.predicate,             // <-- le triplet
            creatorDid = memberDid,
            privateKey = privateKey,
            traceInputs = traceInputs,
            tracePrompt = extractionPrompt,
            traceDurationMs = durationMs,
            traceModel = hooks.modelId
        )
        return Result.Filled(pollen, slot)
    }
}

/**
 * Pont vers le monde extérieur (à implémenter dans GemmaTamagotchiEngine).
 * Garde KnowledgeQuest testable et indépendant de l'UI / du réseau.
 */
interface EnrichmentHooks {
    val modelId: String
    /** Recherche web + visite + screenshot ; renvoie la preuve, ou null si rien. */
    suspend fun searchAndScrape(query: String): Evidence?
    /** Appelle Gemma (LiteRT-LM) pour extraire la valeur depuis le prompt fourni. */
    suspend fun extractWithLlm(prompt: String): String

    data class Evidence(
        val text: String,
        val sourceUrl: String?,
        val screenshotCid: String?,   // CID du screenshot (preuve visuelle ancrée)
        val ocrScreenshotCid: String? = null // CID du screenshot OCR textuel
    )
}
