package com.unique.visionmate.engine.features

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.unique.visionmate.engine.Detection
import com.unique.visionmate.engine.Feature
import com.unique.visionmate.engine.FeatureModelSpec
import com.unique.visionmate.engine.Telemetry
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

internal class TfliteFeatureRunner(
    context: Context,
    private val spec: FeatureModelSpec
) : FeatureRunner {

    override val feature: Feature = spec.feature
    private var interpreter: Interpreter? = null
    private var usingGpu: Boolean = false

    private enum class Layout { NHWC, NCHW }
    private var layout: Layout = Layout.NHWC
    private var modelW: Int = spec.inputWidth
    private var modelH: Int = spec.inputHeight
    private var outputClasses: Int = spec.labels.size.coerceAtLeast(1)

    private var inputBuffer: ByteBuffer? = null
    private var pixelScratch: IntArray = IntArray(0)
    private var sourceW: Int = 1
    private var sourceH: Int = 1
    private var inputScale: Float = 1f
    private var inputPadX: Float = 0f
    private var inputPadY: Float = 0f

    // Cached output geometry for DETECTION_NMS. We auto-detect both common YOLO export layouts:
    //   YOLOv8: [1, 4+nc, A]   channels-first, no objectness (score = classScore)
    //   YOLOv5: [1, A, 5+nc]   anchor-major,  with objectness (score = obj * classScore)
    private var detD1: Int = 0
    private var detD2: Int = 0
    private var detChannels: Int = 0          // per-anchor channel count (the small dim)
    private var detAnchors: Int = 0           // anchor count (the large dim)
    private var detChannelsFirst: Boolean = true
    private var detHasObjectness: Boolean = false
    private var auxOutputs: List<Pair<Int, IntArray>> = emptyList()

    init {
        if (spec.enabled && spec.assetPath != null) {
            try {
                val model = loadModel(context, spec.assetPath)
                val itp = buildInterpreter(model)
                val inShape = itp.getInputTensor(0).shape()
                val outShape = itp.getOutputTensor(0).shape()
                if (inShape.size == 4) {
                    if (inShape[3] == 3) {
                        layout = Layout.NHWC; modelH = inShape[1]; modelW = inShape[2]
                    } else if (inShape[1] == 3) {
                        layout = Layout.NCHW; modelH = inShape[2]; modelW = inShape[3]
                    }
                }
                if (outShape.size == 3) configureDetectionGeometry(outShape, spec.labels.size.coerceAtLeast(1))
                if (outShape.size >= 2 && spec.postprocessor == "CLASSIFICATION_TOPK") {
                    outputClasses = outShape[outShape.size - 1]
                }
                val auxList = mutableListOf<Pair<Int, IntArray>>()
                for (i in 1 until itp.outputTensorCount) {
                    auxList.add(i to itp.getOutputTensor(i).shape())
                }
                auxOutputs = auxList

                inputBuffer = ByteBuffer
                    .allocateDirect(modelW * modelH * 3 * 4)
                    .order(ByteOrder.nativeOrder())
                pixelScratch = IntArray(modelW * modelH)
                interpreter = itp
                Telemetry.i("${spec.feature}: loaded ${spec.assetPath} layout=$layout in=${inShape.toList()} out=${outShape.toList()} auxOutputs=${auxList.size} accel=${if (usingGpu) "GPU" else "CPU"}")
            } catch (e: Exception) {
                Telemetry.w("failed to load TFLite model for ${spec.feature}: ${e.message}")
                interpreter = null
            }
        }
    }

    override val enabled: Boolean get() = interpreter != null

    override suspend fun run(bitmap: Bitmap): List<Detection> {
        val itp = interpreter ?: return emptyList()
        val buf = inputBuffer ?: return emptyList()
        preprocess(bitmap, buf)
        return when (spec.postprocessor) {
            "CLASSIFICATION_TOPK" -> runClassification(itp)
            "DETECTION_NMS" -> runDetection(itp)
            else -> {
                Telemetry.w("postprocessor '${spec.postprocessor}' not implemented for ${spec.feature}")
                emptyList()
            }
        }
    }

    private fun preprocess(bitmap: Bitmap, buf: ByteBuffer) {
        sourceW = bitmap.width.coerceAtLeast(1)
        sourceH = bitmap.height.coerceAtLeast(1)
        val resized = if (spec.postprocessor == "DETECTION_NMS") {
            letterbox(bitmap)
        } else if (bitmap.width != modelW || bitmap.height != modelH) {
            inputScale = 1f
            inputPadX = 0f
            inputPadY = 0f
            Bitmap.createScaledBitmap(bitmap, modelW, modelH, true)
        } else {
            inputScale = 1f
            inputPadX = 0f
            inputPadY = 0f
            bitmap
        }
        resized.getPixels(pixelScratch, 0, modelW, 0, 0, modelW, modelH)
        buf.rewind()
        when (layout) {
            Layout.NHWC -> {
                for (px in pixelScratch) {
                    val r = ((px shr 16) and 0xFF).toFloat()
                    val g = ((px shr 8) and 0xFF).toFloat()
                    val b = (px and 0xFF).toFloat()
                    buf.putFloat((r - spec.mean[0]) / spec.std[0])
                    buf.putFloat((g - spec.mean[1]) / spec.std[1])
                    buf.putFloat((b - spec.mean[2]) / spec.std[2])
                }
            }
            Layout.NCHW -> {
                val n = pixelScratch.size
                for (i in 0 until n) buf.putFloat((((pixelScratch[i] shr 16) and 0xFF).toFloat() - spec.mean[0]) / spec.std[0])
                for (i in 0 until n) buf.putFloat((((pixelScratch[i] shr 8) and 0xFF).toFloat() - spec.mean[1]) / spec.std[1])
                for (i in 0 until n) buf.putFloat(((pixelScratch[i] and 0xFF).toFloat() - spec.mean[2]) / spec.std[2])
            }
        }
        if (resized !== bitmap) resized.recycle()
    }

    private fun letterbox(bitmap: Bitmap): Bitmap {
        val scale = min(modelW.toFloat() / sourceW.toFloat(), modelH.toFloat() / sourceH.toFloat())
        val scaledW = (sourceW * scale).toInt().coerceAtLeast(1)
        val scaledH = (sourceH * scale).toInt().coerceAtLeast(1)
        val padX = (modelW - scaledW) / 2
        val padY = (modelH - scaledH) / 2
        inputScale = scale
        inputPadX = padX.toFloat()
        inputPadY = padY.toFloat()

        val out = Bitmap.createBitmap(modelW, modelH, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.rgb(114, 114, 114))
        Canvas(out).drawBitmap(
            bitmap,
            null,
            Rect(padX, padY, padX + scaledW, padY + scaledH),
            null
        )
        return out
    }

    private fun runClassification(itp: Interpreter): List<Detection> {
        val output = Array(1) { FloatArray(outputClasses) }
        itp.run(inputBuffer, output)
        val scores = output[0]
        val topK = scores.indices.sortedByDescending { scores[it] }.take(3)
        return topK.map { idx ->
            val label = spec.labels.getOrElse(idx) { "class_$idx" }
            Detection(label = label, score = scores[idx])
        }
    }

    /**
     * Decide the detection output layout from the tensor shape. Handles both YOLOv8
     * ([1, 4+nc, A], channels-first, no objectness) and YOLOv5 ([1, A, 5+nc], anchor-major, with
     * objectness). The channel dim is the smaller of the two; anchors the larger.
     */
    private fun configureDetectionGeometry(outShape: IntArray, numClasses: Int) {
        detD1 = outShape[1]
        detD2 = outShape[2]
        detChannelsFirst = detD1 <= detD2
        detChannels = if (detChannelsFirst) detD1 else detD2
        detAnchors = if (detChannelsFirst) detD2 else detD1
        // 5+nc => YOLOv5 (objectness present); 4+nc => YOLOv8. Default to objectness only on exact match.
        detHasObjectness = detChannels == 5 + numClasses
        Telemetry.i("${spec.feature}: detection geometry channels=$detChannels anchors=$detAnchors channelsFirst=$detChannelsFirst objectness=$detHasObjectness")
    }

    /** Reads channel [c] of anchor [a] from the model output, accounting for the detected layout. */
    private fun detVal(out: Array<FloatArray>, c: Int, a: Int): Float =
        if (detChannelsFirst) out[c][a] else out[a][c]

    /**
     * Layout-agnostic YOLO detection postprocessor (see [configureDetectionGeometry]).
     * bbox channels 0..3 = (cx, cy, w, h) in model-input pixel coords; class scores follow at
     * offset 4 (YOLOv8) or 5 (YOLOv5, after the objectness channel). Any trailing channels
     * (e.g. seg-mask coefficients) are ignored.
     */
    private fun runDetection(itp: Interpreter): List<Detection> {
        val a = detAnchors
        if (detChannels <= 4 || a == 0) return emptyList()
        val output = Array(1) { Array(detD1) { FloatArray(detD2) } }

        if (auxOutputs.isEmpty()) {
            itp.run(inputBuffer, output)
        } else {
            val map = HashMap<Int, Any>()
            map[0] = output
            for ((idx, shape) in auxOutputs) {
                val total = shape.fold(1) { acc, x -> acc * x }
                map[idx] = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder())
            }
            itp.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer!!), map)
        }

        val numClasses = spec.labels.size.coerceAtLeast(1)
        val classOffset = if (detHasObjectness) 5 else 4
        val confThresh = spec.confidenceThreshold
        val iouThresh = 0.5f
        val out = output[0]

        val cands = ArrayList<FloatArray>()  // each entry: [cx, cy, w, h, score, cls]
        for (anchor in 0 until a) {
            val obj = if (detHasObjectness) detVal(out, 4, anchor) else 1f
            var bestCls = -1
            var bestClsScore = 0f
            for (c in 0 until numClasses) {
                val s = detVal(out, classOffset + c, anchor)
                if (s > bestClsScore) { bestClsScore = s; bestCls = c }
            }
            val score = obj * bestClsScore
            if (score < confThresh || bestCls < 0) continue
            cands.add(floatArrayOf(detVal(out, 0, anchor), detVal(out, 1, anchor), detVal(out, 2, anchor), detVal(out, 3, anchor), score, bestCls.toFloat()))
        }
        if (cands.isEmpty()) return emptyList()

        cands.sortByDescending { it[4] }
        val suppressed = BooleanArray(cands.size)
        val kept = ArrayList<FloatArray>()
        for (i in cands.indices) {
            if (suppressed[i]) continue
            kept.add(cands[i])
            if (kept.size >= 50) break
            for (j in i + 1 until cands.size) {
                if (suppressed[j] || cands[j][5] != cands[i][5]) continue
                if (iou(
                        cands[i][0], cands[i][1], cands[i][2], cands[i][3],
                        cands[j][0], cands[j][1], cands[j][2], cands[j][3]
                    ) >= iouThresh
                ) suppressed[j] = true
            }
        }

        return kept.mapNotNull { c ->
            val box = decodeBox(c) ?: return@mapNotNull null
            Detection(
                label = spec.labels.getOrElse(c[5].toInt()) { "class_${c[5].toInt()}" },
                score = c[4],
                boxLtrb = box
            )
        }
    }

    private fun decodeBox(c: FloatArray): FloatArray? {
        val normalized = c[0] in 0f..1.5f && c[1] in 0f..1.5f && c[2] in 0f..1.5f && c[3] in 0f..1.5f
        val cx = if (normalized) c[0] * modelW else c[0]
        val cy = if (normalized) c[1] * modelH else c[1]
        val bw = if (normalized) c[2] * modelW else c[2]
        val bh = if (normalized) c[3] * modelH else c[3]

        val leftPx = (cx - bw / 2f - inputPadX) / inputScale
        val topPx = (cy - bh / 2f - inputPadY) / inputScale
        val rightPx = (cx + bw / 2f - inputPadX) / inputScale
        val bottomPx = (cy + bh / 2f - inputPadY) / inputScale

        val left = (leftPx / sourceW).coerceIn(0f, 1f)
        val top = (topPx / sourceH).coerceIn(0f, 1f)
        val right = (rightPx / sourceW).coerceIn(0f, 1f)
        val bottom = (bottomPx / sourceH).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return floatArrayOf(left, top, right, bottom)
    }

    private fun loadModel(context: Context, assetPath: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetPath)
        return FileInputStream(afd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    /**
     * Production-stability path for this device class:
     * keep TFLite on CPU only. We have native tombstones from the Mali/OpenCL GPU
     * delegate path on the target phone, so GPU is intentionally disabled here.
     */
    private fun buildInterpreter(model: MappedByteBuffer): Interpreter {
        usingGpu = false
        Telemetry.i("${spec.feature}: forcing CPU/XNNPACK for stability")
        return Interpreter(model, Interpreter.Options().apply { numThreads = 2 })
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }

    companion object {
        private fun iou(acx: Float, acy: Float, aw: Float, ah: Float,
                        bcx: Float, bcy: Float, bw: Float, bh: Float): Float {
            val ax1 = acx - aw / 2; val ay1 = acy - ah / 2
            val ax2 = acx + aw / 2; val ay2 = acy + ah / 2
            val bx1 = bcx - bw / 2; val by1 = bcy - bh / 2
            val bx2 = bcx + bw / 2; val by2 = bcy + bh / 2
            val iw = max(0f, min(ax2, bx2) - max(ax1, bx1))
            val ih = max(0f, min(ay2, by2) - max(ay1, by1))
            val inter = iw * ih
            val union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
            return if (union > 0f) inter / union else 0f
        }
    }
}
