package com.glassify.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The screen shown after a startup crash, in place of the home screen.
 *
 * Built out of plain platform views on purpose. Everything that could plausibly
 * have crashed — Compose, the shaders, the glass, the launcher's own data
 * loading — is excluded, so this screen still works when none of that does.
 * A diagnostic that shares the failure mode of the thing it is diagnosing is
 * worth nothing.
 */
object SafeModeView {

    fun show(activity: Activity, trace: String) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }

        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.safe_mode_title)
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )

        root.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.safe_mode_body)
                setTextColor(Color.parseColor("#A0A0A8"))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(16))
            }
        )

        val traceView = TextView(activity).apply {
            text = trace
            setTextColor(Color.parseColor("#FF9F9F"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(activity).apply {
                addView(traceView)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f }
            }
        )

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        buttons.addView(
            button(activity, activity.getString(R.string.safe_mode_share)) {
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Glassify crash")
                            putExtra(Intent.EXTRA_TEXT, trace)
                        },
                        activity.getString(R.string.safe_mode_share),
                    )
                )
            }
        )

        buttons.addView(
            // Clearing the record is what lets the user try again. Without it
            // the safe screen would be as inescapable as the loop it replaced.
            button(activity, activity.getString(R.string.safe_mode_retry)) {
                CrashReporter.clear(activity)
                activity.recreate()
            }
        )

        root.addView(buttons)
        activity.setContentView(root)
    }

    private fun button(activity: Activity, label: String, onClick: () -> Unit): View =
        Button(activity).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { weight = 1f }
        }
}
