package be.heyman.android.etymoclan.data.gs1voc

object SlotValueValidator {
    // valide `value` extrait contre le range GS1 et, si code-list, contre les valeurs autorisées
    fun isValid(prop: VocProperty, value: String, allowedCodeValues: List<String>): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.equals("ABSENT", true)) return false
        return when {
            prop.range.any { it == "xsd:date" } ->
                Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(v)
            prop.range.any { it == "xsd:boolean" } ->
                v.lowercase() in setOf("true", "false", "vrai", "faux")
            prop.range.any { it == "xsd:anyURI" } -> v.startsWith("http")
            prop.rangeIsCodeList && allowedCodeValues.isNotEmpty() ->
                allowedCodeValues.any { v.contains(it.substringBefore(" ")) }
            prop.range.any { it == "gs1:QuantitativeValue" || it.contains("MeasurementType") } ->
                Regex("""\d""").containsMatchIn(v)
            else -> v.length in 1..500   // texte libre : borne raisonnable
        }
    }
}
