package com.unique.visionmate.engine.features

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OcrFeature : FeatureRunner {

    override val feature = Feature.OCR
    override val enabled: Boolean = true
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun run(bitmap: Bitmap): List<Detection> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val out = visionText.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val box = line.boundingBox
                        Detection(
                            label = line.text,
                            score = 1.0f,
                            boxLtrb = if (box != null) floatArrayOf(
                                box.left.toFloat(), box.top.toFloat(),
                                box.right.toFloat(), box.bottom.toFloat()
                            ) else null
                        )
                    }
                }
                cont.resume(out)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    override fun close() {
        try { recognizer.close() } catch (_: Exception) {}
    }
}
