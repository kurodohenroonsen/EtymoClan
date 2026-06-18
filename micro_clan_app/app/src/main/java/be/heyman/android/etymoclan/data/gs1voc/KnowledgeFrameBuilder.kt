package be.heyman.android.etymoclan.data.gs1voc

import be.heyman.android.etymoclan.crypto.Pollen

/**
 * Assemble le KnowledgeFrame d'un membre en croisant :
 *   - les propriétés GS1 applicables à sa classe (via Gs1VocRepository, héritage inclus)
 *   - les Pollens déjà produits par le membre (qui renseignent certains slots)
 *
 * Un slot est "rempli" dès qu'au moins un Pollen porte son prédicat.
 * Le statut (déclaré / corroboré / vérifié) dépend du nombre de SOURCES distinctes,
 * lues dans AITrace.inputs (URL, CID...) — c'est ton réseau de confiance natif.
 */
class KnowledgeFrameBuilder(private val repo: Gs1VocRepository) {

    /**
     * @param gs1Class    classe GS1 du membre (ex: "gs1:FruitsVegetables")
     * @param pollens     Pollens existants du membre
     * @param maxSlots    plafond de slots chargés (perf + UX) ; 0 = pas de filtre côté propriétés
     * @param themeFilter ne garder que ces thèmes (vide = tous)
     */
    fun build(
        gs1Class: String,
        pollens: List<Pollen>,
        maxSlots: Int = 240,
        themeFilter: Set<KnowledgeTheme> = emptySet()
    ): KnowledgeFrame {
        val cls = repo.getClass(gs1Class)
        val props = repo.getPropertiesForClass(gs1Class, includeInherited = true, limit = maxSlots)

        // Index des Pollens par prédicat (un slot peut avoir plusieurs Pollens)
        val byPredicate: Map<String, List<Pollen>> =
            pollens.filter { !it.body.predicate.isNullOrBlank() }
                .groupBy { it.body.predicate!! }

        val slots = props.mapNotNull { p ->
            val theme = KnowledgeTheme.forPredicate(p.curie)
            if (themeFilter.isNotEmpty() && theme !in themeFilter) return@mapNotNull null

            val related = byPredicate[p.curie].orEmpty()
            val sources = related
                .flatMap { it.trace?.inputs ?: emptyList() }
                .toSet()
            val status = SlotStatus.fromSources(sources.size)
            // Dernière valeur connue (Pollen le plus récent)
            val value = related.maxByOrNull { it.created }?.body?.value

            KnowledgeSlot(
                predicate = p.curie,
                label = p.label,
                formatHint = p.formatHint(),
                range = p.range,
                theme = theme,
                status = status,
                value = value,
                pollenIds = related.map { it.id },
                sourceCount = sources.size,
                isCodeList = p.rangeIsCodeList
            )
        }

        return KnowledgeFrame(
            gs1Class = gs1Class,
            gs1ClassLabel = cls?.label ?: gs1Class,
            slots = slots
        )
    }
}
