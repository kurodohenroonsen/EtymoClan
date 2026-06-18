package be.heyman.android.etymoclan.data.gs1voc

import androidx.compose.ui.graphics.Color

/**
 * Le "cadre de connaissance" d'un membre du Clan : l'ensemble des champs GS1
 * qu'il PEUT documenter sur lui-même, leur statut de remplissage, et la valeur
 * actuelle s'il y en a une.
 *
 * C'est la structure qui pilote à la fois la boucle d'enrichissement (l'agenda
 * de recherche de l'agent) ET l'affichage du graphe (MemberKnowledgeGraphScreen).
 */

/** Statut de confiance d'un slot, dérivé du nombre de sources indépendantes. */
enum class SlotStatus(val labelFr: String) {
    EMPTY("à chercher"),        // aucun Pollen
    CLAIMED("déclaré"),         // 1 source
    CORROBORATED("corroboré"),  // 2 sources indépendantes
    VERIFIED("vérifié");        // 3+ sources OU validation manuelle

    val color: Color get() = when (this) {
        EMPTY -> Color(0xFF888780)        // gris
        CLAIMED -> Color(0xFFBA7517)      // ambre
        CORROBORATED -> Color(0xFF378ADD) // bleu
        VERIFIED -> Color(0xFF639922)     // vert
    }

    companion object {
        fun fromSources(n: Int, manuallyValidated: Boolean = false): SlotStatus = when {
            manuallyValidated -> VERIFIED
            n >= 3 -> VERIFIED
            n == 2 -> CORROBORATED
            n == 1 -> CLAIMED
            else -> EMPTY
        }
    }
}

/** Un champ documentable = une propriété GS1 + son état de remplissage. */
data class KnowledgeSlot(
    val predicate: String,          // ex: "gs1:bestBeforeDate"
    val label: String,              // ex: "Best before date"
    val formatHint: String,         // ex: "une date (AAAA-MM-JJ)"
    val range: List<String>,        // type(s) attendu(s)
    val theme: KnowledgeTheme,
    val status: SlotStatus = SlotStatus.EMPTY,
    val value: String? = null,      // valeur remplie (objet du triplet)
    val pollenIds: List<String> = emptyList(), // Pollens qui renseignent ce slot
    val sourceCount: Int = 0,       // nb de sources distinctes (via AITrace.inputs)
    val isCodeList: Boolean = false, // valeur contrôlée ?
    val priorityRank: Int? = null
) {
    val isFilled: Boolean get() = status != SlotStatus.EMPTY
}

/** Regroupement thématique des slots (pour l'affichage en clusters). */
enum class KnowledgeTheme(val labelFr: String, val keywords: List<String>) {
    IDENTITY("Identité", listOf("name", "description", "brand", "label", "image", "gtin", "productName")),
    ORIGIN("Origine", listOf("country", "origin", "provenance", "region", "harvest", "farm", "assembly")),
    NUTRITION("Nutrition", listOf("nutrient", "energy", "fat", "protein", "carbohydrate", "sugar", "salt", "fibre", "calorie", "vitamin", "mineral", "serving", "diet")),
    ALLERGENS("Allergènes", listOf("allergen")),
    PACKAGING("Emballage", listOf("packaging", "package", "material", "recycl", "weight", "netContent", "netWeight", "dimension")),
    DATES("Dates", listOf("date", "expiry", "bestBefore", "useBy", "production", "durability")),
    OTHER("Autres", emptyList());

    companion object {
        fun fromDbLabel(s: String?): KnowledgeTheme = when (s?.lowercase()) {
            "identity" -> IDENTITY
            "origin" -> ORIGIN
            "nutrition" -> NUTRITION
            "allergens" -> ALLERGENS
            "packaging" -> PACKAGING
            "dates" -> DATES
            else -> OTHER
        }

        /** Classe une propriété dans un thème par mot-clé (heuristique). */
        fun forPredicate(curie: String): KnowledgeTheme {
            val name = curie.substringAfter(":").lowercase()
            // ordre important : ALLERGENS avant NUTRITION (allergen contient "nutri"? non, mais on garde explicite)
            for (t in listOf(IDENTITY, ALLERGENS, NUTRITION, DATES, ORIGIN, PACKAGING)) {
                if (t.keywords.any { name.contains(it.lowercase()) }) return t
            }
            return OTHER
        }
    }
}

/** Le cadre complet d'un membre. */
data class KnowledgeFrame(
    val gs1Class: String,           // ex: "gs1:FruitsVegetables"
    val gs1ClassLabel: String,      // ex: "Fruits and Vegetables"
    val slots: List<KnowledgeSlot>
) {
    val totalSlots: Int get() = slots.size
    val filledSlots: Int get() = slots.count { it.isFilled }
    val completionPercent: Int get() = if (totalSlots == 0) 0 else (filledSlots * 100) / totalSlots

    /** Slots groupés par thème, dans l'ordre de l'enum (pour les clusters du graphe). */
    val byTheme: Map<KnowledgeTheme, List<KnowledgeSlot>>
        get() = slots.groupBy { it.theme }
            .toSortedMap(compareBy { it.ordinal })

    /** Statut agrégé d'un thème = le plus faible parmi ses slots remplis, EMPTY si rien. */
    fun statusOf(theme: KnowledgeTheme): SlotStatus {
        val s = byTheme[theme].orEmpty()
        val filled = s.filter { it.isFilled }
        if (filled.isEmpty()) return SlotStatus.EMPTY
        return filled.minByOrNull { it.status.ordinal }?.status ?: SlotStatus.EMPTY
    }

    /** Le prochain slot à remplir (priorité : thèmes prioritaires d'abord). */
    fun nextEmptySlot(excludePredicates: Set<String> = emptySet()): KnowledgeSlot? =
        slots.filter { !it.isFilled && it.predicate !in excludePredicates }
             .minWithOrNull(compareBy(
                 { it.priorityRank ?: Int.MAX_VALUE },
                 { it.theme.ordinal },
                 { it.label }))
}
