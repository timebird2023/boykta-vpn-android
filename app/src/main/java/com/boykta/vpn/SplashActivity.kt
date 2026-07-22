package com.boykta.vpn

import android.content.Intent
import android.os.Bundle
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash / boot screen.
 *
 * Shown for ~2 seconds on first launch. Uses a simple
 * fade-in + scale animation on the logo and title, then
 * transitions to MainActivity.
 *
 * No business logic here — just branding.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Animate shield icon
        val ivLogo  = findViewById<ImageView>(R.id.splashLogo)
        val tvTitle = findViewById<TextView>(R.id.splashTitle)
        val tvSub   = findViewById<TextView>(R.id.splashSub)

        val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 800 }
        val scaleUp = ScaleAnimation(
            0.6f, 1f, 0.6f, 1f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 800 }

        val logoAnim = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(scaleUp)
        }

        val textFade = AlphaAnimation(0f, 1f).apply {
            duration  = 600
            startOffset = 400
            fillAfter = true
        }

        ivLogo.startAnimation(logoAnim)
        tvTitle.startAnimation(textFade)
        tvSub.startAnimation(textFade.also {
            it.startOffset = 600
        })

        // Navigate to MainActivity after delay
        CoroutineScope(Dispatchers.Main).launch {
            delay(2_200)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
            @Suppress("DEPRECATION")   // overrideActivityTransition is API 34+; keep compat
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
