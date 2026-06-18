package be.heyman.android.etymoclan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import kotlin.coroutines.resume

object WebScraper {
    private const val TAG = "MicroClan_WebScraper"

    data class ScrapeResult(
        val extractedText: String,
        val screenshotPath: String,
        val detectedLanguage: String,
        val consentBlocked: Boolean = false,
        val sourceUrl: String = ""
    )

    suspend fun searchWeb(query: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, String>>()
        try {
            Log.d(TAG, "Recherche web pour : '$query'")
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // On tente d'abord DuckDuckGo HTML
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(8000)
                .get()
            
            val elements = doc.select(".result__body")
            for (element in elements.take(5)) {
                val titleEl = element.select(".result__a").first()
                if (titleEl != null) {
                    val linkUrl = titleEl.absUrl("href")
                    val cleanUrl = cleanDDGUrl(linkUrl)
                    if (cleanUrl.isNotEmpty() && !cleanUrl.contains("duckduckgo.com")) {
                        results.add(Pair(titleEl.text(), cleanUrl))
                    }
                }
            }
            
            // Si pas de résultats avec DDG, on essaie Google
            if (results.isEmpty()) {
                Log.d(TAG, "DDG vide, tentative avec Google...")
                val googleUrl = "https://www.google.com/search?q=$encodedQuery"
                val googleDoc = Jsoup.connect(googleUrl)
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(8000)
                    .get()
                
                val searchResults = googleDoc.select("div.g")
                for (res in searchResults.take(5)) {
                    val linkEl = res.select("a[href]").first()
                    val titleEl = res.select("h3").first()
                    if (linkEl != null && titleEl != null) {
                        val cleanUrl = linkEl.attr("href")
                        if (cleanUrl.startsWith("http") && !cleanUrl.contains("google.com")) {
                            results.add(Pair(titleEl.text(), cleanUrl))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur pendant la recherche web :", e)
        }
        results
    }

    suspend fun fetchPageText(context: Context, url: String): String {
        return try {
            val result = scrapeAndOcrPage(context, url)
            // Return text with language prefix to help Gemma make sense of it
            "Language: ${result.detectedLanguage}\nScreenshot: ${result.screenshotPath}\n\n${result.extractedText}"
        } catch (e: Exception) {
            Log.e(TAG, "WebView Scraping & OCR failed, falling back to Jsoup", e)
            fetchPageTextJsoup(url)
        }
    }

    suspend fun scrapeAndOcrPage(
        context: Context,
        url: String,
        runModelInference: (suspend (String, String) -> String)? = null
    ): ScrapeResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = WebView(context)
            webView.layout(0, 0, 1080, 1920) // Set initial layout size so WebView renders correctly from the start
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

            val mainHandler = Handler(Looper.getMainLooper())
            var hasResumed = false
            var timeoutRunnable: Runnable? = null

            fun resumeOnce(result: Result<ScrapeResult>) {
                if (!hasResumed) {
                    hasResumed = true
                    val runnable = timeoutRunnable
                    if (runnable != null) {
                        mainHandler.removeCallbacks(runnable)
                    }
                    mainHandler.post {
                        try {
                            webView.destroy()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error destroying WebView", e)
                        }
                    }
                    continuation.resumeWith(result)
                }
            }

            val runnable = Runnable {
                Log.w(TAG, "WebView timeout of 30 seconds reached for: $url")
                resumeOnce(Result.failure(java.util.concurrent.TimeoutException("Scrape timeout reached for $url")))
            }
            timeoutRunnable = runnable

            mainHandler.postDelayed(runnable, 30000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, urlStr: String?) {
                    if (urlStr != null && urlStr.contains("google.com/sorry")) {
                        Log.i(TAG, "CAPTCHA Google détecté dans le WebView. Demande de résolution à l'utilisateur...")
                        GemmaTamagotchiEngine.getInstance()?.showCaptchaDialog(
                            webView = webView,
                            onDone = {
                                view?.reload()
                            },
                            onCancel = {
                                resumeOnce(Result.failure(Exception("CAPTCHA non résolu par l'utilisateur")))
                            }
                        )
                        return
                    }

                    GemmaTamagotchiEngine.getInstance()?.hideCaptchaDialog()

                    fun proceedWithCapture() {
                        if (hasResumed) return
                        val finalUrl = webView.url ?: url
                        try {
                            val jsQuery = """
                                (function() {
                                    try {
                                        return JSON.stringify({
                                            height: Math.max(
                                                document.body ? document.body.scrollHeight : 0,
                                                document.documentElement ? document.documentElement.scrollHeight : 0,
                                                document.body ? document.body.offsetHeight : 0,
                                                document.documentElement ? document.documentElement.offsetHeight : 0,
                                                document.body ? document.body.clientHeight : 0,
                                                document.documentElement ? document.documentElement.clientHeight : 0
                                            ),
                                            lang: document.documentElement ? document.documentElement.lang : "",
                                            title: document.title || "",
                                            text: document.body ? document.body.innerText.substring(0, 8000) : "",
                                            html: document.documentElement ? document.documentElement.outerHTML.substring(0, 200000) : ""
                                        });
                                    } catch(e) {
                                        return JSON.stringify({ height: 800, lang: "", title: "", text: "", html: "" });
                                    }
                                })()
                            """.trimIndent()

                            webView.evaluateJavascript(jsQuery) { jsResult ->
                                try {
                                    var heightDp = 800
                                    var htmlLang = ""
                                    var title = ""
                                    var sampleText = ""
                                    var pageHtml = ""

                                    if (jsResult != null && jsResult != "null") {
                                        try {
                                            val unescaped = org.json.JSONTokener(jsResult).nextValue() as String
                                            val json = org.json.JSONObject(unescaped)
                                            heightDp = json.optInt("height", 800)
                                            htmlLang = json.optString("lang", "").lowercase()
                                            title = json.optString("title", "")
                                            sampleText = json.optString("text", "")
                                            pageHtml = json.optString("html", "")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Error decoding JS evaluation result", e)
                                        }
                                    }

                                    val density = context.resources.displayMetrics.density
                                    val contentWidth = 1080
                                    val contentHeight = (heightDp * density).toInt()
                                    Log.d(TAG, "WebView dimensions from JS: ${contentWidth}x${contentHeight} (heightDp=$heightDp)")

                                    // Set limits to prevent OOM
                                    val finalHeight = contentHeight.coerceAtMost(12000).coerceAtLeast(800)
                                    webView.layout(0, 0, contentWidth, finalHeight)

                                    // Wait 500ms for WebView to finish the layout pass and render the page before capture
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (hasResumed) return@postDelayed
                                        try {
                                            val bitmap = Bitmap.createBitmap(contentWidth, finalHeight, Bitmap.Config.ARGB_8888)
                                            val canvas = Canvas(bitmap)
                                            webView.draw(canvas)

                                            // Save screenshot to external directory
                                            val screenshotDir = File(context.getExternalFilesDir(null), "screenshots")
                                            if (!screenshotDir.exists()) {
                                                screenshotDir.mkdirs()
                                            }
                                            val screenshotFile = File(screenshotDir, "screenshot_${System.currentTimeMillis()}.png")
                                            FileOutputStream(screenshotFile).use { out ->
                                                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                                            }
                                            Log.d(TAG, "Screenshot saved successfully to: ${screenshotFile.absolutePath}")

                                            // Perform OCR and Language ID on a background thread
                                            Thread {
                                                try {
                                                    val inputImage = InputImage.fromBitmap(bitmap, 0)

                                                    var detectedLang = ""

                                                    if (detectedLang.isEmpty()) {
                                                        if (htmlLang.startsWith("ja")) {
                                                            detectedLang = "ja"
                                                        } else if (htmlLang.startsWith("zh")) {
                                                            detectedLang = "zh"
                                                        } else if (htmlLang.startsWith("ko")) {
                                                            detectedLang = "ko"
                                                        } else if (htmlLang.isNotEmpty()) {
                                                            detectedLang = htmlLang.substring(0, 2)
                                                        }
                                                    }

                                                    if (detectedLang == "en" || detectedLang.isEmpty() || detectedLang == "un") {
                                                        if (sampleText.isNotEmpty()) {
                                                            val languageIdentifier = LanguageIdentification.getClient()
                                                            val task = languageIdentifier.identifyLanguage(sampleText)
                                                            val langResult = Tasks.await(task)
                                                            if (langResult != "und" && langResult.isNotEmpty()) {
                                                                detectedLang = langResult
                                                            }
                                                        }
                                                    }

                                                    if (detectedLang.isEmpty()) {
                                                        detectedLang = "en"
                                                    }

                                                    Log.i(TAG, "Detected language: $detectedLang")

                                                    val recognizer = when (detectedLang) {
                                                        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                                                        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                                                        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                                                        "hi", "ne", "mr" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                                                        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                                    }

                                                    val ocrTask = recognizer.process(inputImage)
                                                    val ocrResult = Tasks.await(ocrTask)
                                                    val fullText = ocrResult.text
                                                    Log.d(TAG, "OCR complete. Text length: ${fullText.length}")

                                                    val rawText = if (fullText.trim().isNotEmpty()) fullText else sampleText
                                                    val isConsent = isConsentWall(finalUrl, rawText, pageHtml)
                                                    val cleanedText = if (isConsent) "" else cleanWebpageText(rawText)

                                                    if (isConsent) {
                                                        Log.w(TAG, "CONSENT WALL détecté — l'OCR ne contient PAS le vrai texte AI Mode. URL=$finalUrl")
                                                        val dbgDir = File(context.getExternalFilesDir(null), "debug_consent")
                                                        dbgDir.mkdirs()
                                                        val dbgFile = File(dbgDir, "consent_${System.currentTimeMillis()}.html")
                                                        dbgFile.writeText(pageHtml)
                                                        Log.w(TAG, "HTML de consentement dumpé : ${dbgFile.absolutePath} (${pageHtml.length} chars)")
                                                        // logcat tronque ~4000 chars/ligne -> on logge par tranches (6 max)
                                                        pageHtml.chunked(3500).take(6).forEachIndexed { i, c -> Log.w(TAG, "CONSENT_HTML[$i]: $c") }
                                                    }

                                                    resumeOnce(Result.success(ScrapeResult(
                                                        extractedText = cleanedText,
                                                        screenshotPath = screenshotFile.absolutePath,
                                                        detectedLanguage = detectedLang,
                                                        consentBlocked = isConsent,
                                                        sourceUrl = url
                                                    )))
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "OCR/LanguageID background task failed", e)
                                                    resumeOnce(Result.failure(e))
                                                }
                                            }.start()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error drawing screenshot", e)
                                            resumeOnce(Result.failure(e))
                                        }
                                    }, 500)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing JS evaluation result", e)
                                    resumeOnce(Result.failure(e))
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "WebView rendering screenshot failed", e)
                            resumeOnce(Result.failure(e))
                        }
                    }

                    val urlStrStr = urlStr ?: ""
                    if (urlStrStr.contains("udm=50")) {
                        val startTime = System.currentTimeMillis()
                        // Stabilisation check for AI mode (Gemini streaming output)
                        // Interval of 600ms is used because it corresponds to the typical pause between Gemini streaming chunks.
                        fun checkStabilization(lastLength: Int) {
                            if (hasResumed) return
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= 4000L) { // Plafond de 4s
                                Log.d(TAG, "Plafond de stabilisation de 4s atteint pour udm=50. Capture en cours.")
                                proceedWithCapture()
                                return
                            }
                            webView.evaluateJavascript("(function() { return document.body ? document.body.innerText.length : 0; })()") { resStr ->
                                if (hasResumed) return@evaluateJavascript
                                val currentLength = resStr?.toIntOrNull() ?: 0
                                if (lastLength >= 0 && currentLength == lastLength) {
                                    Log.d(TAG, "Stabilisation de document.body.innerText.length atteinte à $currentLength. Capture en cours.")
                                    proceedWithCapture()
                                } else {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        checkStabilization(currentLength)
                                    }, 600L) // Intervalle de ~600 ms entre deux mesures
                                }
                            }
                        }
                        checkStabilization(-1)
                    } else {
                        val delayMs = if (urlStrStr.contains("google.com/search")) 6000L else 2000L
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (hasResumed) return@postDelayed
                            proceedWithCapture()
                        }, delayMs)
                    }

                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "WebViewClient error $errorCode: $description for $failingUrl")
                }
            }

            Log.i(TAG, "Loading URL in WebView: $url")
            webView.loadUrl(url)
        }
    }

    private suspend fun fetchPageTextJsoup(url: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fallback: Extraction of text from Jsoup for URL : $url")
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(8000)
                .followRedirects(true)
                .get()
            
            val title = doc.title()
            val paragraphs = doc.select("p, h1, h2, h3, article")
            val bodyText = paragraphs.joinToString("\n") { it.text() }
            
            val fullContent = "Titre: $title\n\n$bodyText"
            val cleaned = cleanWebpageText(fullContent)
            if (cleaned.length > 3000) {
                cleaned.substring(0, 3000) + "..."
            } else {
                cleaned
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de l'extraction fallback JSoup $url :", e)
            "Erreur d'extraction fallback de la page : ${e.message}"
        }
    }

    fun cleanWebpageText(text: String): String {
        val lower = text.lowercase()
        val errorSignatures = listOf(
            "resource limit is reached",
            "exceeded resource limit",
            "temporarily unable to service your request",
            "403 forbidden",
            "access denied",
            "cloudflare",
            "checking your browser",
            "verify you are human",
            "ddos protection",
            "404 not found",
            "502 bad gateway",
            "503 service unavailable",
            "504 gateway timeout",
            "captcha",
            "robot check"
        )
        for (sig in errorSignatures) {
            if (lower.contains(sig)) {
                Log.w(TAG, "Page bloquée ou erreur de ressource détectée: '$sig'")
                return "Erreur : L'accès à la page web a échoué ou a été bloqué par le serveur distant (ex: Limite de ressources ou Cloudflare)."
            }
        }

        val lines = text.split("\n")
        val cleanLines = lines.map { it.trim() }.filter { line ->
            if (line.isEmpty()) return@filter false
            
            // Filter out navigation, generic layout noise and buttons
            val lowerLine = line.lowercase()
            if (line.length < 20) {
                val isNoiseWord = lowerLine in listOf(
                    "menu", "navigation", "panier", "cart", "connexion", "login", "s'inscrire", "register",
                    "home", "accueil", "recherche", "search", "contact", "contactez-nous", "contact us",
                    "privacy", "cookies", "mentions légales", "copyright", "droits réservés", "share",
                    "partager", "tweeter", "facebook", "newsletter", "s'abonner", "instagram", "youtube",
                    "twitter", "linkedin", "suivant", "précédent", "next", "prev", "close", "fermer",
                    "loading", "chargement", "tous droits réservés", "all rights reserved"
                )
                if (isNoiseWord) return@filter false
            }
            
            val isConsentOrGeneric = lowerLine.contains("accepter les cookies") ||
                    lowerLine.contains("consentement") ||
                    lowerLine.contains("politique de cookies") ||
                    lowerLine.contains("privacy policy") ||
                    lowerLine.contains("se connecter") ||
                    lowerLine.contains("créer un compte") ||
                    lowerLine.contains("passer au contenu") ||
                    lowerLine.contains("skip to content")
            
            !isConsentOrGeneric
        }

        return cleanLines.joinToString("\n")
    }

    private fun cleanDDGUrl(url: String): String {
        if (url.contains("uddg=")) {
            try {
                val parts = url.split("uddg=")
                if (parts.size > 1) {
                    val rawUrl = parts[1].split("&").first()
                    return java.net.URLDecoder.decode(rawUrl, "UTF-8")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erreur décodage URL DDG : $url", e)
            }
        }
        return url
    }

    suspend fun harvest(context: Context, query: String): ScrapeResult {
        Log.d(TAG, "Harvesting for query: '$query'")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val aiModeUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=fr&gl=BE"
        
        var result = try {
            scrapeAndOcrPage(context, aiModeUrl)
        } catch (e: Exception) {
            Log.e(TAG, "AI Mode search failed, trying fallback", e)
            null
        }

        val textLength = result?.extractedText?.length ?: 0
        if (result == null || result.consentBlocked || textLength < 40) {
            Log.w(TAG, "AI Mode search result was null, consentBlocked, or insufficient (length=$textLength). Falling back to normal search...")
            val links = searchWeb(query)
            if (links.isNotEmpty()) {
                val firstLink = links.first().second
                Log.d(TAG, "Harvesting fallback: scraping first link: $firstLink")
                try {
                    result = scrapeAndOcrPage(context, firstLink)
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback scraping failed for URL: $firstLink", e)
                }
            } else {
                Log.e(TAG, "No fallback links found for query: '$query'")
            }
        }
        
        return result ?: ScrapeResult("", "", "en", sourceUrl = aiModeUrl)
    }

    private fun isConsentWall(finalUrl: String?, ocrText: String, html: String): Boolean {
        val u = (finalUrl ?: "").lowercase()
        if (u.contains("consent.google.") || u.contains("/consent")) return true
        val needles = listOf(
            "avant d'accéder à google", "voordat je verdergaat", "tout accepter",
            "alles accepteren", "accept all", "reject all", "before you continue",
            "we use cookies", "nous utilisons des cookies", "gérer les cookies"
        )
        val hay = (ocrText + " " + html).lowercase()
        return needles.any { hay.contains(it) }
    }

    // TODO: quand isConsentWall est vrai, injecter du JS pour cliquer "Tout accepter"
    // (multi-sélecteurs à déterminer en analysant les fichiers debug_consent/*.html :
    //  button[aria-label*=accept], form[action*=consent] button, etc.),
    // attendre le re-render, puis relancer capture+OCR.
    // fun dismissConsentAndRetry(webView: WebView, onDone: () -> Unit) { /* à venir */ }
}

