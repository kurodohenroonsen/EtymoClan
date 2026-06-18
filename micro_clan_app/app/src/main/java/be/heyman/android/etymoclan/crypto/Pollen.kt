package be.heyman.android.etymoclan.crypto

import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * VERSION PATCHÉE — ajoute le champ `predicate` au PollenBody.
 *
 * Un Pollen devient ainsi un TRIPLET RDF signé :
 *     target (sujet) + body.predicate + body.value (objet)
 * ex: urn:icd:0160:444... | gs1:bestBeforeDate | "2026-07-01"
 *
 * RÉTRO-COMPATIBLE : si predicate == null, le JSON canonique est identique à l'ancien
 * (body = {type, value}), donc les Pollens déjà signés gardent le même hash/urn:cid.
 */

data class Pollen(
    val context: String = "https://etimologiae.org/contexts/pollen/v1.jsonld",
    val id: String,
    val type: String = "Annotation",
    val motivation: String,
    val creator: String,
    val created: String,
    val target: String,
    val body: PollenBody,
    val trace: AITrace? = null,
    val proof: DataIntegrityProof? = null
)

data class PollenBody(
    val type: String,               // "Fact", "SoilAnalysis", "StructuredFact"...
    val value: String,              // l'objet du triplet (valeur ou URI)
    val predicate: String? = null   // NOUVEAU : le prédicat GS1/schema.org, ex "gs1:netContent"
)

data class AITrace(
    val type: String = "AITrace",
    val inputs: List<String>,
    val prompt: String,
    val durationMs: Long,
    val model: String
)

data class DataIntegrityProof(
    val type: String = "DataIntegrityProof",
    val cryptosuite: String = "ecdsa-jcs-2019",
    val proofPurpose: String = "assertionMethod",
    val proofValue: String
)

object CryptoManager {
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun getDidForKey(publicKey: java.security.PublicKey): String {
        val encoded = publicKey.encoded
        val base64 = Base64.encodeToString(encoded, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        return "did:key:$base64"
    }

    fun signData(privateKey: java.security.PrivateKey, dataToSign: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(dataToSign)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun verifySignatureWithDid(did: String, data: ByteArray, signatureStr: String): Boolean {
        return try {
            if (!did.startsWith("did:key:")) return false
            val base64Key = did.substring("did:key:".length)
            val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(publicKey)
            s.update(data)
            s.verify(Base64.decode(signatureStr, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
        } catch (e: Exception) { false }
    }
}

object PollenFactory {
    fun createAndSignPollen(
        targetIu: String,
        motivation: String,
        bodyType: String,
        bodyValue: String,
        creatorDid: String,
        privateKey: java.security.PrivateKey,
        predicate: String? = null,            // NOUVEAU
        traceInputs: List<String> = emptyList(),
        tracePrompt: String = "",
        traceDurationMs: Long = 0,
        traceModel: String = ""
    ): Pollen {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val createdStr = sdf.format(Date())

        val canonicalJson = getCanonicalJson(
            motivation = motivation, creator = creatorDid, created = createdStr,
            target = targetIu, bodyType = bodyType, bodyValue = bodyValue,
            bodyPredicate = predicate,
            traceInputs = if (tracePrompt.isNotEmpty()) traceInputs else null,
            tracePrompt = if (tracePrompt.isNotEmpty()) tracePrompt else null,
            traceDuration = if (tracePrompt.isNotEmpty()) traceDurationMs else null,
            traceModel = if (tracePrompt.isNotEmpty()) traceModel else null
        )

        val hashBytes = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
        val id = "urn:cid:" + hashBytes.joinToString("") { "%02x".format(it) }

        val signatureStr = CryptoManager.signData(privateKey, canonicalJson.toByteArray(Charsets.UTF_8))
        val traceObj = if (tracePrompt.isNotEmpty())
            AITrace(inputs = traceInputs, prompt = tracePrompt, durationMs = traceDurationMs, model = traceModel)
        else null

        return Pollen(
            id = id, motivation = motivation, creator = creatorDid, created = createdStr,
            target = targetIu,
            body = PollenBody(type = bodyType, value = bodyValue, predicate = predicate),
            trace = traceObj,
            proof = DataIntegrityProof(proofValue = signatureStr)
        )
    }

    fun getCanonicalJson(
        motivation: String, creator: String, created: String, target: String,
        bodyType: String, bodyValue: String, bodyPredicate: String? = null,
        traceInputs: List<String>?, tracePrompt: String?,
        traceDuration: Long?, traceModel: String?
    ): String {
        val sortedMap = sortedMapOf<String, Any>()
        sortedMap["@context"] = "https://etimologiae.org/contexts/pollen/v1.jsonld"
        // body : on n'ajoute predicate QUE s'il existe -> rétro-compatibilité du hash
        val bodyMap = sortedMapOf<String, Any>("type" to bodyType, "value" to bodyValue)
        if (!bodyPredicate.isNullOrBlank()) bodyMap["predicate"] = bodyPredicate
        sortedMap["body"] = bodyMap
        sortedMap["created"] = created
        sortedMap["creator"] = creator
        sortedMap["motivation"] = motivation
        sortedMap["target"] = target
        if (traceInputs != null && tracePrompt != null && traceDuration != null && traceModel != null) {
            sortedMap["trace"] = sortedMapOf(
                "duration_ms" to traceDuration, "inputs" to traceInputs,
                "model" to traceModel, "prompt" to tracePrompt, "type" to "AITrace"
            )
        }
        sortedMap["type"] = "Annotation"
        return mapToJsonString(sortedMap)
    }

    private fun mapToJsonString(map: Map<String, Any>): String {
        val b = StringBuilder("{")
        val entries = map.entries.toList()
        for (i in entries.indices) {
            b.append("\"").append(entries[i].key).append("\":")
            b.append(valueToJsonString(entries[i].value))
            if (i < entries.size - 1) b.append(",")
        }
        return b.append("}").toString()
    }

    private fun valueToJsonString(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> "[" + value.joinToString(",") { valueToJsonString(it) } + "]"
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") mapToJsonString(value as Map<String, Any>)
        else -> "\"" + value.toString() + "\""
    }
}
