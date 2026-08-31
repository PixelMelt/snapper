package com.snapper.android.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.snapper.android.overlay.SnapActions

class CaptureActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "capture_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (val action = intent.getStringExtra(EXTRA_ACTION) ?: intent.action) {
            SnapActions.ACTION_HISTORY -> startActivity(Intent(this, HistoryActivity::class.java))
            SnapActions.ACTION_NORMAL,
            SnapActions.ACTION_FREEZE,
            SnapActions.ACTION_INSTANT,
            SnapActions.ACTION_OPEN_LAST,
            SnapActions.ACTION_CLOSE_ALL -> {
                if (Settings.canDrawOverlays(this)) {
                    SnapActions.send(this, action)
                } else {
                    Toast.makeText(
                        this,
                        "Display over apps is off. Open Snapper to manage access, then try again",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        finish()
    }
}
