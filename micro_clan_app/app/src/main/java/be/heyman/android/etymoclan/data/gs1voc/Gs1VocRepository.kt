package be.heyman.android.etymoclan.data.gs1voc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Accès en lecture seule à la base GS1 Web Vocabulary (gs1voc.db, ~3.3 Mo).
 *
 * La DB est livrée pré-construite dans app/src/main/assets/gs1voc.db.
 * Au premier lancement elle est copiée dans filesDir (SQLiteDatabase ne peut pas
 * ouvrir un fichier directement depuis les assets).
 *
 * C'est le "cerveau encyclopédique GS1" que l'agent local interroge par tool-calling.
 */
class Gs1VocRepository private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val TAG = "Gs1VocRepository"
        private const val ASSET_NAME = "gs1voc.db"
        @Volatile private var INSTANCE: Gs1VocRepository? = null

        fun get(context: Context): Gs1VocRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(context: Context): Gs1VocRepository {
            val out = File(context.filesDir, ASSET_NAME)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open(ASSET_NAME).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                Log.i(TAG, "gs1voc.db copiée dans ${out.absolutePath} (${out.length()} octets)")
            }
            val db = SQLiteDatabase.openDatabase(out.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            return Gs1VocRepository(db)
        }

        /** Nettoie une requête FTS5 libre venue de l'agent (anti-injection + tolérance). */
        private fun sanitizeFts(raw: String): String {
            val terms = raw.lowercase()
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
                .trim().split(Regex("\\s+"))
                .filter { it.length >= 2 }
            if (terms.isEmpty()) return "\"\""
            // OR entre les termes, chacun en préfixe pour la tolérance
            return terms.joinToString(" OR ") { "\"$it\"*" }
        }

        private fun splitList(s: String?): List<String> =
            s?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    // ---------------------------------------------------------------------
    // 0. Obtenir toutes les classes de type produit (gs1:Product et ses sous-classes directes/indirectes)
    // ---------------------------------------------------------------------
    fun getProductClasses(): List<VocClass> {
        val out = mutableListOf<VocClass>()
        try {
            db.rawQuery(
                """
                WITH RECURSIVE sub(curie) AS (
                  SELECT 'gs1:Product'
                  UNION
                  SELECT c.curie
                  FROM classes c JOIN sub ON 
                    (','||IFNULL(c.sub_class_of,'')||',') LIKE ('%,'||sub.curie||',%')
                )
                SELECT DISTINCT c.* 
                FROM classes c JOIN sub ON c.curie = sub.curie
                WHERE c.is_code_list = 0
                ORDER BY c.label
                """, null
            ).use { while (it.moveToNext()) out.add(readClass(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la récupération des classes de produits :", e)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 1. Trouver la bonne classe GS1 pour un produit (mapping archétype)
    // ---------------------------------------------------------------------
    fun suggestClasses(keyword: String, limit: Int = 5): List<VocClass> {
        val out = mutableListOf<VocClass>()
        try {
            val q = sanitizeFts(keyword)
            db.rawQuery(
                """SELECT c.* FROM search_fts f JOIN classes c ON c.uri=f.uri
                   WHERE f.kind='class' AND search_fts MATCH ? LIMIT ?""",
                arrayOf(q, limit.toString())
            ).use { while (it.moveToNext()) out.add(readClass(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 MATCH a échoué dans suggestClasses, fallback vers LIKE : ${e.message}")
            val likePattern = "%$keyword%"
            db.rawQuery(
                """SELECT * FROM classes 
                   WHERE is_code_list=0 AND (label LIKE ? OR curie LIKE ? OR comment LIKE ?) 
                   LIMIT ?""",
                arrayOf(likePattern, likePattern, likePattern, limit.toString())
            ).use { while (it.moveToNext()) out.add(readClass(it)) }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 2. Détail d'une classe
    // ---------------------------------------------------------------------
    fun getClass(curie: String): VocClass? {
        db.rawQuery("SELECT * FROM classes WHERE curie=?", arrayOf(curie)).use {
            return if (it.moveToFirst()) readClass(it) else null
        }
    }

    // ---------------------------------------------------------------------
    // 3. Propriétés applicables à une classe (héritage transitif via subClassOf)
    //    -> "tout ce qui peut être connu sur moi"
    // ---------------------------------------------------------------------
    fun getPropertiesForClass(
        curie: String,
        includeInherited: Boolean = true,
        filter: String? = null,
        limit: Int = 60,
        offset: Int = 0
    ): List<VocProperty> {
        val out = mutableListOf<VocProperty>()
        val filterClause = if (!filter.isNullOrBlank())
            "AND (p.label LIKE '%' || ? || '%' OR p.curie LIKE '%' || ? || '%')" else ""

        val sql = if (includeInherited) {
            """
            WITH RECURSIVE anc(curie, distance) AS (
              SELECT ?, 0
              UNION
              SELECT TRIM(j.value), anc.distance + 1
              FROM classes c JOIN anc ON c.curie = anc.curie
              JOIN json_each('["'||REPLACE(c.sub_class_of,',','","')||'"]') j
              WHERE IFNULL(c.sub_class_of,'') != ''
            )
            SELECT p.*, MIN(anc.distance) as min_distance 
            FROM properties p JOIN anc ON (','||IFNULL(p.domain,'')||',') LIKE ('%,'||anc.curie||',%')
            WHERE p.term_status != 'deprecated' $filterClause
            GROUP BY p.uri
            ORDER BY min_distance ASC, p.priority_rank ASC, p.label ASC 
            LIMIT ? OFFSET ?
            """
        } else {
            """
            SELECT p.*, 0 as min_distance FROM properties p
            WHERE (','||IFNULL(p.domain,'')||',') LIKE ('%,'||?||',%')
              AND p.term_status != 'deprecated' $filterClause
            ORDER BY p.priority_rank ASC, p.label ASC LIMIT ? OFFSET ?
            """
        }
        val args = mutableListOf(curie)
        if (!filter.isNullOrBlank()) { args.add(filter); args.add(filter) }
        args.add(limit.toString()); args.add(offset.toString())

        db.rawQuery(sql, args.toTypedArray()).use {
            while (it.moveToNext()) out.add(readProperty(it))
        }
        return out
    }

    /** Compte total de propriétés (pour pagination / % de complétion). */
    fun countPropertiesForClass(curie: String): Int {
        db.rawQuery(
            """
            WITH RECURSIVE anc(curie, distance) AS (
              SELECT ?, 0
              UNION SELECT TRIM(j.value), anc.distance + 1 FROM classes c JOIN anc ON c.curie=anc.curie
              JOIN json_each('["'||REPLACE(c.sub_class_of,',','","')||'"]') j
              WHERE IFNULL(c.sub_class_of,'')!=''
            )
            SELECT COUNT(DISTINCT p.uri) FROM properties p JOIN anc ON
              (','||IFNULL(p.domain,'')||',') LIKE ('%,'||anc.curie||',%')
            WHERE p.term_status != 'deprecated'
            """, arrayOf(curie)
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ---------------------------------------------------------------------
    // 4. Détail d'une propriété (format/range = ce que l'agent doit produire)
    // ---------------------------------------------------------------------
    fun getProperty(curie: String): VocProperty? {
        db.rawQuery("SELECT * FROM properties WHERE curie=?", arrayOf(curie)).use {
            return if (it.moveToFirst()) readProperty(it) else null
        }
    }

    // ---------------------------------------------------------------------
    // 5. Valeurs d'une code-list (vocabulaire contrôlé)
    // ---------------------------------------------------------------------
    fun getCodeListValues(codeListCurie: String, limit: Int = 50, search: String? = null): List<CodeValue> {
        val out = mutableListOf<CodeValue>()
        val (clause, args) = if (!search.isNullOrBlank())
            "AND label LIKE '%' || ? || '%'" to arrayOf(codeListCurie, search, limit.toString())
        else "" to arrayOf(codeListCurie, limit.toString())
        db.rawQuery(
            "SELECT * FROM code_values WHERE code_list_curie=? $clause ORDER BY label LIMIT ?",
            args
        ).use { while (it.moveToNext()) out.add(readCodeValue(it)) }
        return out
    }

    // ---------------------------------------------------------------------
    // 6. Recherche plein-texte transversale (classes + propriétés + valeurs)
    // ---------------------------------------------------------------------
    data class SearchHit(val curie: String, val kind: String, val label: String, val snippet: String)
    fun search(keyword: String, limit: Int = 12): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        try {
            val q = sanitizeFts(keyword)
            db.rawQuery(
                """SELECT curie, kind, label,
                          snippet(search_fts, 4, '[', ']', '…', 8) AS snip
                   FROM search_fts WHERE search_fts MATCH ? LIMIT ?""",
                arrayOf(q, limit.toString())
            ).use {
                while (it.moveToNext())
                    out.add(SearchHit(it.getString(0), it.getString(1), it.getString(2), it.getString(3) ?: ""))
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 MATCH a échoué dans search, fallback vers LIKE : ${e.message}")
            val likePattern = "%$keyword%"
            // Recherche dans les classes
            db.rawQuery(
                "SELECT curie, 'class' as kind, label, comment FROM classes WHERE label LIKE ? OR curie LIKE ? LIMIT ?",
                arrayOf(likePattern, likePattern, limit.toString())
            ).use {
                while (it.moveToNext()) {
                    val comment = it.getString(3) ?: ""
                    val snippet = if (comment.length > 60) comment.take(60) + "..." else comment
                    out.add(SearchHit(it.getString(0), it.getString(1), it.getString(2) ?: "", snippet))
                }
            }
            // Recherche dans les propriétés si limite non atteinte
            if (out.size < limit) {
                val rem = limit - out.size
                db.rawQuery(
                    "SELECT curie, 'property' as kind, label, comment FROM properties WHERE label LIKE ? OR curie LIKE ? LIMIT ?",
                    arrayOf(likePattern, likePattern, rem.toString())
                ).use {
                    while (it.moveToNext()) {
                        val comment = it.getString(3) ?: ""
                        val snippet = if (comment.length > 60) comment.take(60) + "..." else comment
                        out.add(SearchHit(it.getString(0), it.getString(1), it.getString(2) ?: "", snippet))
                    }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------
    // 7. Lister les code-lists disponibles
    // ---------------------------------------------------------------------
    fun listCodeLists(limit: Int = 80): List<VocClass> {
        val out = mutableListOf<VocClass>()
        db.rawQuery(
            "SELECT * FROM classes WHERE is_code_list=1 ORDER BY n_values DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { while (it.moveToNext()) out.add(readClass(it)) }
        return out
    }

    // ---------------------------- mappers ----------------------------------
    private fun readClass(c: android.database.Cursor) = VocClass(
        uri = c.getString(c.getColumnIndexOrThrow("uri")),
        curie = c.getString(c.getColumnIndexOrThrow("curie")),
        localName = c.getString(c.getColumnIndexOrThrow("local_name")),
        label = c.getString(c.getColumnIndexOrThrow("label")) ?: "",
        comment = c.getString(c.getColumnIndexOrThrow("comment")),
        subClassOf = splitList(c.getString(c.getColumnIndexOrThrow("sub_class_of"))),
        equivalentSchema = splitList(c.getString(c.getColumnIndexOrThrow("equivalent_schema"))),
        isCodeList = c.getInt(c.getColumnIndexOrThrow("is_code_list")) == 1,
        nValues = c.getInt(c.getColumnIndexOrThrow("n_values")),
        termStatus = c.getString(c.getColumnIndexOrThrow("term_status")) ?: "stable"
    )

    private fun readProperty(c: android.database.Cursor) = VocProperty(
        uri = c.getString(c.getColumnIndexOrThrow("uri")),
        curie = c.getString(c.getColumnIndexOrThrow("curie")),
        localName = c.getString(c.getColumnIndexOrThrow("local_name")),
        label = c.getString(c.getColumnIndexOrThrow("label")) ?: "",
        comment = c.getString(c.getColumnIndexOrThrow("comment")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")) ?: "Property",
        domain = splitList(c.getString(c.getColumnIndexOrThrow("domain"))),
        range = splitList(c.getString(c.getColumnIndexOrThrow("range"))),
        subPropertyOf = splitList(c.getString(c.getColumnIndexOrThrow("sub_property_of"))),
        equivalentSchema = splitList(c.getString(c.getColumnIndexOrThrow("equivalent_schema"))),
        termStatus = c.getString(c.getColumnIndexOrThrow("term_status")) ?: "stable",
        isFunctional = c.getInt(c.getColumnIndexOrThrow("is_functional")) == 1,
        theme = try { c.getString(c.getColumnIndexOrThrow("theme")) } catch (_: Exception) { null },
        priorityRank = try {
            val idx = c.getColumnIndexOrThrow("priority_rank")
            if (c.isNull(idx)) null else c.getInt(idx)
        } catch (_: Exception) { null }
    )

    private fun readCodeValue(c: android.database.Cursor) = CodeValue(
        uri = c.getString(c.getColumnIndexOrThrow("uri")),
        curie = c.getString(c.getColumnIndexOrThrow("curie")),
        localName = c.getString(c.getColumnIndexOrThrow("local_name")),
        label = c.getString(c.getColumnIndexOrThrow("label")) ?: "",
        comment = c.getString(c.getColumnIndexOrThrow("comment")),
        codeListCurie = c.getString(c.getColumnIndexOrThrow("code_list_curie")),
        originalCode = c.getString(c.getColumnIndexOrThrow("original_code"))
    )
}
