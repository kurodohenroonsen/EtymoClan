package be.heyman.android.etymoclan.data.gs1voc

import be.heyman.android.etymoclan.crypto.Pollen

/**
 * Agrège les Pollens atomiques d'un membre en UN document JSON-LD standard
 * (schema.org + GS1), consommable par un moteur de recherche ou un résolveur
 * GS1 Digital Link.
 *
 * Chaque fait reste vérifiable individuellement (le Pollen signé + sa Trace),
 * mais la projection donne la "fiche produit" lisible et interopérable.
 *
 * On ne garde que le Pollen le plus récent par prédicat (last-write-wins).
 */
object PollenProjection {

    fun toJsonLd(
        memberIu: String,
        gs1Class: String,            // ex: "gs1:FruitsVegetables"
        pollens: List<Pollen>
    ): String {
        val latestByPredicate = pollens
            .filter { !it.body.predicate.isNullOrBlank() }
            .groupBy { it.body.predicate!! }
            .mapValues { (_, list) -> list.maxByOrNull { it.created }!! }

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"@context\": {\n")
        sb.append("    \"@vocab\": \"https://schema.org/\",\n")
        sb.append("    \"gs1\": \"https://ref.gs1.org/voc/\"\n")
        sb.append("  },\n")
        sb.append("  \"@type\": [\"Product\", \"$gs1Class\"],\n")
        sb.append("  \"@id\": \"${esc(memberIu)}\"")

        for ((predicate, pollen) in latestByPredicate) {
            sb.append(",\n  \"${esc(predicate)}\": ${renderValue(pollen.body.value)}")
        }
        sb.append("\n}")
        return sb.toString()
    }

    /** Si la valeur ressemble à une URI gs1: (code-list), on la rend telle quelle ; sinon string. */
    private fun renderValue(v: String): String {
        val t = v.trim()
        return if (t.startsWith("gs1:") || t.startsWith("http"))
            "{ \"@id\": \"${esc(t)}\" }"
        else "\"${esc(t)}\""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
