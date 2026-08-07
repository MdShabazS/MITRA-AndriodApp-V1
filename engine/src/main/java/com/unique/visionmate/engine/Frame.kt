package com.unique.visionmate.engine

import android.graphics.Bitmap

internal data class Frame(
    val bitmap: Bitmap,
    val frameId: Long,
    val tsMs: Long
)
