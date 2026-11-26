package dev.minimal.scrcpyclient.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Placeholder: proves the build pipeline works. Real UI comes in the next step. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Scrcpy Client scaffold OK"
            textSize = 20f
            setPadding(48, 96, 48, 48)
        })
    }
}
