package be.heyman.android.etymoclan.data.gs1voc

/**
 * Modèles de données du vocabulaire GS1 Web Vocabulary (v1.16).
 * Ces structures représentent le "cadre de connaissance" qu'un membre du Clan
 * peut interroger pour savoir QUOI chercher et SOUS QUEL FORMAT le ranger.
 */

/** Une classe GS1 (ex: gs1:FruitsVegetables, gs1:Beverage, gs1:Product). */
data class VocClass(
    val uri: String,
    val curie: String,          // ex: "gs1:FruitsVegetables"
    val localName: String,      // ex: "FruitsVegetables"
    val label: String,          // ex: "Fruits and Vegetables"
    val comment: String?,       // description longue
    val subClassOf: List<String>,   // chaîne de super-classes (curies)
    val equivalentSchema: List<String>, // équivalences schema.org
    val isCodeList: Boolean,    // true si c'est une liste de valeurs énumérées
    val nValues: Int,           // nb de valeurs si code-list
    val termStatus: String      // "stable" | "deprecated" | ...
)

/** Une propriété GS1 (ex: gs1:netContent, gs1:bestBeforeDate). */
data class VocProperty(
    val uri: String,
    val curie: String,          // ex: "gs1:netContent"
    val localName: String,
    val label: String,          // ex: "Net content"
    val comment: String?,       // définition GS1 officielle
    val kind: String,           // "ObjectProperty" | "DatatypeProperty" | "Property"
    val domain: List<String>,   // classes auxquelles la propriété s'applique
    val range: List<String>,    // FORMAT/TYPE attendu (xsd:date, gs1:QuantitativeValue, code-list...)
    val subPropertyOf: List<String>,
    val equivalentSchema: List<String>,
    val termStatus: String,
    val isFunctional: Boolean,  // true = une seule valeur autorisée
    val theme: String? = null,
    val priorityRank: Int? = null
) {
    /** True si le range pointe vers une code-list (valeurs énumérées contrôlées). */
    val rangeIsCodeList: Boolean get() = range.any { it.startsWith("gs1:") && it.endsWith("Code") } ||
            range.any { it.startsWith("gs1:") && it.contains("Type") }

    /** Hint lisible pour guider la recherche de l'agent. */
    fun formatHint(): String = when {
        range.isEmpty() -> "texte libre"
        range.any { it == "xsd:date" } -> "une date (AAAA-MM-JJ)"
        range.any { it == "xsd:boolean" } -> "vrai/faux"
        range.any { it == "xsd:anyURI" } -> "une URL (lien vers une ressource)"
        range.any { it.contains("langString") || it == "xsd:string" } -> "du texte"
        range.any { it == "gs1:QuantitativeValue" } -> "une quantité + unité (ex: 750 + ml)"
        range.any { it.contains("MeasurementType") } -> "une mesure (valeur + unité)"
        rangeIsCodeList -> "une valeur d'une liste contrôlée (${range.joinToString()})"
        else -> "type: ${range.joinToString()}"
    }
}

/** Une valeur de code-list (ex: gs1:AllergenTypeCode-AM = "peanuts"). */
data class CodeValue(
    val uri: String,
    val curie: String,
    val localName: String,
    val label: String,
    val comment: String?,
    val codeListCurie: String,  // la code-list parente
    val originalCode: String?   // le code GS1 court (ex: "AM")
)
