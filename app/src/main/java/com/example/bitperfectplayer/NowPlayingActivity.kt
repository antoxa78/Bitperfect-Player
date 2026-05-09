package com.example.bitperfectplayer

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class NowPlayingActivity : FragmentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screensaverHandler = Handler(Looper.getMainLooper())
    private var screensaverRunnable = Runnable { showScreensaver() }
    private var isScreensaverActive = false
    
    private lateinit var textTitle: TextView
    private lateinit var textArtist: TextView
    private lateinit var textPlaylistPos: TextView
    private lateinit var textBitrate: TextView
    private lateinit var textCurrentTime: TextView
    private lateinit var textTotalTime: TextView
    private lateinit var imgTrackIcon: android.widget.ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        textTitle = findViewById(R.id.text_title)
        textArtist = findViewById(R.id.text_artist)
        textPlaylistPos = findViewById(R.id.text_playlist_pos)
        textBitrate = findViewById(R.id.text_bitrate)
        textCurrentTime = findViewById(R.id.text_current_time)
        textTotalTime = findViewById(R.id.text_total_time)
        imgTrackIcon = findViewById(R.id.img_track_icon)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnRepeat = findViewById(R.id.btn_repeat)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    textCurrentTime.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaController?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })

        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { mediaController?.seekToPrevious() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { mediaController?.seekToNext() }
        findViewById<ImageButton>(R.id.btn_playlist).setOnClickListener { showPlaylist() }
        
        btnPlayPause.setOnClickListener {
            mediaController?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        btnShuffle.setOnClickListener {
            mediaController?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                updateShuffleRepeatUI()
            }
        }

        btnRepeat.setOnClickListener {
            mediaController?.let {
                val nextMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                it.repeatMode = nextMode
                updateShuffleRepeatUI()
            }
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupController()
                resetScreensaverTimer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isScreensaverActive) {
            hideScreensaver()
        }
        resetScreensaverTimer()
    }

    private fun resetScreensaverTimer() {
        screensaverHandler.removeCallbacks(screensaverRunnable)
        val mins = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("screensaver_delay", 0)
        if (mins > 0 && mediaController?.isPlaying == true) {
            screensaverHandler.postDelayed(screensaverRunnable, mins * 60 * 1000L)
        }
    }

    private fun showScreensaver() {
        if (isFinishing || isDestroyed || isScreensaverActive) return
        if (window.decorView.findViewWithTag<android.view.View>("screensaver_overlay") != null) return
        
        isScreensaverActive = true
        // Simple black overlay for screensaver
        val overlay = android.widget.FrameLayout(this)
        overlay.tag = "screensaver_overlay"
        overlay.setBackgroundColor(android.graphics.Color.BLACK)
        overlay.isClickable = true
        overlay.isFocusable = true
        
        val params = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        addContentView(overlay, params)
        
        // Add moving text to prevent burn-in
        val textView = TextView(this)
        val title = mediaController?.currentMediaItem?.mediaMetadata?.title ?: "Music"
        textView.text = "Now Playing: $title"
        textView.setTextColor(android.graphics.Color.DKGRAY)
        textView.textSize = 24f
        val textParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        textParams.gravity = android.view.Gravity.CENTER
        overlay.addView(textView, textParams)
        
        // Simple "bounce" animation to prevent burn-in
        val handler = Handler(Looper.getMainLooper())
        val moveRunnable = object : Runnable {
            var dx = 2
            var dy = 2
            override fun run() {
                if (!isScreensaverActive) return
                textView.translationX += dx
                textView.translationY += dy
                
                val width = overlay.width
                val height = overlay.height
                if (width > 0 && height > 0) {
                    if (textView.x + textView.width > width || textView.x < 0) dx = -dx
                    if (textView.y + textView.height > height || textView.y < 0) dy = -dy
                }
                handler.postDelayed(this, 50)
            }
        }
        handler.post(moveRunnable)
    }

    private fun hideScreensaver() {
        isScreensaverActive = false
        val overlay = window.decorView.findViewWithTag<android.view.View>("screensaver_overlay")
        (overlay?.parent as? android.view.ViewGroup)?.removeView(overlay)
    }

    private fun setupController() {
        val controller = mediaController ?: return
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateUI()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateUI()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                if (isPlaying) resetScreensaverTimer() else screensaverHandler.removeCallbacks(screensaverRunnable)
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                updateShuffleRepeatUI()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateShuffleRepeatUI()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                textBitrate.text = "Error: ${error.errorCodeName}"
                android.widget.Toast.makeText(this@NowPlayingActivity, "Playback error: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        updateUI()
        startProgressUpdate()
    }

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return
        val mediaItem = controller.currentMediaItem
        textTitle.text = mediaItem?.mediaMetadata?.title ?: "Unknown"
        
        // Show artist or subtitle (useful for streams)
        val artist = mediaItem?.mediaMetadata?.artist
        val subtitle = mediaItem?.mediaMetadata?.subtitle
        textArtist.text = when {
            !artist.isNullOrEmpty() -> artist
            !subtitle.isNullOrEmpty() -> subtitle
            else -> "Unknown"
        }
        
        val current = controller.currentMediaItemIndex + 1
        val total = controller.mediaItemCount
        textPlaylistPos.text = "$current / $total"

        val uri = mediaItem?.mediaId ?: ""
        val iconRes = when {
            uri.startsWith("smb://") -> R.drawable.ic_network
            uri.startsWith("content://") -> R.drawable.ic_folder
            else -> R.drawable.ic_audio
        }
        imgTrackIcon.setImageResource(iconRes)

        btnPlayPause.setImageResource(
            if (controller.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        
        updateShuffleRepeatUI()

        // Audio format details (bitrate/sample rate) via track selection
        val trackGroups = controller.currentTracks
        var format: androidx.media3.common.Format? = null
        
        for (group in trackGroups.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        format = group.getTrackFormat(i)
                        break
                    }
                }
            }
        }

        if (format != null) {
            val bitrateVal = format.bitrate
            val sampleRateVal = format.sampleRate
            val pcmEncoding = format.pcmEncoding
            
            val bitrateStr = if (bitrateVal != androidx.media3.common.Format.NO_VALUE && bitrateVal > 0) "${bitrateVal / 1000} kbps" else "VBR"
            val sampleRateStr = if (sampleRateVal != androidx.media3.common.Format.NO_VALUE && sampleRateVal > 0) "${sampleRateVal} Hz" else ""
            val bitDepthStr = when (pcmEncoding) {
                androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit"
                androidx.media3.common.C.ENCODING_PCM_24BIT -> "24-bit"
                androidx.media3.common.C.ENCODING_PCM_32BIT -> "32-bit"
                else -> ""
            }
            
            val infoList = mutableListOf<String>()
            if (bitrateStr.isNotEmpty()) infoList.add(bitrateStr)
            if (sampleRateStr.isNotEmpty()) infoList.add(sampleRateStr)
            if (bitDepthStr.isNotEmpty()) infoList.add(bitDepthStr)
            
            textBitrate.text = infoList.joinToString(" | ")
        } else {
            when (controller.playbackState) {
                Player.STATE_BUFFERING -> textBitrate.text = "Buffering..."
                Player.STATE_IDLE -> textBitrate.text = "Idle"
                Player.STATE_ENDED -> textBitrate.text = "Playback Ended"
                else -> textBitrate.text = "Detecting Format..."
            }
        }
    }

    private fun updateShuffleRepeatUI() {
        val controller = mediaController ?: return
        
        btnShuffle.alpha = if (controller.shuffleModeEnabled) 1.0f else 0.5f
        
        when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 0.5f
            }
            Player.REPEAT_MODE_ALL -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 1.0f
            }
            Player.REPEAT_MODE_ONE -> {
                // You might want a different icon for Repeat One, but we'll use same with indicator or just different alpha/color
                // For now, let's just change alpha or use a "1" overlay if we had one.
                btnRepeat.setImageResource(android.R.drawable.ic_menu_rotate) // Visual difference
                btnRepeat.alpha = 1.0f
            }
        }
    }

    private fun showPlaylist() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        if (count == 0) {
            android.widget.Toast.makeText(this, "Playlist is empty", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val items = mutableListOf<String>()
        for (i in 0 until count) {
            val item = controller.getMediaItemAt(i)
            items.add("${i + 1}. ${item.mediaMetadata.title ?: "Unknown"}")
        }

        // Custom adapter for playlist with icons
        val adapter = object : android.widget.ArrayAdapter<String>(this, R.layout.list_item_browse, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
                val text = view.findViewById<TextView>(R.id.item_text)
                val icon = view.findViewById<android.widget.ImageView>(R.id.item_icon)
                
                text.text = getItem(position)
                val item = controller.getMediaItemAt(position)
                val uri = item.mediaId
                
                val iconRes = when {
                    uri.startsWith("smb://") -> R.drawable.ic_network
                    uri.startsWith("content://") -> R.drawable.ic_folder
                    else -> R.drawable.ic_audio
                }
                icon.setImageResource(iconRes)
                return view
            }
        }

        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Current Playlist")
        builder.setAdapter(adapter) { _, which ->
            controller.seekTo(which, 0)
            controller.play()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val controller = mediaController ?: return
                val currentPos = controller.currentPosition
                val duration = controller.duration
                
                textCurrentTime.text = formatTime(currentPos)
                
                if (duration > 0 && duration != Long.MAX_VALUE && duration != androidx.media3.common.C.TIME_UNSET) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = currentPos.toInt()
                    textTotalTime.text = formatTime(duration)
                } else {
                    seekBar.max = 100
                    seekBar.progress = 0
                    textTotalTime.text = "∞" // Infinity symbol for streams
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        screensaverHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
}
