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

data class Pollen(
    val context: String = "https://etimologiae.org/contexts/pollen/v1.jsonld",
    val id: String, // urn:cid:hash
    val type: String = "Annotation",
    val motivation: String, // "evaluating", "analyzing", "enriching", "translating"
    val creator: String, // did:key:publickey
    val created: String, // ISO timestamp
    val target: String, // IU of the target
    val body: PollenBody,
    val trace: AITrace? = null,
    val proof: DataIntegrityProof? = null
)

data class PollenBody(
    val type: String, // "Fact", "SoilAnalysis", "IrrigationState", "EvolutionState"
    val value: String
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
    val proofValue: String // base64 signature of the JCS string
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
        val signatureBytes = s.sign()
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun verifySignatureWithDid(did: String, data: ByteArray, signatureStr: String): Boolean {
        return try {
            if (!did.startsWith("did:key:")) return false
            val base64Key = did.substring("did:key:".length)
            val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val x509KeySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val publicKey = keyFactory.generatePublic(x509KeySpec)
            
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(publicKey)
            s.update(data)
            val signatureBytes = Base64.decode(signatureStr, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            s.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
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
            motivation = motivation,
            creator = creatorDid,
            created = createdStr,
            target = targetIu,
            bodyType = bodyType,
            bodyValue = bodyValue,
            traceInputs = if (tracePrompt.isNotEmpty()) traceInputs else null,
            tracePrompt = if (tracePrompt.isNotEmpty()) tracePrompt else null,
            traceDuration = if (tracePrompt.isNotEmpty()) traceDurationMs else null,
            traceModel = if (tracePrompt.isNotEmpty()) traceModel else null
        )

        val hashBytes = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }
        val id = "urn:cid:$hashHex"

        val signatureStr = CryptoManager.signData(privateKey, canonicalJson.toByteArray(Charsets.UTF_8))
        val proof = DataIntegrityProof(proofValue = signatureStr)

        val traceObj = if (tracePrompt.isNotEmpty()) {
            AITrace(inputs = traceInputs, prompt = tracePrompt, durationMs = traceDurationMs, model = traceModel)
        } else null

        return Pollen(
            id = id,
            motivation = motivation,
            creator = creatorDid,
            created = createdStr,
            target = targetIu,
            body = PollenBody(type = bodyType, value = bodyValue),
            trace = traceObj,
            proof = proof
        )
    }

    fun getCanonicalJson(
        motivation: String,
        creator: String,
        created: String,
        target: String,
        bodyType: String,
        bodyValue: String,
        traceInputs: List<String>?,
        tracePrompt: String?,
        traceDuration: Long?,
        traceModel: String?
    ): String {
        val sortedMap = sortedMapOf<String, Any>()
        sortedMap["@context"] = "https://etimologiae.org/contexts/pollen/v1.jsonld"
        sortedMap["body"] = mapOf("type" to bodyType, "value" to bodyValue).toSortedMap()
        sortedMap["created"] = created
        sortedMap["creator"] = creator
        sortedMap["motivation"] = motivation
        sortedMap["target"] = target
        if (traceInputs != null && tracePrompt != null && traceDuration != null && traceModel != null) {
            sortedMap["trace"] = mapOf(
                "duration_ms" to traceDuration,
                "inputs" to traceInputs,
                "model" to traceModel,
                "prompt" to tracePrompt,
                "type" to "AITrace"
            ).toSortedMap()
        }
        sortedMap["type"] = "Annotation"
        return mapToJsonString(sortedMap)
    }

    private fun mapToJsonString(map: Map<String, Any>): String {
        val builder = StringBuilder()
        builder.append("{")
        val entries = map.entries.toList()
        for (i in entries.indices) {
            val entry = entries[i]
            builder.append("\"").append(entry.key).append("\":")
            builder.append(valueToJsonString(entry.value))
            if (i < entries.size - 1) {
                builder.append(",")
            }
        }
        builder.append("}")
        return builder.toString()
    }

    private fun valueToJsonString(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            is List<*> -> "[" + value.joinToString(",") { valueToJsonString(it) } + "]"
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJsonString(value as Map<String, Any>)
            }
            else -> "\"" + value.toString() + "\""
        }
    }
}
