package com.codex.chat.core.media

import android.graphics.drawable.PictureDrawable
import com.caverock.androidsvg.SVG

object SvgNativeRenderer {

    fun renderToPictureDrawable(svgXml: String): PictureDrawable? {
        return try {
            val cleanXml = cleanSvg(svgXml)
            val svg = SVG.getFromString(cleanXml)
            val picture = svg.renderToPicture()
            PictureDrawable(picture)
        } catch (e: Throwable) {
            null
        }
    }

    fun isValidSvg(svgXml: String): Boolean {
        val s = svgXml.trim()
        val startIndex = s.indexOf("<svg", ignoreCase = true)
        val endIndex = s.lastIndexOf("</svg>", ignoreCase = true)
        return startIndex != -1 && endIndex != -1 && endIndex > startIndex
    }

    fun cleanSvg(raw: String): String {
        var s = raw.trim()
        val startIndex = s.indexOf("<svg", ignoreCase = true)
        val endIndex = s.lastIndexOf("</svg>", ignoreCase = true)
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            s = s.substring(startIndex, endIndex + 6)
        }
        return s
    }
}
