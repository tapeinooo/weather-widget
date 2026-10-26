package com.example.weatherwidget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout

/**
 * 確認用の画面。先に `adb shell appwidget grantbind --package com.example.weatherwidget` で許可しておく。
 * intent の extra で大きさ（dp）を変えられる：--ei w 360 --ei h 130
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, 1)
        host.startListening()
        val provider = ComponentName(this, WeatherWidgetReceiver::class.java)
        val prefs = getSharedPreferences("host", MODE_PRIVATE)
        var id = prefs.getInt("id", -1)
        if (id == -1 || manager.getAppWidgetInfo(id) == null) {
            id = host.allocateAppWidgetId()
            check(manager.bindAppWidgetIdIfAllowed(id, provider)) { "grantbind が必要" }
            prefs.edit().putInt("id", id).apply()
        }
        val w = intent.getIntExtra("w", 360)
        val h = intent.getIntExtra("h", 130)
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        })
        val view = host.createView(this, id, manager.getAppWidgetInfo(id))
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#3C6E91")) }
        frame.addView(view, FrameLayout.LayoutParams(dp(w), dp(h), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(80) })
        setContentView(frame)
        WidgetRenderer.updateAll(this)
    }

    override fun onDestroy() {
        host.stopListening()
        super.onDestroy()
    }
}
