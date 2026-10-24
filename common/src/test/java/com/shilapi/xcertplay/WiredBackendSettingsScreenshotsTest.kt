package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Compact and expanded previews of the wired backend setting at large text size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WiredBackendSettingsScreenshotsTest {
    @Test fun settingCardFitsCompactAndExpandedLayoutsAtLargeFont() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        try {
            activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putString("wired_backend_success_version", "obsolete")
                .putString("wired_backend_success_abi", "arm64-v8a").commit()
            for ((name, widthDp, language) in listOf(
                Triple("compact-ja", 320, "ja-JP"),
                Triple("compact-zh", 320, "zh-CN"),
                Triple("full-ru", 960, "ru-RU"),
                Triple("compact-zh-hdpi", 320, "zh-CN"),
                Triple("full-en", 960, "en-US"),
                Triple("compact-ar-rtl", 320, "ar-SA"),
            )) {
                val locale = Locale.forLanguageTag(language)
                val config = Configuration(activity.resources.configuration).apply {
                    fontScale = 1.5f
                    densityDpi = if (name.endsWith("hdpi")) 320 else 160
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.EXPERIMENTAL_NETWORK))
                DiPlayActivity::class.java.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, content)
                assertTrue(descendants(content).filterIsInstance<TextView>().any {
                    it.text == activity.getString(R.string.settings_wired_backend_title)
                })

                assertTrue("Obsolete validation record must not add a clear button",
                    descendants(content).filterIsInstance<Button>().count() == 1)
                val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, width, content.measuredHeight)
                val selector = descendants(content).filterIsInstance<TextView>().single {
                    it.text.toString().startsWith("${activity.getString(R.string.settings_wired_backend_mode)} · ")
                }
                assertTrue("Backend selector wraps at $name", selector.layout.lineCount <= 1)
                val bitmap = Bitmap.createBitmap(width, content.measuredHeight, Bitmap.Config.ARGB_8888)
                try {
                    content.draw(Canvas(bitmap))
                    val folder = File("build/reports/lwip-settings-ui").apply { mkdirs() }
                    FileOutputStream(File(folder, "$name.png")).use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
            activity.finish()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
