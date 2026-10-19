package io.github.daniel_geron.smm2injector

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The Android app's launcher icon (a "?" block with a download arrow) on a rounded red square. */
val AppIconVector: ImageVector = ImageVector.Builder(
    defaultWidth = 256.dp,
    defaultHeight = 256.dp,
    viewportWidth = 76f,
    viewportHeight = 76f,
).apply {
    addPath(addPathNodes("M14,0 h48 a14,14 0 0 1 14,14 v48 a14,14 0 0 1 -14,14 h-48 a14,14 0 0 1 -14,-14 v-48 a14,14 0 0 1 14,-14 z"), fill = SolidColor(Color(0xFFE52521)))
    group(translationX = -16f, translationY = -16f) {
        addPath(
            addPathNodes("M32,22 h44 a4,4 0 0 1 4,4 v36 a4,4 0 0 1 -4,4 h-44 a4,4 0 0 1 -4,-4 v-36 a4,4 0 0 1 4,-4 z"),
            fill = SolidColor(Color(0xFFFBD000)),
            stroke = SolidColor(Color(0xFF7A4A00)),
            strokeLineWidth = 3f,
        )
        addPath(
            addPathNodes("M46,36 a8,8 0 1 1 11,7.4 c-1.8,0.8 -2.5,1.8 -2.5,3.6 v2 h-5 v-2.5 c0,-3.5 1.6,-5.4 4.4,-6.8 a3,3 0 1 0 -2.9,-3.7 z M49.5,52 h5 v5 h-5 z"),
            fill = SolidColor(Color(0xFF7A4A00)),
        )
        addPath(addPathNodes("M49,69 h10 v6 h6 l-11,10 l-11,-10 h6 z"), fill = SolidColor(Color.White))
    }
}.build()
