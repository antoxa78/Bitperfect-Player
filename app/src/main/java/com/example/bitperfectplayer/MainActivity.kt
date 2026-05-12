package com.example.bitperfectplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : FragmentActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            addUrisToPlaylist(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun addUrisToPlaylist(uris: List<Uri>) {
        val controller = mediaController ?: return
        
        Thread {
            val allItems = mutableListOf<MediaItem>()
            for (uri in uris) {
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                if (fileName.endsWith(".m3u") || fileName.endsWith(".m3u8")) {
                    allItems.addAll(parseM3u(uri))
                } else {
                    allItems.add(createMediaItem(uri))
                }
            }

            runOnUiThread {
                if (allItems.isNotEmpty()) {
                    // Collect current items and add new ones to avoid issues with addMediaItems on some devices
                    val currentItems = mutableListOf<MediaItem>()
                    for (i in 0 until controller.mediaItemCount) {
                        currentItems.add(controller.getMediaItemAt(i))
                    }
                    currentItems.addAll(allItems)
                    
                    controller.setMediaItems(currentItems)
                    
                    if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                        controller.prepare()
                        controller.play()
                    }
                    Toast.makeText(this, "Playlist: ${currentItems.size} items total", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun createMediaItem(uri: Uri): MediaItem {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {}

        val lower = uri.toString().lowercase()
        val mimeType = when {
            lower.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
            else -> null
        }

        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(uri.lastPathSegment ?: "Unknown")
                    .build()
            )
            .build()
    }

    fun parseM3u(uri: Uri): List<MediaItem> {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                return parseM3uFromStream(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    fun parseM3uFromStream(inputStream: java.io.InputStream): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null
            
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXTINF:")) {
                    // Extract title after the comma
                    val commaIndex = trimmed.indexOf(",")
                    if (commaIndex != -1) {
                        currentTitle = trimmed.substring(commaIndex + 1)
                    }
                } else if (!trimmed.startsWith("#")) {
                    val itemUri = try {
                        when {
                            trimmed.startsWith("/") -> Uri.parse("file://$trimmed")
                            trimmed.startsWith("file://") || trimmed.startsWith("content://") ||
                            trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                            trimmed.startsWith("smb://") -> Uri.parse(trimmed)
                            else -> null
                        }
                    } catch (e: Exception) { null }

                    if (itemUri != null) {
                        val lowercaseUrl = trimmed.lowercase()
                        val mimeType = when {
                            lowercaseUrl.contains("flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                            lowercaseUrl.contains("mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                            lowercaseUrl.contains("aac") || lowercaseUrl.contains("aacp") -> androidx.media3.common.MimeTypes.AUDIO_AAC
                            else -> null
                        }

                        val metadataBuilder = MediaMetadata.Builder()
                        var finalTitle = currentTitle ?: itemUri.lastPathSegment ?: trimmed
                        
                        if (finalTitle.contains(" - ")) {
                            val parts = finalTitle.split(" - ", limit = 2)
                            metadataBuilder.setArtist(parts[0].trim())
                            finalTitle = parts[1].trim()
                        }
                        
                        items.add(
                            MediaItem.Builder()
                                .setMediaId(trimmed)
                                .setUri(itemUri)
                                .setMimeType(mimeType)
                                .setMediaMetadata(metadataBuilder.setTitle(finalTitle).build())
                                .build()
                        )
                    }
                    currentTitle = null // Reset for next item
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    fun pickFiles() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            intent.type = "audio/*"
            intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            
            // Define MIME types explicitly to include playlists
            val mimeTypes = arrayOf(
                "audio/*", 
                "application/octet-stream", 
                "application/x-mpegurl", 
                "audio/mpegurl",
                "audio/x-mpegurl"
            )
            intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimeTypes)
            
            startActivityForResult(intent, 2001)
        } catch (e: Exception) {
            Toast.makeText(this, "File manager not found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.let {
                if (it.clipData != null) {
                    val count = it.clipData!!.itemCount
                    for (i in 0 until count) {
                        uris.add(it.clipData!!.getItemAt(i).uri)
                    }
                } else if (it.data != null) {
                    uris.add(it.data!!)
                }
            }
            if (uris.isNotEmpty()) {
                addUrisToPlaylist(uris)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val fragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment
        fragment?.refreshRows()
    }

    override fun onDestroy() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
    
    fun getController(): MediaController? = mediaController
}
