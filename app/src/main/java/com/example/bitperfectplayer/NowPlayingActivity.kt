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
    private lateinit var textAlbum: TextView
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
        textAlbum = findViewById(R.id.text_album)
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
        if (mins != 0 && mediaController?.isPlaying == true) {
            val finalMins = Math.abs(mins)
            screensaverHandler.postDelayed(screensaverRunnable, finalMins * 60 * 1000L)
        }
    }

    private fun showScreensaver() {
        if (isFinishing || isDestroyed || isScreensaverActive) return
        if (window.decorView.findViewWithTag<android.view.View>("screensaver_overlay") != null) return
        
        val mins = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("screensaver_delay", 0)
        if (mins == 0) return

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
        
        if (mins < 0) {
            // Completely black mode - no text
            return
        }

        // Add moving text to prevent burn-in
        val textView = TextView(this)
        textView.tag = "screensaver_text"
        updateScreensaverText(textView)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Internal explorer uses direct calls, but we keep this for any other intent results if needed.
    }

    private fun parseM3uLocal(inputStream: java.io.InputStream, baseUri: android.net.Uri): List<androidx.media3.common.MediaItem> {
        val items = mutableListOf<androidx.media3.common.MediaItem>()
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null
            
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXTINF:")) {
                    val commaIndex = trimmed.indexOf(",")
                    if (commaIndex != -1) {
                        currentTitle = trimmed.substring(commaIndex + 1)
                    }
                } else if (!trimmed.startsWith("#")) {
                    val itemUri = try {
                        when {
                            trimmed.startsWith("/") -> android.net.Uri.parse("file://$trimmed")
                            trimmed.startsWith("file://") || trimmed.startsWith("content://") ||
                            trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                            trimmed.startsWith("smb://") -> android.net.Uri.parse(trimmed)
                            else -> null
                        }
                    } catch (e: Exception) { null }

                    if (itemUri != null) {
                        items.add(
                            androidx.media3.common.MediaItem.Builder()
                                .setMediaId(trimmed)
                                .setUri(itemUri)
                                .setMediaMetadata(
                                    androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(currentTitle ?: itemUri.lastPathSegment ?: trimmed)
                                        .build()
                                )
                                .build()
                        )
                    }
                    currentTitle = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    private fun updateScreensaverText(textView: TextView) {
        val mediaItem = mediaController?.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        val title = metadata?.title ?: "Music"
        val artist = metadata?.artist ?: "Unknown Artist"
        val album = metadata?.albumTitle ?: "Unknown Album"
        
        // Track number if available in metadata
        val trackNum = metadata?.trackNumber
        val trackPrefix = if (trackNum != null) "$trackNum. " else ""
        
        textView.text = "Now Playing:\n$trackPrefix$title\n$artist\n$album"
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
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
                refreshScreensaver()
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                updateUI()
                refreshScreensaver()
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

    private fun refreshScreensaver() {
        if (isScreensaverActive) {
            val screensaverText = window.decorView.findViewWithTag<TextView>("screensaver_text")
            if (screensaverText != null) {
                updateScreensaverText(screensaverText)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return
        val mediaItem = controller.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        
        textTitle.text = metadata?.title ?: "Unknown Title"
        
        val artist = metadata?.artist
        val subtitle = metadata?.subtitle
        textArtist.text = when {
            !artist.isNullOrEmpty() -> artist
            !subtitle.isNullOrEmpty() -> subtitle
            else -> "Unknown Artist"
        }

        // Try to get album from albumTitle or potentially other fields
        val album = metadata?.albumTitle
        if (!album.isNullOrEmpty()) {
            textAlbum.text = album.toString()
            textAlbum.visibility = android.view.View.VISIBLE
        } else {
            textAlbum.visibility = android.view.View.GONE
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
        
        val items = mutableListOf<String>()
        for (i in 0 until count) {
            val item = controller.getMediaItemAt(i)
            items.add("${i + 1}. ${item.mediaMetadata.title ?: "Unknown"}")
        }

        // Custom adapter for playlist with icons
        val playlistAdapter = object : android.widget.ArrayAdapter<String>(this, R.layout.list_item_browse, items) {
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
        
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val listView = android.widget.ListView(this).apply {
            adapter = playlistAdapter
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
        }
        dialogView.addView(listView)

        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = builder.setView(dialogView).create()

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        buttonRow.addView(createBtn("Clear") { 
            controller.clearMediaItems()
            android.widget.Toast.makeText(this@NowPlayingActivity, "Playlist cleared", android.widget.Toast.LENGTH_SHORT).show()
            updateUI()
        })
        buttonRow.addView(createBtn("Add Files") {
            checkAndBrowseInternalStorage(isSelectionMode = false, isDirectoryMode = false)
            dialog.dismiss()
        })
        buttonRow.addView(createBtn("Add Directory") {
            checkAndBrowseInternalStorage(isSelectionMode = true, isDirectoryMode = true)
            dialog.dismiss()
        })

        dialogView.addView(buttonRow)

        listView.setOnItemClickListener { _, _, which, _ ->
            controller.seekTo(which, 0)
            controller.play()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun checkAndBrowseInternalStorage(isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
            } else {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("All Files Access Required")
                    .setMessage("To browse storage directly on Android TV 11+, please grant 'All Files Access' in the system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
        }
    }

    private data class BrowseItem(val name: String, val path: String, val icon: Int, val isDirectory: Boolean)

    private class BrowseAdapter(context: android.content.Context, items: List<BrowseItem>) : 
        android.widget.ArrayAdapter<BrowseItem>(context, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<android.widget.TextView>(R.id.item_text).text = item.name
            view.findViewById<android.widget.ImageView>(R.id.item_icon).setImageResource(item.icon)
            return view
        }
    }

    private fun browseFileStorage(path: String, isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        val currentDir = java.io.File(path)
        val loadingToast = android.widget.Toast.makeText(this, "Reading folder...", android.widget.Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val files = currentDir.listFiles() ?: emptyArray()
                val items = mutableListOf<BrowseItem>()

                for (file in files) {
                    if (file.name.startsWith(".")) continue
                    
                    if (file.isDirectory) {
                        items.add(BrowseItem(file.name, file.absolutePath, R.drawable.ic_folder, true))
                    } else if (!isDirectoryMode && isPlayable(file.name)) {
                        val icon = if (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8")) R.drawable.ic_playlist else R.drawable.ic_audio
                        items.add(BrowseItem(file.name, file.absolutePath, icon, false))
                    }
                }

                val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                runOnUiThread {
                    loadingToast.cancel()
                    
                    val title = if (isDirectoryMode) "Select Directory" else currentDir.name.ifEmpty { "Storage" }
                    
                    val adapter = BrowseAdapter(this, sortedItems)
                    val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title)

                    val dialogView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(16, 16, 16, 16)
                    }

                    val listView = android.widget.ListView(this).apply {
                        this.adapter = adapter
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            0, 1.0f
                        )
                    }
                    dialogView.addView(listView)

                    val buttonRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val dialog = builder.setView(dialogView).create()

                    fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(this).apply {
                        this.text = text
                        setOnClickListener {
                            onClick()
                            dialog.dismiss()
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        isFocusable = true
                    }

                    buttonRow.addView(createBtn("Back") { 
                        dialog.dismiss()
                        val parent = currentDir.parent
                        if (parent != null && parent != android.os.Environment.getExternalStorageDirectory().parent) {
                            browseFileStorage(parent, isSelectionMode, isDirectoryMode)
                        }
                    })
                    if (isDirectoryMode) {
                        buttonRow.addView(createBtn("Add Folder") {
                            addFilesToPlaylist(currentDir, false)
                        })
                    } else {
                        buttonRow.addView(createBtn("Add All") {
                            addFilesToPlaylist(currentDir, false)
                        })
                        buttonRow.addView(createBtn("Replace All") {
                            addFilesToPlaylist(currentDir, true)
                        })
                    }

                    dialogView.addView(buttonRow)

                    listView.setOnItemClickListener { _, _, which, _ ->
                        val item = sortedItems[which]
                        if (item.isDirectory) {
                            dialog.dismiss()
                            browseFileStorage(item.path, isSelectionMode, isDirectoryMode)
                        } else {
                            handleFileSelection(item.path, item.name)
                            dialog.dismiss()
                        }
                    }

                    dialog.show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    loadingToast.cancel()
                    android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleFileSelection(path: String, name: String) {
        val file = java.io.File(path)
        val options = arrayOf("Add to current playlist", "Replace current playlist")
        
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(options) { _, which ->
                addFilesToPlaylist(file, which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFilesToPlaylist(root: java.io.File, replace: Boolean) {
        val controller = mediaController ?: return
        val loadingToast = android.widget.Toast.makeText(this, "Processing files...", android.widget.Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            val itemsToAdd = mutableListOf<androidx.media3.common.MediaItem>()
            
            fun scanRecursive(file: java.io.File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { scanRecursive(it) }
                } else if (isPlayable(file.name)) {
                    if (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                itemsToAdd.addAll(parseM3uLocal(stream, android.net.Uri.fromFile(file)))
                            }
                        } catch (e: Exception) {}
                    } else {
                        val uri = android.net.Uri.fromFile(file).toString()
                        val title = file.name.substringBeforeLast(".")
                        itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                            .setMediaId(uri)
                            .setUri(android.net.Uri.fromFile(file))
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
                            .build())
                    }
                }
            }

            scanRecursive(root)

            runOnUiThread {
                loadingToast.cancel()
                if (itemsToAdd.isNotEmpty()) {
                    val sortedItems = itemsToAdd.sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }
                    
                    if (replace) {
                        controller.setMediaItems(sortedItems)
                    } else {
                        controller.addMediaItems(sortedItems)
                    }
                    controller.prepare()
                    controller.play()
                    android.widget.Toast.makeText(this, "Added ${sortedItems.size} items", android.widget.Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    android.widget.Toast.makeText(this, "No music found.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun isPlayable(filename: String): Boolean {
        val extensions = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".m3u", ".m3u8")
        return extensions.any { filename.lowercase().endsWith(it) }
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
