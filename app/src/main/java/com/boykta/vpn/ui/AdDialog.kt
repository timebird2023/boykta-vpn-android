package com.boykta.vpn.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.boykta.vpn.R
import com.boykta.vpn.model.Announcement
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView

class AdDialog : DialogFragment() {

    companion object {
        private const val ARG_URLS = "media_urls"
        private const val ARG_LINK = "link_url"
        private const val ARG_TYPE = "media_type"
        private const val AD_DURATION_SEC = 15

        fun newInstance(announcement: Announcement): AdDialog {
            return AdDialog().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_URLS, ArrayList(announcement.mediaUrls))
                    putString(ARG_LINK, announcement.linkUrl)
                    putString(ARG_TYPE, announcement.mediaType)
                }
            }
        }
    }

    var onAdClosed: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var secondsRemaining = AD_DURATION_SEC
    private lateinit var runnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_ad, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mediaUrls = arguments?.getStringArrayList(ARG_URLS) ?: emptyList<String>()
        val linkUrl = arguments?.getString(ARG_LINK, "") ?: ""

        val vpAd = view.findViewById<ViewPager2>(R.id.vpAd)
        val videoAd = view.findViewById<VideoView>(R.id.videoAd)
        val pbTimer = view.findViewById<ProgressBar>(R.id.pbTimer)
        val tvTimer = view.findViewById<TextView>(R.id.tvAdTimer)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)
        val layoutAdLink = view.findViewById<View>(R.id.layoutAdLink)

        // Setup the correct renderer for the announcement media type.
        if (arguments?.getString(ARG_TYPE, "image") == "video") {
            vpAd.visibility = View.GONE
            videoAd.visibility = View.VISIBLE
            mediaUrls.firstOrNull()?.let { url ->
                videoAd.setVideoURI(Uri.parse(url))
                videoAd.setOnPreparedListener { player ->
                    player.isLooping = true
                    videoAd.start()
                }
            }
        } else {
            videoAd.visibility = View.GONE
            vpAd.adapter = AdImageAdapter(mediaUrls)
        }
        pbTimer.max = AD_DURATION_SEC * 10
        pbTimer.progress = AD_DURATION_SEC * 10

        // Block back button
        dialog?.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK
        }

        // Disable close button initially
        btnClose.visibility = View.GONE

        // Countdown ticker (every 100ms for smooth progress bar)
        var ticks = AD_DURATION_SEC * 10
        runnable = object : Runnable {
            override fun run() {
                if (!isAdded) return
                ticks--
                secondsRemaining = ticks / 10 + if (ticks % 10 > 0) 1 else 0
                pbTimer.progress = ticks
                tvTimer.text = if (secondsRemaining > 0) "الإغلاق بعد $secondsRemaining ث" else "يمكنك الإغلاق الآن"

                if (ticks <= 0) {
                    btnClose.visibility = View.VISIBLE
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.postDelayed(runnable, 100)

        // Close button
        btnClose.setOnClickListener {
            dismiss()
            onAdClosed?.invoke()
        }

        // Link click
        if (linkUrl.isNotBlank()) {
            layoutAdLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
            }
        } else {
            layoutAdLink.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setDimAmount(0.85f)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(runnable)
        super.onDestroyView()
    }

    // ── Image/GIF carousel adapter ──────────────────────────────────────────

    inner class AdImageAdapter(private val urls: List<String>) :
        RecyclerView.Adapter<AdImageAdapter.VH>() {

        inner class VH(val imageView: ShapeableImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ShapeableImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.imageView)
                .asGif()
                .load(urls[position])
                .error(
                    Glide.with(holder.imageView)
                        .load(urls[position]) // fallback to static image
                )
                .into(holder.imageView)
        }

        override fun getItemCount() = urls.size
    }
}
