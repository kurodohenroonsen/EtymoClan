package be.heyman.android.etymoclan.agentcore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.os.Build
import android.util.Log
import be.heyman.android.etymoclan.GemmaTamagotchiEngine
import be.heyman.android.etymoclan.LLMTruncateException
import be.heyman.android.etymoclan.cleanResponseAndDetectLoop
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator
import com.google.mediapipe.tasks.vision.imagegenerator.ImageGenerator.ImageGeneratorOptions

interface LocalModel {
    val name: String
    suspend fun generate(systemPrompt: String, userPrompt: String): String
}

interface LocalImageModel : LocalModel {
    suspend fun generateImage(prompt: String, outputPath: String): Boolean
}

class LiteRtModel(private val context: Context) : LocalModel {
    override val name: String = "Gemma-4-LiteRT"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String {
        val engine = GemmaTamagotchiEngine.getEngineInstance()
        if (engine == null) {
            Log.d("LiteRtModel", "Engine is null, running simulateSummary.")
            return GemmaTamagotchiEngine.simulateSummary(userPrompt)
        }
        return withContext(Dispatchers.IO) {
            try {
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(40, 0.95, 0.0, 0)
                )
                val conv = engine.createConversation(config)
                val responseBuilder = java.lang.StringBuilder()
                try {
                    conv.sendMessageAsync(userPrompt).collect { chunk ->
                        responseBuilder.append(chunk)
                        val partialText = responseBuilder.toString()
                        val (cleanedText, shouldTruncate) = cleanResponseAndDetectLoop(partialText)
                        if (shouldTruncate) {
                            responseBuilder.setLength(0)
                            responseBuilder.append(cleanedText)
                            throw LLMTruncateException()
                        }
                    }
                } catch (e: LLMTruncateException) {
                    Log.i("LiteRtModel", "Inférence : boucle ou garbage détecté, réponse tronquée.")
                } finally {
                    conv.close()
                }
                responseBuilder.toString()
            } catch (e: Exception) {
                Log.e("LiteRtModel", "Error in local LiteRT inference, falling back", e)
                GemmaTamagotchiEngine.simulateSummary(userPrompt)
            }
        }
    }
}

class MnnModel(override val name: String, private val modelPath: String) : LocalImageModel {
    private val TAG = "MnnModel"
    private var isJniAvailable = false

    init {
        try {
            System.loadLibrary("mnnllmapp")
            isJniAvailable = true
            Log.i(TAG, "libmnnllmapp loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libmnnllmapp.so not found, using programmatic canvas graphics fallback.")
        }
    }

    override suspend fun generate(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        if (isJniAvailable) {
            try {
                return@withContext executeMnnTextNative(systemPrompt, userPrompt)
            } catch (e: Exception) {
                Log.e(TAG, "Error running native MNN text inference", e)
            }
        }
        "MNN [Simulé] : Réponse textuelle pour '$userPrompt'"
    }

