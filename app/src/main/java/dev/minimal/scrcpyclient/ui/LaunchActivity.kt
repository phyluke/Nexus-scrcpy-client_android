package dev.minimal.scrcpyclient.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.minimal.scrcpyclient.R

class LaunchActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.parseColor("#0D1117")
        window.navigationBarColor = Color.parseColor("#0D1117")
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            addView(
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher_art)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
        }
        setContentView(root)

        handler.postDelayed({ goNext() }, 650L)
    }

    private fun goNext() {
        if (launched || isFinishing) return
        launched = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
