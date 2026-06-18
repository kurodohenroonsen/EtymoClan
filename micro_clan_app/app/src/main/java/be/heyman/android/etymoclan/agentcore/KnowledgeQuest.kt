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
        excludePredicates: Set<String> = emptySet()
    ): Result {
        val slot = frame.nextEmptySlot(excludePredicates) ?: return Result.FrameComplete
        return fillSpecificSlot(memberIu, memberDid, privateKey, frame, productName, slot.predicate)
    }

    suspend fun fillSpecificSlot(
        memberIu: String,
        memberDid: String,
        privateKey: java.security.PrivateKey,
        frame: KnowledgeFrame,
        productName: String,
        slotPredicate: String
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