    override suspend fun generateImage(prompt: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Generating image for prompt: '$prompt' to $outputPath")
        if (isJniAvailable) {
            try {
                val success = executeMnnImageNative(prompt, outputPath)
                if (success) return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Error executing native MNN image generation", e)
            }
        }
        // Programmatic fallback: draw a highly-stylized cyber-gothic visual artifact
        try {
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Draw deep cyber-gothic background (Obsidian & Void black)
            canvas.drawColor(Color.parseColor("#070B12"))

            // Seed random dynamically based on prompt for deterministic variety
            val promptSeed = prompt.hashCode().toLong()
            val random = java.util.Random(promptSeed)

            val lowerPrompt = prompt.lowercase()
            when {
                lowerPrompt.contains("oeuf") || lowerPrompt.contains("egg") || lowerPrompt.contains("seed") || lowerPrompt.contains("graine") -> {
                    // EGG / SEED STAGE:
                    // 1. Deep Core Glow
                    val radialShader = RadialGradient(
                        256f, 256f, 220f,
                        intArrayOf(Color.parseColor("#25103F"), Color.parseColor("#070B12")),
                        floatArrayOf(0.3f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = radialShader
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(256f, 256f, 220f, paint)
                    paint.shader = null

                    // 2. HUD Concentric Circles and Radar sweeps
                    paint.color = Color.parseColor("#204F46E5") // semi-transparent indigo
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f
                    canvas.drawCircle(256f, 256f, 180f, paint)
                    canvas.drawCircle(256f, 256f, 140f, paint)
                    canvas.drawCircle(256f, 256f, 90f, paint)

                    // Draw angle marks on HUD
                    paint.color = Color.parseColor("#4006B6D4") // faint cyan
                    paint.strokeWidth = 1f
                    for (angle in 0 until 360 step 45) {
                        val rad = Math.toRadians(angle.toDouble())
                        val x1 = (256 + 180 * Math.cos(rad)).toFloat()
                        val y1 = (256 + 180 * Math.sin(rad)).toFloat()
                        val x2 = (256 + 195 * Math.cos(rad)).toFloat()
                        val y2 = (256 + 195 * Math.sin(rad)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }

                    // 3. Cyber Telemetry Overlay
                    paint.color = Color.parseColor("#4ade80") // light tech green
                    paint.textSize = 10f
                    paint.typeface = android.graphics.Typeface.MONOSPACE
                    paint.style = Paint.Style.FILL
                    canvas.drawText("SYS.LOC: LOCAL_INF", 24f, 36f, paint)
                    canvas.drawText("STAGE: L0/INIT", 24f, 50f, paint)
                    canvas.drawText("MODEL: MNN_SANA_PROC", 24f, 64f, paint)
                    canvas.drawText("CORE: 0x" + Integer.toHexString(random.nextInt(65536)).uppercase(), 24f, 78f, paint)

                    canvas.drawText("DEC_KEY: OK", 380f, 480f, paint)
                    canvas.drawText("ENTROPY: 0.124", 380f, 494f, paint)

                    // 4. Neon Circuit Board Traces
                    paint.color = Color.parseColor("#CC06B6D4") // Cyan neon
                    paint.strokeWidth = 2f
                    paint.style = Paint.Style.STROKE
                    for (i in 0 until 3) {
                        val startX = 50f + random.nextInt(400)
                        val startY = 50f + random.nextInt(400)
                        val path = Path().apply {
                            moveTo(startX, startY)
                            lineTo(startX + (random.nextInt(60) - 30), startY + (random.nextInt(60) - 30))
                            lineTo(startX + (random.nextInt(120) - 60), startY + (random.nextInt(120) - 60))
                        }
                        canvas.drawPath(path, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(startX, startY, 4f, paint)
                        paint.style = Paint.Style.STROKE
                    }

                    // 5. Metallic Egg with Rich Copper/Bronze Gradient
                    val eggRect = RectF(160f, 130f, 352f, 382f)
                    val eggShader = LinearGradient(
                        256f, 130f, 256f, 382f,
                        Color.parseColor("#FBBF24"), // Gold
                        Color.parseColor("#78350F"), // Dark Bronze
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = eggShader
                    paint.style = Paint.Style.FILL
                    canvas.drawOval(eggRect, paint)
                    paint.shader = null

                    // 6. Draw micro-circuit patterns on Egg Shell
                    paint.color = Color.parseColor("#9006B6D4") // Cyan neon
                    paint.strokeWidth = 2f
                    paint.style = Paint.Style.STROKE
                    val shellPath = Path().apply {
                        moveTo(256f, 130f)
                        lineTo(256f, 240f)
                        lineTo(210f, 280f)
                        lineTo(210f, 330f)
                        moveTo(256f, 240f)
                        lineTo(302f, 280f)
                        lineTo(302f, 330f)
                    }
                    canvas.drawPath(shellPath, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(256f, 240f, 7f, paint)

                    // 7. Species specific details on egg
                    if (lowerPrompt.contains("téléviseur") || lowerPrompt.contains("tv")) {
                        paint.color = Color.parseColor("#D946EF") // Fuchsia screen outline
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 3f
                        canvas.drawRect(210f, 270f, 302f, 330f, paint)
                        // Screen scanlines inside TV visor
                        paint.color = Color.parseColor("#40D946EF")
                        paint.strokeWidth = 1f
                        var lineY = 275f
                        while (lineY < 325f) {
                            canvas.drawLine(212f, lineY, 300f, lineY, paint)
                            lineY += 4f
                        }
                    } else if (lowerPrompt.contains("tomate") || lowerPrompt.contains("tomato")) {
                        paint.color = Color.parseColor("#10B981") // Emerald leaf sprout
                        paint.style = Paint.Style.FILL
                        val leafPath = Path().apply {
                            moveTo(256f, 130f)
                            cubicTo(210f, 80f, 210f, 60f, 256f, 60f)
                            cubicTo(302f, 60f, 302f, 80f, 256f, 130f)
                        }
                        canvas.drawPath(leafPath, paint)
                    } else if (lowerPrompt.contains("basilic")) {
                        paint.color = Color.parseColor("#34D399") // Mint green leaflets
                        paint.style = Paint.Style.FILL
                        val leaf1 = Path().apply {
                            moveTo(256f, 130f)
                            cubicTo(230f, 100f, 200f, 100f, 220f, 75f)
                            cubicTo(240f, 75f, 250f, 100f, 256f, 130f)
                        }
                        val leaf2 = Path().apply {
                            moveTo(256f, 130f)
                            cubicTo(282f, 100f, 312f, 100f, 292f, 75f)
                            cubicTo(272f, 75f, 262f, 100f, 256f, 130f)
                        }
                        canvas.drawPath(leaf1, paint)
                        canvas.drawPath(leaf2, paint)
                    }
                }
                lowerPrompt.contains("bébé") || lowerPrompt.contains("baby") || lowerPrompt.contains("sprout") || lowerPrompt.contains("pousse") -> {
                    // BABY / SPROUT STAGE:
                    // 1. Core deep space background glow
                    val radialShader2 = RadialGradient(
                        256f, 256f, 240f,
                        intArrayOf(Color.parseColor("#121829"), Color.parseColor("#070B12")),
                        floatArrayOf(0.4f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = radialShader2
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(256f, 256f, 240f, paint)
                    paint.shader = null

                    // 2. Telemetry texts
                    paint.color = Color.parseColor("#06B6D4") // cyan
                    paint.textSize = 10f
                    paint.typeface = android.graphics.Typeface.MONOSPACE
                    paint.style = Paint.Style.FILL
                    canvas.drawText("SYS.STATUS: ACTIVE_RUN", 24f, 36f, paint)
                    canvas.drawText("STAGE: L1/BABY", 24f, 50f, paint)
                    canvas.drawText("INF.RATE: 1.2 TF", 24f, 64f, paint)
                    canvas.drawText("SYS_ID: 0x" + Integer.toHexString(random.nextInt(65536)).uppercase(), 24f, 78f, paint)

                    // 3. Cybermatic Orbital Rings
                    paint.color = Color.parseColor("#3006B6D4")
                    paint.strokeWidth = 2f
                    paint.style = Paint.Style.STROKE
                    canvas.drawOval(RectF(80f, 160f, 432f, 352f), paint)
                    canvas.drawOval(RectF(110f, 140f, 402f, 372f), paint)

                    // 4. Species Sprout Body
                    if (lowerPrompt.contains("tomate") || lowerPrompt.contains("tomato")) {
                        // Cyber-Tomato: Red Sphere with Shading
                        val bodyShader = RadialGradient(
                            236f, 250f, 90f,
                            intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#7F1D1D")),
                            floatArrayOf(0.1f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = bodyShader
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 270f, 80f, paint)
                        paint.shader = null

                        // Cyber Visor / Eyes
                        paint.color = Color.parseColor("#06B6D4")
                        canvas.drawCircle(220f, 260f, 12f, paint)
                        canvas.drawCircle(292f, 260f, 12f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(217f, 257f, 4f, paint)
                        canvas.drawCircle(289f, 257f, 4f, paint)

                        // Digital leaf crown
                        paint.color = Color.parseColor("#22C55E")
                        paint.style = Paint.Style.FILL
                        val crown = Path().apply {
                            moveTo(256f, 190f)
                            lineTo(235f, 150f)
                            lineTo(250f, 170f)
                            lineTo(256f, 140f)
                            lineTo(262f, 170f)
                            lineTo(277f, 150f)
                            close()
                        }
                        canvas.drawPath(crown, paint)

                    } else if (lowerPrompt.contains("basilic")) {
                        // Basil baby: Emerald Green Core
                        val bodyShader = RadialGradient(
                            256f, 280f, 90f,
                            intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#064E3B")),
                            floatArrayOf(0.0f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = bodyShader
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 280f, 75f, paint)
                        paint.shader = null

                        // Golden lenses
                        paint.color = Color.parseColor("#FBBF24")
                        canvas.drawCircle(225f, 275f, 9f, paint)
                        canvas.drawCircle(287f, 275f, 9f, paint)

                        // Side neon leaves
                        paint.color = Color.parseColor("#34D399")
                        val leaves = Path().apply {
                            moveTo(210f, 250f)
                            cubicTo(160f, 200f, 160f, 170f, 220f, 220f)
                            moveTo(302f, 250f)
                            cubicTo(352f, 200f, 352f, 170f, 292f, 220f)
                        }
                        paint.strokeWidth = 3f
                        paint.style = Paint.Style.FILL
                        canvas.drawPath(leaves, paint)

                    } else {
                        // General companion (Terreau/etc.): Android Capsule
                        val capShader = LinearGradient(
                            256f, 180f, 256f, 360f,
                            Color.parseColor("#2563EB"),
                            Color.parseColor("#1E3A8A"),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = capShader
                        paint.style = Paint.Style.FILL
                        val capsule = RectF(190f, 180f, 322f, 360f)
                        canvas.drawRoundRect(capsule, 45f, 45f, paint)
                        paint.shader = null

                        // Visor
                        paint.color = Color.parseColor("#06B6D4")
                        canvas.drawRoundRect(RectF(210f, 225f, 302f, 260f), 10f, 10f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(232f, 242f, 4f, paint)
                        canvas.drawCircle(280f, 242f, 4f, paint)

                        // Digital antenna sprout
                        paint.color = Color.parseColor("#10B981")
                        paint.strokeWidth = 3f
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(256f, 180f, 256f, 130f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 130f, 7f, paint)
                    }
                }
                else -> {
                    // DEITY / ADULT STAGE (Level 2):
                    // 1. Cosmic Golden Halo
                    val radialShader = RadialGradient(
                        256f, 256f, 250f,
                        intArrayOf(Color.parseColor("#3F1B05"), Color.parseColor("#070B12")),
                        floatArrayOf(0.1f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = radialShader
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(256f, 256f, 250f, paint)
                    paint.shader = null

                    // 2. Sacred Geometry Matrix (Concentric bronze rings & 12 rays)
                    paint.color = Color.parseColor("#50B45309") // dark warm bronze
                    paint.strokeWidth = 1.5f
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(256f, 256f, 200f, paint)
                    canvas.drawCircle(256f, 256f, 150f, paint)
                    canvas.drawCircle(256f, 256f, 100f, paint)

                    for (angle in 0 until 360 step 30) {
                        val rad = Math.toRadians(angle.toDouble())
                        val startX = (256 + 100 * Math.cos(rad)).toFloat()
                        val startY = (256 + 100 * Math.sin(rad)).toFloat()
                        val endX = (256 + 200 * Math.cos(rad)).toFloat()
                        val endY = (256 + 200 * Math.sin(rad)).toFloat()
                        canvas.drawLine(startX, startY, endX, endY, paint)
                        canvas.drawCircle(endX, endY, 4f, paint)
                    }

                    // 3. Deity Telemetry Overlay
                    paint.color = Color.parseColor("#F59E0B") // Amber gold
                    paint.textSize = 10f
                    paint.typeface = android.graphics.Typeface.MONOSPACE
                    paint.style = Paint.Style.FILL
                    canvas.drawText("SYS.STATUS: MAX_DEITY", 24f, 36f, paint)
                    canvas.drawText("STAGE: L2/DEITY", 24f, 50f, paint)
                    canvas.drawText("POWER: 99.99%", 24f, 64f, paint)
                    canvas.drawText("SIGNAL: SYNCED", 24f, 78f, paint)

                    // 4. Overlapping rotated Pentagons (Outer golden constructs)
                    paint.strokeWidth = 4f
                    val goldShader = LinearGradient(
                        256f, 80f, 256f, 432f,
                        Color.parseColor("#FBBF24"), // Gold
                        Color.parseColor("#D97706"), // Deep Gold
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = goldShader
                    paint.style = Paint.Style.STROKE

                    // Pentagon 1
                    val path1 = Path()
                    for (i in 0..5) {
                        val angle = 90 + i * 72
                        val rad = Math.toRadians(angle.toDouble())
                        val x = (256 + 170 * Math.cos(rad)).toFloat()
                        val y = (256 - 170 * Math.sin(rad)).toFloat()
                        if (i == 0) path1.moveTo(x, y) else path1.lineTo(x, y)
                    }
                    path1.close()
                    canvas.drawPath(path1, paint)

                    // Pentagon 2 (Rotated)
                    paint.color = Color.parseColor("#A0FBBF24")
                    paint.shader = null
                    paint.strokeWidth = 1.5f
                    val path2 = Path()
                    for (i in 0..5) {
                        val angle = 126 + i * 72
                        val rad = Math.toRadians(angle.toDouble())
                        val x = (256 + 140 * Math.cos(rad)).toFloat()
                        val y = (256 - 140 * Math.sin(rad)).toFloat()
                        if (i == 0) path2.moveTo(x, y) else path2.lineTo(x, y)
                    }
                    path2.close()
                    canvas.drawPath(path2, paint)

                    // 5. Central 3D Faceted Crystal Core
                    // Draw a 3D Diamond made of 4 shaded triangles
                    val centerColor = Color.parseColor("#E0F2FE") // Ice white/blue
                    val shadowColor = Color.parseColor("#0284C7") // Dark blue
                    val midColor1 = Color.parseColor("#0EA5E9") // Mid cyan-blue
                    val midColor2 = Color.parseColor("#38BDF8") // Light blue

                    // Top-Left Facet
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(256f, 186f, 256f, 256f, centerColor, midColor1, Shader.TileMode.CLAMP)
                    val p1 = Path().apply {
                        moveTo(256f, 186f)
                        lineTo(256f, 256f)
                        lineTo(196f, 256f)
                        close()
                    }
                    canvas.drawPath(p1, paint)

                    // Top-Right Facet
                    paint.shader = LinearGradient(256f, 186f, 256f, 256f, centerColor, midColor2, Shader.TileMode.CLAMP)
                    val p2 = Path().apply {
                        moveTo(256f, 186f)
                        lineTo(256f, 256f)
                        lineTo(316f, 256f)
                        close()
                    }
                    canvas.drawPath(p2, paint)

                    // Bottom-Left Facet
                    paint.shader = LinearGradient(256f, 326f, 256f, 256f, shadowColor, midColor1, Shader.TileMode.CLAMP)
                    val p3 = Path().apply {
                        moveTo(256f, 326f)
                        lineTo(256f, 256f)
                        lineTo(196f, 256f)
                        close()
                    }
                    canvas.drawPath(p3, paint)

                    // Bottom-Right Facet
                    paint.shader = LinearGradient(256f, 326f, 256f, 256f, shadowColor, midColor2, Shader.TileMode.CLAMP)
                    val p4 = Path().apply {
                        moveTo(256f, 326f)
                        lineTo(256f, 256f)
                        lineTo(316f, 256f)
                        close()
                    }
                    canvas.drawPath(p4, paint)
                    paint.shader = null

                    // Draw crystal edges
                    paint.color = Color.parseColor("#D0E0F2FE")
                    paint.strokeWidth = 1.5f
                    paint.style = Paint.Style.STROKE
                    val edges = Path().apply {
                        moveTo(256f, 186f)
                        lineTo(316f, 256f)
                        lineTo(256f, 326f)
                        lineTo(196f, 256f)
                        close()
                        moveTo(256f, 186f)
                        lineTo(256f, 326f)
                        moveTo(196f, 256f)
                        lineTo(316f, 256f)
                    }
                    canvas.drawPath(edges, paint)

                    // 6. Glowing Neon Vertex Nodes
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#22D3EE")
                    for (i in 0 until 5) {
                        val angle = 90 + i * 72
                        val rad = Math.toRadians(angle.toDouble())
                        val x = (256 + 170 * Math.cos(rad)).toFloat()
                        val y = (256 - 170 * Math.sin(rad)).toFloat()
                        canvas.drawCircle(x, y, 8f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(x, y, 3.5f, paint)
                        paint.color = Color.parseColor("#22D3EE")
                    }
                }
            }

            // CRT Scanlines overlay
            paint.color = Color.parseColor("#06FFFFFF") // ~2% opacity white scanlines
            paint.strokeWidth = 1f
            paint.style = Paint.Style.STROKE
            paint.shader = null
            var yLine = 0f
            while (yLine < size) {
                canvas.drawLine(0f, yLine, size.toFloat(), yLine, paint)
                yLine += 4f
            }

            // Deterministic Analog Film Grain overlay
            val noiseRandom = java.util.Random(1337)
            val noisePaint = Paint().apply {
                style = Paint.Style.FILL
            }
            for (i in 0 until 14000) {
                val rx = noiseRandom.nextFloat() * size
                val ry = noiseRandom.nextFloat() * size
                val dotSize = noiseRandom.nextFloat() * 1.3f + 0.4f
                val alpha = noiseRandom.nextInt(10) + 3 // 3 to 12 alpha (out of 255)
                val isLight = noiseRandom.nextBoolean()
                noisePaint.color = if (isLight) Color.WHITE else Color.BLACK
                noisePaint.alpha = alpha
                canvas.drawCircle(rx, ry, dotSize, noisePaint)
            }

            // Save the bitmap to the target path
            val file = File(outputPath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            Log.d(TAG, "Stylized cyber-gothic illustration generated at: $outputPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing fallback image", e)
            false
        }
    }

    private fun executeMnnTextNative(system: String, user: String): String {
        // Native JNI hook placeholder
        return "MNN Native Text Output"
    }

    private fun executeMnnImageNative(prompt: String, output: String): Boolean {
        // Native JNI hook placeholder
        return false
    }
}

class MediaPipeModel(private val context: Context, private val modelDir: String) : LocalImageModel {
    override val name: String = "MediaPipe-StableDiffusion"
    private val TAG = "MediaPipeModel"
    private var imageGenerator: Any? = null
    private var isLoaded = false

    init {
        try {
            if (modelDir.isNotEmpty() && File(modelDir).exists()) {
                val options = ImageGeneratorOptions.builder()
                    .setImageGeneratorModelDirectory(modelDir)
                    .build()
                imageGenerator = ImageGenerator.createFromOptions(context, options)
                isLoaded = true
                Log.i(TAG, "MediaPipe ImageGenerator initialized successfully with model dir: $modelDir")
            } else {
                Log.w(TAG, "MediaPipe model directory is empty or does not exist, using programmatic canvas fallback.")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize MediaPipe ImageGenerator", e)
        }
    }

    override suspend fun generate(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        "MediaPipe SD [Simulé] : Réponse pour '$userPrompt'"
    }

    override suspend fun generateImage(prompt: String, outputPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "MediaPipe generating image for prompt: '$prompt' -> $outputPath")
        if (isLoaded && imageGenerator != null) {
            try {
                val generator = imageGenerator as ImageGenerator
                // Placeholder code representing actual on-device generation invocation:
                // val result = generator.generate(prompt, 20, 42)
                // val bitmap = result.generatedImage() ... save it.
            } catch (e: Exception) {
                Log.e(TAG, "Error running native MediaPipe generator", e)
            }
        }

        try {
            val size = 512
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Background: Deep dark gothic violet/black void
            canvas.drawColor(Color.parseColor("#08070C"))

            // Seed random dynamically based on prompt for deterministic variety
            val promptSeed = prompt.hashCode().toLong()
            val random = java.util.Random(promptSeed)

            // 1. Draw rich background nebulae gas clouds
            for (i in 0 until 6) {
                val cx = 100f + random.nextInt(312)
                val cy = 100f + random.nextInt(312)
                val r = 120f + random.nextInt(120)
                val colorHex = when (random.nextInt(4)) {
                    0 -> "#25123E" // Deep violet
                    1 -> "#0B3C5D" // Midnight blue
                    2 -> "#022C22" // Dark forest green
                    else -> "#3B0764" // Royal purple
                }
                val shader = RadialGradient(
                    cx, cy, r,
                    intArrayOf(Color.parseColor(colorHex), Color.TRANSPARENT),
                    floatArrayOf(0.1f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, r, paint)
            }
            paint.shader = null

            // 2. Draw atmospheric volumetric light rays
            for (i in 0 until 3) {
                val beamPath = Path().apply {
                    moveTo(0f, 0f)
                    val w1 = 40f + random.nextInt(80)
                    val w2 = 150f + random.nextInt(150)
                    lineTo(w1, 0f)
                    lineTo(300f + w2, 512f)
                    lineTo(300f, 512f)
                    close()
                }
                val beamShader = LinearGradient(
                    0f, 0f, 400f, 512f,
                    Color.parseColor("#1CF2C94C"), // ~11% opacity golden light rays
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                paint.shader = beamShader
                paint.style = Paint.Style.FILL
                canvas.drawPath(beamPath, paint)
            }
            paint.shader = null

            val lowerPrompt = prompt.lowercase()
            when {
                lowerPrompt.contains("oeuf") || lowerPrompt.contains("egg") || lowerPrompt.contains("seed") || lowerPrompt.contains("graine") -> {
                    // STABLE DIFFUSION LEVEL 0 EGG:
                    // 1. Organic soil/dust particles at the bottom
                    paint.style = Paint.Style.FILL
                    for (i in 0 until 180) {
                        val px = random.nextFloat() * size
                        val py = 380f + random.nextFloat() * 132f
                        val pSize = random.nextFloat() * 3.5f + 1f
                        val colorHex = when (random.nextInt(3)) {
                            0 -> "#3F2F2F" // Dark earth
                            1 -> "#2D3748" // Slate
                            else -> "#065F46" // Moss green
                        }
                        paint.color = Color.parseColor(colorHex)
                        paint.alpha = 150 + random.nextInt(105)
                        canvas.drawCircle(px, py, pSize, paint)
                    }
                    paint.alpha = 255

                    // 2. Winding cybernetic/organic roots
                    paint.color = Color.parseColor("#0F766E") // Dark teal roots
                    paint.strokeWidth = 2.5f
                    paint.style = Paint.Style.STROKE
                    for (i in 0 until 4) {
                        val rootPath = Path().apply {
                            moveTo(256f, 330f)
                            val rx1 = 150f + random.nextInt(212)
                            val ry1 = 360f + random.nextInt(50)
                            val rx2 = 50f + random.nextInt(412)
                            quadTo(rx1, ry1, rx2, 480f)
                        }
                        canvas.drawPath(rootPath, paint)
                    }

                    // 3. Volumetric Egg drawing
                    val eggRect = RectF(160f, 140f, 352f, 372f)
                    val eggShader = LinearGradient(
                        256f, 140f, 256f, 372f,
                        Color.parseColor("#FBBF24"), // Warm yellow
                        Color.parseColor("#451A03"), // Deep bronze
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = eggShader
                    paint.style = Paint.Style.FILL
                    canvas.drawOval(eggRect, paint)
                    paint.shader = null

                    // 4. Soft 3D lighting reflection / Specular Highlight
                    val specShader = RadialGradient(
                        220f, 200f, 60f,
                        intArrayOf(Color.parseColor("#B0FFFFFF"), Color.TRANSPARENT),
                        floatArrayOf(0.0f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = specShader
                    canvas.drawCircle(220f, 200f, 60f, paint)
                    paint.shader = null

                    // 5. Floating spores with ambient halos
                    paint.style = Paint.Style.FILL
                    for (i in 0 until 18) {
                        val sx = 80f + random.nextInt(352)
                        val sy = 60f + random.nextInt(340)
                        paint.color = Color.parseColor("#1006B6D4") // Ambient cyan glow
                        canvas.drawCircle(sx, sy, 14f, paint)
                        paint.color = Color.parseColor("#C022D3EE") // Spore core
                        canvas.drawCircle(sx, sy, 2.5f, paint)
                    }
                }
                lowerPrompt.contains("bébé") || lowerPrompt.contains("baby") || lowerPrompt.contains("sprout") || lowerPrompt.contains("pousse") -> {
                    // STABLE DIFFUSION LEVEL 1 SPROUT/BABY:
                    // 1. Organic ground fog
                    val fogShader = LinearGradient(
                        256f, 340f, 256f, 512f,
                        Color.TRANSPARENT,
                        Color.parseColor("#50042F1A"), // semi-transparent dark forest green fog
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = fogShader
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(0f, 340f, 512f, 512f, paint)
                    paint.shader = null

                    // 2. Drawing organic spores floating in air
                    paint.style = Paint.Style.FILL
                    for (i in 0 until 20) {
                        val sx = 50f + random.nextInt(412)
                        val sy = 50f + random.nextInt(412)
                        paint.color = Color.parseColor("#1210B981") // emerald glow
                        canvas.drawCircle(sx, sy, 16f, paint)
                        paint.color = Color.parseColor("#D034D399") // bright spore
                        canvas.drawCircle(sx, sy, 2.5f, paint)
                    }

                    // 3. Species-specific character render with soft 3D highlights
                    if (lowerPrompt.contains("tomate") || lowerPrompt.contains("tomato")) {
                        // Volumetric Red Tomato body
                        val bodyShader = RadialGradient(
                            236f, 250f, 95f,
                            intArrayOf(Color.parseColor("#F87171"), Color.parseColor("#7F1D1D")),
                            floatArrayOf(0.1f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = bodyShader
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 270f, 80f, paint)
                        paint.shader = null

                        // 3D Glass highlight specular overlay
                        val glassShader = RadialGradient(
                            220f, 230f, 50f,
                            intArrayOf(Color.parseColor("#90FFFFFF"), Color.TRANSPARENT),
                            floatArrayOf(0.0f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = glassShader
                        canvas.drawCircle(220f, 230f, 50f, paint)
                        paint.shader = null

                        // Cyber lenses
                        paint.color = Color.parseColor("#06B6D4")
                        canvas.drawCircle(220f, 270f, 12f, paint)
                        canvas.drawCircle(292f, 270f, 12f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(217f, 267f, 4f, paint)
                        canvas.drawCircle(289f, 267f, 4f, paint)

                        // Crown Leaves with detailed veins
                        paint.color = Color.parseColor("#10B981")
                        paint.style = Paint.Style.FILL
                        val leafPath = Path().apply {
                            moveTo(256f, 190f)
                            cubicTo(210f, 150f, 200f, 120f, 256f, 110f)
                            cubicTo(312f, 120f, 302f, 150f, 256f, 190f)
                        }
                        canvas.drawPath(leafPath, paint)

                        paint.color = Color.parseColor("#60A5FA") // glowing blue vein
                        paint.strokeWidth = 1.5f
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(256f, 190f, 256f, 115f, paint)
                        canvas.drawLine(256f, 170f, 236f, 150f, paint)
                        canvas.drawLine(256f, 150f, 230f, 135f, paint)
                        canvas.drawLine(256f, 170f, 276f, 150f, paint)
                        canvas.drawLine(256f, 150f, 282f, 135f, paint)

                    } else if (lowerPrompt.contains("basilic")) {
                        // Basil baby: Volumetric green body
                        val bodyShader = RadialGradient(
                            236f, 260f, 90f,
                            intArrayOf(Color.parseColor("#34D399"), Color.parseColor("#064E3B")),
                            floatArrayOf(0.1f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = bodyShader
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 280f, 75f, paint)
                        paint.shader = null

                        // 3D Highlight
                        val specShader = RadialGradient(
                            225f, 245f, 45f,
                            intArrayOf(Color.parseColor("#80FFFFFF"), Color.TRANSPARENT),
                            floatArrayOf(0.0f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = specShader
                        canvas.drawCircle(225f, 245f, 45f, paint)
                        paint.shader = null

                        // Lenses
                        paint.color = Color.parseColor("#FBBF24")
                        canvas.drawCircle(225f, 285f, 9f, paint)
                        canvas.drawCircle(287f, 285f, 9f, paint)

                        // Floating leaves with veins
                        paint.color = Color.parseColor("#10B981")
                        paint.style = Paint.Style.FILL
                        val leafLeft = Path().apply {
                            moveTo(210f, 250f)
                            cubicTo(150f, 200f, 140f, 170f, 200f, 210f)
                            close()
                        }
                        val leafRight = Path().apply {
                            moveTo(302f, 250f)
                            cubicTo(362f, 200f, 372f, 170f, 312f, 210f)
                            close()
                        }
                        canvas.drawPath(leafLeft, paint)
                        canvas.drawPath(leafRight, paint)

                        // Draw veins on leaves
                        paint.color = Color.parseColor("#A7F3D0")
                        paint.strokeWidth = 1.2f
                        paint.style = Paint.Style.STROKE
                        // Left leaf main vein
                        canvas.drawLine(210f, 250f, 165f, 195f, paint)
                        canvas.drawLine(195f, 232f, 180f, 230f, paint)
                        canvas.drawLine(180f, 215f, 168f, 218f, paint)
                        // Right leaf main vein
                        canvas.drawLine(302f, 250f, 347f, 195f, paint)
                        canvas.drawLine(317f, 232f, 332f, 230f, paint)
                        canvas.drawLine(332f, 215f, 344f, 218f, paint)

                    } else {
                        // General companion (Terreau/etc.): Volumetric capsule
                        val capShader = LinearGradient(
                            256f, 180f, 256f, 360f,
                            Color.parseColor("#3B82F6"),
                            Color.parseColor("#1E3A8A"),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = capShader
                        paint.style = Paint.Style.FILL
                        val capsule = RectF(190f, 180f, 322f, 360f)
                        canvas.drawRoundRect(capsule, 45f, 45f, paint)
                        paint.shader = null

                        // 3D lighting reflection
                        val specShader = RadialGradient(
                            210f, 210f, 40f,
                            intArrayOf(Color.parseColor("#70FFFFFF"), Color.TRANSPARENT),
                            floatArrayOf(0.0f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = specShader
                        canvas.drawCircle(210f, 210f, 40f, paint)
                        paint.shader = null

                        // Visor
                        paint.color = Color.parseColor("#06B6D4")
                        canvas.drawRoundRect(RectF(210f, 225f, 302f, 260f), 10f, 10f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(232f, 242f, 3.5f, paint)
                        canvas.drawCircle(280f, 242f, 3.5f, paint)

                        // Antenna sprout
                        paint.color = Color.parseColor("#10B981")
                        paint.strokeWidth = 3f
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(256f, 180f, 256f, 130f, paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(256f, 130f, 7f, paint)
                    }
                }
                else -> {
                    // STABLE DIFFUSION LEVEL 2 DEITY / ADULT:
                    // 1. Cosmic Ring of Orbiting Glowing Spores (80 dots)
                    paint.style = Paint.Style.FILL
                    for (i in 0 until 80) {
                        val angle = i * 4.5f
                        val rad = Math.toRadians(angle.toDouble())
                        val radius = 140f + random.nextFloat() * 30f
                        val px = (256 + radius * Math.cos(rad)).toFloat()
                        val py = (256 + radius * Math.sin(rad)).toFloat()
                        paint.color = Color.parseColor("#40F471FF")
                        canvas.drawCircle(px, py, 6f, paint)
                        paint.color = Color.WHITE
                        canvas.drawCircle(px, py, 1.5f, paint)
                    }

                    // 2. Golden runic ring
                    paint.color = Color.parseColor("#80FBBF24")
                    paint.strokeWidth = 2.0f
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(256f, 256f, 110f, paint)
                    canvas.drawCircle(256f, 256f, 115f, paint)

                    // Draw golden tick lines (runes) around ring
                    paint.strokeWidth = 1.5f
                    for (angle in 0 until 360 step 15) {
                        val rad = Math.toRadians(angle.toDouble())
                        val x1 = (256 + 110 * Math.cos(rad)).toFloat()
                        val y1 = (256 + 110 * Math.sin(rad)).toFloat()
                        val x2 = (256 + 122 * Math.cos(rad)).toFloat()
                        val y2 = (256 + 122 * Math.sin(rad)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }

                    // 3. Central 3D Hexagonal Cut Crystal
                    val center = 256f
                    val cy = 256f
                    val r = 70f
                    val px = FloatArray(6)
                    val py = FloatArray(6)
                    for (i in 0 until 6) {
                        val rad = Math.toRadians((i * 60 - 30).toDouble())
                        px[i] = (center + r * Math.cos(rad)).toFloat()
                        py[i] = (cy + r * Math.sin(rad)).toFloat()
                    }

                    val colors = intArrayOf(
                        Color.parseColor("#FDF2F8"), // Pink light
                        Color.parseColor("#F471FF"), // Magenta
                        Color.parseColor("#D946EF"), // Fuchsia
                        Color.parseColor("#C026D3"), // Dark fuchsia
                        Color.parseColor("#86198F"), // Deep violet
                        Color.parseColor("#DB2777")  // Pink dark
                    )

                    paint.style = Paint.Style.FILL
                    for (i in 0 until 6) {
                        val next = (i + 1) % 6
                        val triPath = Path().apply {
                            moveTo(center, cy)
                            lineTo(px[i], py[i])
                            lineTo(px[next], py[next])
                            close()
                        }
                        val triShader = RadialGradient(
                            center, cy, r,
                            intArrayOf(colors[i], colors[(i + 3) % 6]),
                            floatArrayOf(0.1f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        paint.shader = triShader
                        canvas.drawPath(triPath, paint)
                    }
                    paint.shader = null

                    // Draw crystal facet edges
                    paint.color = Color.parseColor("#A0FFFFFF")
                    paint.strokeWidth = 1.5f
                    paint.style = Paint.Style.STROKE
                    val crystalOutline = Path().apply {
                        moveTo(px[0], py[0])
                        for (i in 1 until 6) {
                            lineTo(px[i], py[i])
                        }
                        close()
                    }
                    canvas.drawPath(crystalOutline, paint)
                    for (i in 0 until 6) {
                        canvas.drawLine(center, cy, px[i], py[i], paint)
                    }

                    // Floating halo dots
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#E8F471FF")
                    canvas.drawCircle(180f, 190f, 6f, paint)
                    canvas.drawCircle(330f, 190f, 5f, paint)
                    canvas.drawCircle(190f, 320f, 7f, paint)
                    canvas.drawCircle(320f, 320f, 6f, paint)
                }
            }

            // Cinematic Vignette Overlay
            val vignetteShader = RadialGradient(
                256f, 256f, 320f,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#E0000000")),
                floatArrayOf(0.4f, 1.0f),
                Shader.TileMode.CLAMP
            )
            paint.shader = vignetteShader
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null

            // Deterministic Film Grain Overlay
            val noiseRandom = java.util.Random(1337)
            val noisePaint = Paint().apply {
                style = Paint.Style.FILL
            }
            for (i in 0 until 14000) {
                val rx = noiseRandom.nextFloat() * size
                val ry = noiseRandom.nextFloat() * size
                val dotSize = noiseRandom.nextFloat() * 1.3f + 0.4f
                val alpha = noiseRandom.nextInt(10) + 3 // 3 to 12 alpha (out of 255)
                val isLight = noiseRandom.nextBoolean()
                noisePaint.color = if (isLight) Color.WHITE else Color.BLACK
                noisePaint.alpha = alpha
                canvas.drawCircle(rx, ry, dotSize, noisePaint)
            }

            val file = File(outputPath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            Log.d(TAG, "MediaPipe Stable Diffusion fallback image generated at: $outputPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing fallback image", e)
            false
        }
    }
}

class AiCoreModel : LocalModel {
    override val name: String = "Gemini-Nano-AICore"

    override suspend fun generate(systemPrompt: String, userPrompt: String): String {
        // Note: Pixel 9 AICore is accessed via system service context, returning simulated fallback
        // until binding interface is initialized.
        Log.d("AiCoreModel", "AICore model query. OS Version: ${Build.VERSION.SDK_INT}")
        return "AICore Nano [Simulé] : Réponse pour '$userPrompt'"
    }
}
