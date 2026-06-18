package be.heyman.android.etymoclan.data.gs1voc

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Boîte à outils GS1 exposée à l'agent local (Gemma via LiteRT-LM).
 *
 * Donne au membre du Clan la capacité d'interroger le référentiel GS1 officiel
 * pour savoir QUOI documenter sur lui-même et SOUS QUEL FORMAT.
 *
 * Branchement dans GemmaTamagotchiEngine, à côté de ClanMemberToolSet :
 *
 *   val gs1 = Gs1VocToolSet(context)
 *   conversation = engine.createConversation(
 *       ConversationConfig(tools = listOf(tool(ClanMemberToolSet(member.id)), tool(gs1)))
 *   )
 *
 * Toutes les méthodes renvoient du texte compact, optimisé pour la fenêtre de contexte.
 */
class Gs1VocToolSet(context: Context) : ToolSet {

    private val repo = Gs1VocRepository.get(context.applicationContext)

    @Tool(description = "Trouve la ou les classes GS1 correspondant à un type de produit (ex: 'tomate', 'jus de fruit', 'téléviseur', 'chaussure'). À appeler EN PREMIER pour rattacher un membre du Clan à sa catégorie GS1 officielle.")
    fun gs1FindClassForProduct(
        @ToolParam(description = "Mot-clé décrivant le produit, en anglais de préférence (ex: 'fruit vegetable', 'beverage', 'footwear').") keyword: String
    ): String {
        val hits = repo.suggestClasses(keyword, 5)
        if (hits.isEmpty()) return "Aucune classe GS1 trouvée pour '$keyword'. Essaie un terme plus générique en anglais (ex: 'product', 'food', 'beverage')."
        return buildString {
            appendLine("Classes GS1 candidates pour '$keyword' :")
            hits.forEach { c ->
                append("• ${c.curie}  «${c.label}»")
                if (c.subClassOf.isNotEmpty()) append("  (sous-classe de ${c.subClassOf.first()})")
                appendLine()
            }
            appendLine("→ Choisis la plus précise, puis appelle gs1ListProperties pour connaître ce qu'il faut documenter.")
        }
    }

    @Tool(description = "Liste les propriétés (champs d'information) qu'on peut/doit documenter pour une classe GS1 donnée, héritage inclus. C'est le 'cadre de recherche' : ce que l'agent doit aller chercher sur le web. Utilise 'filter' pour cibler un thème (ex: 'nutrition', 'allergen', 'packaging', 'date').")
    fun gs1ListProperties(
        @ToolParam(description = "La classe GS1 (ex: 'gs1:FruitsVegetables').") gs1Class: String,
        @ToolParam(description = "Filtre optionnel sur le nom de la propriété (ex: 'nutrition', 'allergen', 'origin', 'date', 'weight'). Vide = toutes.") filter: String,
        @ToolParam(description = "Nombre max de résultats (recommandé: 15 à 25 pour ne pas saturer).") limit: Int
    ): String {
        val total = repo.countPropertiesForClass(gs1Class)
        if (total == 0) return "Classe '$gs1Class' inconnue ou sans propriété. Vérifie via gs1FindClassForProduct."
        val lim = if (limit <= 0) 20 else limit.coerceAtMost(40)
        val props = repo.getPropertiesForClass(gs1Class, true, filter.ifBlank { null }, lim)
        return buildString {
            val scope = if (filter.isBlank()) "toutes" else "filtre='$filter'"
            appendLine("Propriétés GS1 pour $gs1Class ($scope) — $total au total, ${props.size} affichées :")
            props.forEach { p ->
                append("• ${p.curie}  «${p.label}»  → ${p.formatHint()}")
                if (p.isFunctional) append("  [valeur unique]")
                appendLine()
            }
            appendLine("→ Pour les détails/définition d'une propriété : gs1DescribeProperty. Pour les valeurs autorisées d'une liste : gs1CodeValues.")
        }
    }

