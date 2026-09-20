package com.codex.chat.agent.device

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Screen dimensions and pixel density provider with Android 11+ (API 30)
 * WindowMetrics support and backward-compatible fallback for API 26-29.
 */
object DeviceMetricsProvider {

    fun getScreenWidth(context: Context): Int = getMetrics(context).widthPixels

    fun getScreenHeight(context: Context): Int = getMetrics(context).heightPixels

    fun getDensityDpi(context: Context): Int = getMetrics(context).densityDpi

    private fun getMetrics(context: Context): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()

        if (wm == null) {
            context.resources.displayMetrics.setTo(metrics)
            return metrics
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
            metrics.density = context.resources.displayMetrics.density
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        return metrics
    }
}
