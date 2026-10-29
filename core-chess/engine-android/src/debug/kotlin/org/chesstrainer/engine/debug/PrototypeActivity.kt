// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.chesstrainer.engine.R

/**
 * Налагоджувальна Activity прототипу рушія (лише debug). Кнопки запускають сценарії
 * [PrototypeScenarios]; ті самі сценарії запускаються з adb:
 *
 *     adb shell am start -n org.chesstrainer.app.debug/org.chesstrainer.engine.debug.PrototypeActivity \
 *         --es scenario cold --es mode remote [--ez baseline true] [--ei repeat 5]
 *
 * Результати: `adb logcat -s EnginePrototype`.
 */
class PrototypeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var remoteSwitch: Switch
    private lateinit var baselineSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Вимірювання потребують ввімкненого екрана; Activity показується й над екраном блокування.
        @Suppress("DEPRECATION") // setShowWhenLocked/setTurnScreenOn потребують API 27, minSdk 26
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        status = TextView(this).apply { text = getString(R.string.prototype_title) }
        remoteSwitch = Switch(this).apply { text = getString(R.string.prototype_remote) }
        baselineSwitch = Switch(this).apply { text = getString(R.string.prototype_baseline) }
        root.addView(status)
        root.addView(remoteSwitch)
        root.addView(baselineSwitch)
        for (name in SCENARIOS) {
            root.addView(
                Button(this).apply {
                    text = name
                    setOnClickListener { launchScenario(name, remoteSwitch.isChecked, baselineSwitch.isChecked, 5) }
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        setContentView(ScrollView(this).apply { addView(root) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val scenario = intent.getStringExtra("scenario") ?: return
        val remote = intent.getStringExtra("mode") == "remote"
        remoteSwitch.isChecked = remote
        launchScenario(scenario, remote, intent.getBooleanExtra("baseline", false), intent.getIntExtra("repeat", 5))
    }

    private fun launchScenario(name: String, remote: Boolean, baseline: Boolean, repeat: Int) {
        status.text = getString(R.string.prototype_status, name, remote.toString(), baseline.toString())
        PrototypeHost.launch(applicationContext, name, remote, baseline, repeat) {
            runOnUiThread { status.text = getString(R.string.prototype_done, name) }
        }
    }

    override fun onDestroy() {
        Log.i("EnginePrototype", "PrototypeActivity destroyed")
        super.onDestroy()
    }

    private companion object {
        val SCENARIOS = listOf(
            "hello", "contract", "cold", "restart", "bestmove", "ipc", "analyse",
            "memory", "crash", "bgprep", "bgcheck", "stop",
        )
    }
}
