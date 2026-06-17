package be.heyman.android.etymoclan.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log

class NfcReceiver(
    private val onNfcPayload: (String) -> Unit,
    private val onSendMessage: (String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            "be.heyman.android.etymoclan.SIMULATE_NFC" -> {
                val payload = intent.getStringExtra("payload") ?: "Type:Terreau;Origin:Sarcomusation;Lot:Broadcast"
                Log.i(TAG, "Simulation NFC reçue via Broadcast ADB : $payload")
                onNfcPayload(payload)
            }
            "be.heyman.android.etymoclan.SEND_MESSAGE" -> {
                val message = intent.getStringExtra("message") ?: ""
                Log.i(TAG, "Message reçu via Broadcast ADB : $message")
                onSendMessage(message)
            }
        }
    }

    companion object {
        private const val TAG = "MicroClan_NfcReceiver"

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction("be.heyman.android.etymoclan.SIMULATE_NFC")
                addAction("be.heyman.android.etymoclan.SEND_MESSAGE")
            }
        }

        fun parseNfcIntent(intent: Intent): String? {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag == null) return null

            val ndef = Ndef.get(tag) ?: return null
            try {
                ndef.connect()
                val msg = ndef.ndefMessage
                if (msg != null && msg.records.isNotEmpty()) {
                    val payload = msg.records[0].payload
                    val isUtf16 = (payload[0].toInt() and 0x80) != 0
                    val langLen = payload[0].toInt() and 0x3F
                    val text = String(
                        payload,
                        1 + langLen,
                        payload.size - 1 - langLen,
                        if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
                    ).trim()
                    Log.i(TAG, "Données NFC lues avec succès : '$text'")
                    return text
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la lecture du tag NFC NDEF", e)
            } finally {
                try { ndef.close() } catch (_: Exception) {}
            }
            return null
        }
    }
}
