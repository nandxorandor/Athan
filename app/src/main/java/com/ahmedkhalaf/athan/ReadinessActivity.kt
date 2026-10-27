package com.ahmedkhalaf.athan

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.ahmedkhalaf.athan.databinding.ActivityReadinessBinding

/**
 * "Is everything ready?" - each thing on the phone that can stop an athan,
 * with a tap to fix it, and buttons to hear the heads-up and the athan now
 * rather than finding out at the next prayer. Asked for by users who had set
 * everything up and then waited, unsure, for the first one to arrive.
 *
 * Re-checked on every return, since fixing a check means leaving for the
 * system's settings and coming back.
 */
class ReadinessActivity : LocalizedActivity() {

    private lateinit var binding: ActivityReadinessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadinessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.testHeadsUp.setOnClickListener { testHeadsUp(this) }
        binding.testAthan.setOnClickListener { testAthan(this) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val checks = Readiness.checks(this)
        val problems = checks.count { it.status == Readiness.Status.PROBLEM }
        binding.summary.text =
            if (problems == 0) getString(R.string.readiness_all_good)
            else resources.getQuantityString(R.plurals.readiness_problems, problems, problems)
        binding.summary.setTextColor(
            getColor(if (problems == 0) R.color.accent else R.color.warning_text)
        )

        binding.checks.removeAllViews()
        checks.forEach { check ->
            val row = layoutInflater.inflate(R.layout.item_check, binding.checks, false)
            val color = getColor(
                when (check.status) {
                    Readiness.Status.OK -> R.color.accent
                    Readiness.Status.ADVICE -> R.color.text_dim
                    Readiness.Status.PROBLEM -> R.color.warning_text
                }
            )
            row.findViewById<ImageView>(R.id.checkIcon).apply {
                setImageResource(
                    if (check.status == Readiness.Status.OK) R.drawable.ic_check_ok
                    else R.drawable.ic_check_warn
                )
                imageTintList = ColorStateList.valueOf(color)
            }
            row.findViewById<TextView>(R.id.checkTitle).setText(check.title)
            row.findViewById<TextView>(R.id.checkDetail).setText(check.detail)
            val fix = check.fix
            if (fix != null) {
                row.findViewById<TextView>(R.id.checkFix).apply {
                    visibility = View.VISIBLE
                    // Advice is a choice to make, not a fault to fix.
                    setText(
                        if (check.status == Readiness.Status.ADVICE) R.string.check_change
                        else R.string.check_fix
                    )
                }
                row.findViewById<View>(R.id.checkChevron).visibility = View.VISIBLE
                row.setOnClickListener {
                    // A settings screen some makers have moved or removed is
                    // not worth a crash; fall back to the app's own page.
                    runCatching { fix(this) }.onFailure {
                        runCatching {
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName"),
                                )
                            )
                        }
                    }
                }
            } else {
                row.isClickable = false
                row.background = null
            }
            binding.checks.addView(row)
        }
    }

    companion object {
        /**
         * The real heads-up for the next prayer, now: its tone or vibration,
         * its volume and its banner, exactly as they will arrive.
         */
        fun testHeadsUp(context: Context) {
            val slot = PrayerEngine(Prefs(context)).next()?.slot ?: Slot.DHUHR
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReminderService::class.java)
                    .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
            )
        }

        /**
         * The real athan for the next prayer, now, as that prayer is set:
         * sound, vibration or silent. Stopped from its notification or the
         * home screen's Stop, like any other.
         */
        fun testAthan(context: Context) {
            val slot = PrayerEngine(Prefs(context)).next()?.slot ?: Slot.DHUHR
            ContextCompat.startForegroundService(
                context,
                Intent(context, AthanService::class.java)
                    .putExtra(AthanScheduler.EXTRA_SLOT, slot.name)
            )
        }
    }
}