    @Tool(description = "Donne la définition officielle GS1 d'une propriété, son format/type attendu (range), et l'équivalent schema.org s'il existe. À appeler avant de remplir un champ pour savoir EXACTEMENT quoi produire.")
    fun gs1DescribeProperty(
        @ToolParam(description = "La propriété GS1 (ex: 'gs1:netContent', 'gs1:bestBeforeDate').") property: String
    ): String {
        val p = repo.getProperty(property) ?: return "Propriété '$property' introuvable."
        return buildString {
            appendLine("${p.curie} — «${p.label}»")
            p.comment?.let { appendLine("Définition GS1 : ${it.trim()}") }
            appendLine("Type attendu (range) : ${if (p.range.isEmpty()) "texte libre" else p.range.joinToString()}")
            appendLine("Format à produire : ${p.formatHint()}")
            if (p.equivalentSchema.isNotEmpty()) appendLine("Équivalent schema.org : ${p.equivalentSchema.joinToString()}")
            if (p.rangeIsCodeList) appendLine("⚠ Valeur CONTRÔLÉE : appelle gs1CodeValues(\"${p.range.first()}\") pour la liste autorisée.")
            if (p.isFunctional) appendLine("Cardinalité : une seule valeur autorisée.")
        }
    }

    @Tool(description = "Liste les valeurs autorisées d'une code-list GS1 (vocabulaire contrôlé). À utiliser quand une propriété attend une valeur énumérée (ex: type d'allergène, matériau d'emballage). Permet à l'agent de choisir une valeur officielle au lieu d'inventer.")
    fun gs1CodeValues(
        @ToolParam(description = "La code-list GS1 (ex: 'gs1:AllergenTypeCode', 'gs1:PackagingMaterialTypeCode').") codeList: String,
        @ToolParam(description = "Filtre optionnel pour chercher une valeur précise (ex: 'peanut', 'glass'). Vide = début de liste.") search: String,
        @ToolParam(description = "Nombre max de valeurs (recommandé: 15).") limit: Int
    ): String {
        val lim = if (limit <= 0) 15 else limit.coerceAtMost(40)
        val vals = repo.getCodeListValues(codeList, lim, search.ifBlank { null })
        if (vals.isEmpty()) return "Aucune valeur pour '$codeList'" + (if (search.isNotBlank()) " correspondant à '$search'." else ". Vérifie le nom via gs1ListCodeLists.")
        return buildString {
            appendLine("Valeurs autorisées de $codeList :")
            vals.forEach { v -> appendLine("• ${v.curie}${v.originalCode?.let { " (code: $it)" } ?: ""}  «${v.label}»") }
            appendLine("→ Utilise l'URI complète (${vals.first().curie}) comme valeur du Pollen.")
        }
    }

    @Tool(description = "Recherche transversale dans tout le vocabulaire GS1 (classes, propriétés, valeurs) par mot-clé. Utile quand l'agent ne sait pas où ranger une information trouvée sur le web.")
    fun gs1Search(
        @ToolParam(description = "Mot(s)-clé(s), anglais de préférence (ex: 'expiry date', 'country origin', 'organic').") keyword: String
    ): String {
        val hits = repo.search(keyword, 12)
        if (hits.isEmpty()) return "Rien trouvé pour '$keyword' dans le vocabulaire GS1."
        return buildString {
            appendLine("Résultats GS1 pour '$keyword' :")
            hits.forEach { h -> appendLine("• [${h.kind}] ${h.curie}  «${h.label}»") }
        }
    }

    @Tool(description = "Liste les principales code-lists (vocabulaires contrôlés) disponibles dans GS1, triées par richesse. Vue d'ensemble des dimensions énumérables.")
    fun gs1ListCodeLists(
        @ToolParam(description = "Nombre max (recommandé: 15).") limit: Int
    ): String {
        val lim = if (limit <= 0) 15 else limit.coerceAtMost(40)
        val lists = repo.listCodeLists(lim)
        return buildString {
            appendLine("Code-lists GS1 (valeurs contrôlées) :")
            lists.forEach { c -> appendLine("• ${c.curie}  «${c.label}»  — ${c.nValues} valeurs") }
        }
    }
}
