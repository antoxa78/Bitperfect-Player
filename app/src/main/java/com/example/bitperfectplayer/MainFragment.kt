package com.example.bitperfectplayer

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import jcifs.smb.SmbFile
import org.json.JSONArray
import org.json.JSONObject

class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var controlsAdapter: ArrayObjectAdapter
    private lateinit var playlistAdapter: ArrayObjectAdapter
    private val PREFS_NAME = "SmbShares"
    private val KEY_SHARES = "shares"
    private val PREFS_SETTINGS = "AppSettings"
    private val KEY_SCREENSAVER = "screensaver_delay"
    private val KEY_RESUME = "resume_playback"
    private val KEY_RECENT = "recent_files"
    private val KEY_MUSIC_FOLDERS = "music_folders"
    private var hasAttemptedResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set some jCIFS properties for better compatibility
        System.setProperty("jcifs.smb.client.minVersion", "SMB1")
        System.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        System.setProperty("jcifs.smb.client.connTimeout", "10000")
        System.setProperty("jcifs.smb.client.sessionTimeout", "15000")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUIElements()
        loadRows()
        setupEventListeners()
        observePlaybackState()
    }

    private fun setupUIElements() {
        title = "Bitperfect Player"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.purple_700)
    }

    fun refreshRows() {
        if (::rowsAdapter.isInitialized) {
            rowsAdapter.clear()
            loadRows()
        }
    }

    private fun loadRows() {
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        val cardPresenter = CardPresenter { item ->
            if (item.mediaId.startsWith("smb://")) {
                handleSmbSelection(item.mediaId)
            } else if (item.mediaId.startsWith("content://")) {
                handleLocalSelection(item.mediaId, item.mediaMetadata.title.toString())
            }
        }

        // Section: Now Playing Controls
        controlsAdapter = ArrayObjectAdapter(cardPresenter)
        updateControlsRow()
        val controlsHeader = HeaderItem(0, "Now Playing")
        rowsAdapter.add(ListRow(controlsHeader, controlsAdapter))

        // Section: Actions
        val actionsAdapter = ArrayObjectAdapter(cardPresenter)
        actionsAdapter.add(createActionItem("Add Local Files", "Pick audio files from storage"))
        actionsAdapter.add(createActionItem("Add SMB Source", "Enter network share details"))
        val actionsHeader = HeaderItem(1, "Actions")
        rowsAdapter.add(ListRow(actionsHeader, actionsAdapter))

        // Section: Playlists
        playlistAdapter = ArrayObjectAdapter(cardPresenter)
        updatePlaylistRow()
        val playlistHeader = HeaderItem(6, "Playlists")
        rowsAdapter.add(ListRow(playlistHeader, playlistAdapter))

        // Section: Network Share
        val networkAdapter = ArrayObjectAdapter(cardPresenter)
        loadSavedSmbShares(networkAdapter)
        val networkHeader = HeaderItem(3, "Network Shares (SMB)")
        rowsAdapter.add(ListRow(networkHeader, networkAdapter))

        // Section: Local Music Library (Configured Folders)
        val localAdapter = ArrayObjectAdapter(cardPresenter)
        loadConfiguredLocalFolders(localAdapter)
        val localHeader = HeaderItem(2, "Local Music Library")
        rowsAdapter.add(ListRow(localHeader, localAdapter))

        // Section: Recent Files
        if (isRecentEnabled()) {
            val recentAdapter = ArrayObjectAdapter(cardPresenter)
            loadRecentFiles(recentAdapter)
            if (recentAdapter.size() > 0) {
                val recentHeader = HeaderItem(5, "Recently Played")
                rowsAdapter.add(ListRow(recentHeader, recentAdapter))
            }
        }

        // Section: Settings
        val settingsAdapter = ArrayObjectAdapter(cardPresenter)
        settingsAdapter.add(createActionItem("Screensaver", "Delay: ${getScreensaverLabel()}"))
        settingsAdapter.add(createActionItem("Resume Playback", if (isResumeEnabled()) "Enabled" else "Disabled"))
        settingsAdapter.add(createActionItem("Recent Files", if (isRecentEnabled()) "Enabled" else "Disabled"))
        settingsAdapter.add(createActionItem("Music Folders", "Manage local and network folders"))
        settingsAdapter.add(createActionItem("Scan Library", "Update folders and playlists"))
        val settingsHeader = HeaderItem(4, "Settings")
        rowsAdapter.add(ListRow(settingsHeader, settingsAdapter))

        adapter = rowsAdapter
    }

    private fun loadSavedSmbShares(adapter: ArrayObjectAdapter) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sharesJson = prefs.getString(KEY_SHARES, "[]")
        try {
            val jsonArray = JSONArray(sharesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ip = obj.getString("ip")
                val share = obj.getString("share")
                val user = obj.optString("user", "")
                val pass = obj.optString("pass", "")
                
                val uri = if (user.isNotEmpty()) {
                    val encUser = Uri.encode(user)
                    val encPass = Uri.encode(pass)
                    "smb://$encUser:$encPass@$ip/$share/"
                } else {
                    "smb://$ip/$share/"
                }
                adapter.add(createMediaItem("$share on $ip", "Network Share", uri))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveSmbShare(ip: String, share: String, user: String, pass: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sharesJson = prefs.getString(KEY_SHARES, "[]")
        try {
            val jsonArray = JSONArray(sharesJson)
            val newObj = JSONObject().apply {
                put("ip", ip)
                put("share", share)
                put("user", user)
                put("pass", pass)
            }
            jsonArray.put(newObj)
            prefs.edit().putString(KEY_SHARES, jsonArray.toString()).apply()
            refreshRows()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAddSmbDialog() {
        val activity = activity ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_smb, null)
        val editIp = view.findViewById<EditText>(R.id.edit_ip)
        val editShare = view.findViewById<EditText>(R.id.edit_share_name)
        val editUser = view.findViewById<EditText>(R.id.edit_username)
        val editPass = view.findViewById<EditText>(R.id.edit_password)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add SMB Share")
            .setView(view)
            .setPositiveButton("Add Manually") { _, _ ->
                val ip = editIp.text.toString().trim()
                val share = editShare.text.toString().trim().removePrefix("/").removeSuffix("/")
                val user = editUser.text.toString().trim()
                val pass = editPass.text.toString().trim()
                if (ip.isNotEmpty() && share.isNotEmpty()) {
                    saveSmbShare(ip, share, user, pass)
                } else {
                    Toast.makeText(activity, "IP and Share Name are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Scan Shares") { _, _ ->
                val ip = editIp.text.toString().trim()
                val user = editUser.text.toString().trim()
                val pass = editPass.text.toString().trim()
                if (ip.isNotEmpty()) {
                    scanSharesOnIp(ip, user, pass)
                } else {
                    scanNetworkForSmb(user, pass)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanSharesOnIp(ip: String, user: String, pass: String) {
        val activity = activity ?: return
        val loadingToast = Toast.makeText(activity, "Scanning shares on $ip...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val credentials = if (user.isNotEmpty()) {
                    val encUser = Uri.encode(user)
                    val encPass = Uri.encode(pass)
                    "$encUser:$encPass@"
                } else ""
                
                val cleanIp = ip.removeSuffix("/")
                val uri = "smb://$credentials$cleanIp/"
                val server = SmbFile(uri)
                val shares = try {
                    server.listFiles() ?: emptyArray()
                } catch (e: Exception) {
                    if (user.isNotEmpty()) {
                         // Fallback to anonymous if credentials failed
                         SmbFile("smb://$cleanIp/").listFiles() ?: emptyArray()
                    } else throw e
                }
                
                val shareNames = shares
                    .map { it.name.removeSuffix("/") }
                    .filter { !it.endsWith("$") }
                    .toTypedArray()

                val shareItems = shareNames.map { name ->
                    BrowseItem(name, "", R.drawable.ic_network, true)
                }

                activity.runOnUiThread {
                    loadingToast.cancel()
                    if (shareNames.isEmpty()) {
                        Toast.makeText(activity, "No public shares found on $ip.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }

                    val adapter = BrowseAdapter(activity, shareItems)
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Select Share on $ip")
                        .setAdapter(adapter) { _, which ->
                            saveSmbShare(ip, shareNames[which], user, pass)
                        }
                        .setNegativeButton("Back") { _, _ -> showAddSmbDialog() }
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    loadingToast.cancel()
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Scan Failed")
                        .setMessage("Could not connect to $ip.\nDetails: ${e.localizedMessage ?: "Unknown error"}")
                        .setPositiveButton("Retry with Credentials") { _, _ ->
                            showAddSmbDialog() // Show dialog again to enter user/pass
                        }
                        .setNegativeButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun loadConfiguredLocalFolders(adapter: ArrayObjectAdapter) {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = settings.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                adapter.add(createMediaItem(obj.getString("name"), "Local Folder", obj.getString("uri")))
            }
        } catch (e: Exception) {}
    }

    private fun updateControlsRow() {
        controlsAdapter.clear()
        val activity = activity as? MainActivity
        val controller = activity?.getController()
        
        val currentItem = controller?.currentMediaItem
        val title = currentItem?.mediaMetadata?.title?.toString() ?: "Nothing Playing"
        val subtitle = currentItem?.mediaMetadata?.artist?.toString() ?: ""

        controlsAdapter.add(createActionItem("NOW: $title", subtitle))

        // Add Resume Last Played if enabled and not currently playing anything else
        if (isResumeEnabled() && (controller == null || controller.mediaItemCount == 0)) {
            val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val lastUri = settings.getString("last_played_uri", null)
            if (lastUri != null) {
                val lastTitle = settings.getString("last_played_title", "Last Played") ?: "Last Played"
                controlsAdapter.add(createActionItem("Resume Last Played", lastTitle))
            }
        }
    }

    private fun updatePlaylistRow() {
        if (!::playlistAdapter.isInitialized) return
        playlistAdapter.clear()
        
        val context = context ?: return
        
        // Scan Local Folders for Playlists
        val settings = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = settings.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val uri = obj.getString("uri")
                if (uri.startsWith("content://")) {
                    findPlaylistsLocal(uri)
                }
            }
        } catch (e: Exception) {}

        // Scan SMB Shares for Playlists
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sharesJson = prefs.getString(KEY_SHARES, "[]")
        try {
            val jsonArray = JSONArray(sharesJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val ip = obj.getString("ip")
                val share = obj.getString("share")
                val user = obj.optString("user", "")
                val pass = obj.optString("pass", "")
                val uri = if (user.isNotEmpty()) {
                    val encUser = Uri.encode(user)
                    val encPass = Uri.encode(pass)
                    "smb://$encUser:$encPass@$ip/$share/"
                } else {
                    "smb://$ip/$share/"
                }
                findPlaylistsSmb(uri)
            }
        } catch (e: Exception) {}
    }

    private fun findPlaylistsLocal(uriString: String) {
        val context = context ?: return
        val rootUri = Uri.parse(uriString)
        Thread {
            try {
                val treeUri = android.provider.DocumentsContract.getTreeDocumentId(rootUri)
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, treeUri)
                val projection = arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8")) {
                            val docId = cursor.getString(idCol)
                            val itemUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, docId).toString()
                            activity?.runOnUiThread {
                                playlistAdapter.add(createMediaItem(name, "Local Playlist", itemUri))
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun findPlaylistsSmb(uri: String) {
        Thread {
            try {
                val dir = SmbFile(uri)
                val files = dir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.name.lowercase().endsWith(".m3u") || f.name.lowercase().endsWith(".m3u8")) {
                        val name = f.name
                        val path = f.path
                        activity?.runOnUiThread {
                            playlistAdapter.add(createMediaItem(name, "SMB Playlist", path))
                        }
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    private fun observePlaybackState() {
        val activity = activity as? MainActivity
        val checkController = object : Runnable {
            override fun run() {
                val controller = activity?.getController()
                if (controller != null) {
                    controller.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                            updateControlsRow()
                            updatePlaylistRow()
                        }
                    })
                    updateControlsRow()
                    updatePlaylistRow()

                    // Auto-resume if enabled and nothing is playing
                    if (isResumeEnabled() && controller.mediaItemCount == 0 && !hasAttemptedResume) {
                        hasAttemptedResume = true
                        resumeLastPlayed()
                    }
                } else {
                    view?.postDelayed(this, 500)
                }
            }
        }
        view?.post(checkController)
    }

    private fun createActionItem(title: String, description: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("action:$title")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(description)
                    .build()
            )
            .build()
    }

    private fun createMediaItem(title: String, artist: String, uri: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            if (item is MediaItem) {
                if (item.mediaId.startsWith("action:")) {
                    handleAction(item.mediaId)
                } else if (row is ListRow && row.headerItem.id == 6L) { // Playlists
                    if (item.mediaId.startsWith("smb://")) {
                        handleSmbSelection(item.mediaId)
                    } else if (item.mediaId.startsWith("content://")) {
                        handleLocalSelection(item.mediaId, item.mediaMetadata.title.toString())
                    }
                } else if (item.mediaId.startsWith("smb://")) {
                    if (item.mediaId.endsWith("/")) {
                        browseSmbDirectory(item.mediaId)
                    } else {
                        // It's an SMB file, play it or show options
                        handleSmbSelection(item.mediaId)
                    }
                } else if (item.mediaId.startsWith("content://")) {
                    browseLocalDirectory(item.mediaId)
                } else if (row is ListRow) {
                    val activity = activity as? MainActivity
                    activity?.getController()?.let { controller ->
                        val adapter = row.adapter as ArrayObjectAdapter
                        val mediaItems = mutableListOf<MediaItem>()
                        var startIndex = 0
                        for (i in 0 until adapter.size()) {
                            val mediaItem = adapter.get(i) as MediaItem
                            if (!mediaItem.mediaId.startsWith("action:")) {
                                mediaItems.add(mediaItem)
                                if (mediaItem == item) {
                                    startIndex = mediaItems.size - 1
                                }
                            }
                        }
                        if (mediaItems.isNotEmpty()) {
                            controller.setMediaItems(mediaItems, startIndex, 0)
                            controller.prepare()
                            controller.play()
                            
                            val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                            startActivity(intent)
                        }
                    }
                }
            }
        }
    }

    private fun browseSmbDirectory(uri: String) {
        val context = activity ?: return
        if (context.isFinishing || context.isDestroyed) return
        
        val loadingToast = Toast.makeText(context, "Accessing network share...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val dir = SmbFile(uri)
                val files = try {
                    dir.listFiles() ?: emptyArray()
                } catch (e: Exception) {
                    // Handle potential auth errors or connection timeouts
                    emptyArray()
                }

                // Sort: folders first, then files, case-insensitive
                val filteredFiles = files.filter { 
                    if (it.isDirectory()) {
                        !it.name.startsWith(".") && !it.name.contains("thumbnail", ignoreCase = true)
                    } else {
                        isPlayable(it.name)
                    }
                }
                val sortedFiles = filteredFiles.sortedWith(compareBy({ !it.isDirectory() }, { it.name.lowercase() }))
                val browseItems = sortedFiles.map { 
                    val isDir = it.isDirectory()
                    val icon = if (isDir) R.drawable.ic_folder 
                              else if (it.name.lowercase().endsWith(".m3u") || it.name.lowercase().endsWith(".m3u8")) R.drawable.ic_playlist
                              else R.drawable.ic_audio
                    BrowseItem(it.name, it.path, icon, isDir)
                }

                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    
                    if (browseItems.isEmpty()) {
                        Toast.makeText(context, "This folder is empty or inaccessible.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val adapter = BrowseAdapter(context, browseItems)
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(dir.name.removeSuffix("/"))
                        .setAdapter(adapter) { _, which ->
                            val item = browseItems[which]
                            if (item.isDirectory) {
                                browseSmbDirectory(item.path)
                            } else {
                                handleSmbSelection(item.path)
                            }
                        }
                        .setNegativeButton("Back", null)
                    
                    val dialog = builder.create()
                    dialog.show()
                    
                    dialog.listView.setOnItemLongClickListener { _, _, which, _ ->
                        val item = browseItems[which]
                        if (item.isDirectory) {
                            handleSmbSelection(item.path)
                            dialog.dismiss()
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    val activity = activity ?: return@runOnUiThread
                    loadingToast.cancel()
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("SMB Error")
                        .setMessage("Failed to browse: ${e.localizedMessage}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun browseLocalDirectory(uriString: String) {
        val context = activity ?: return
        val uri = Uri.parse(uriString)
        val loadingToast = Toast.makeText(context, "Scanning folder...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val treeUri = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeUri)
                
                val projection = arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                
                val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                val items = mutableListOf<BrowseItem>()

                cursor?.use {
                    val idCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = it.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                    
                    while (it.moveToNext()) {
                        val id = it.getString(idCol)
                        val name = it.getString(nameCol)
                        val mime = it.getString(mimeCol)
                        val isDir = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                        
                        if (isDir) {
                            // Filter thumbnails and hidden folders
                            if (name.startsWith(".") || name.contains("thumbnail", ignoreCase = true)) continue
                            
                            // Only add if it contains music/playlists or subfolders
                            if (hasPlayableContent(context, uri, id)) {
                                items.add(BrowseItem(name, android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, id).toString(), R.drawable.ic_folder, true))
                            }
                        } else if (isPlayable(name)) {
                            val icon = if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8")) R.drawable.ic_playlist else R.drawable.ic_audio
                            items.add(BrowseItem(name, android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, id).toString(), icon, false))
                        }
                    }
                }

                // Sort: folders first
                val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                activity?.runOnUiThread {
                    loadingToast.cancel()
                    if (sortedItems.isEmpty()) {
                        Toast.makeText(context, "No playable files found.", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val adapter = BrowseAdapter(context, sortedItems)
                    val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Browse Folder")
                        .setAdapter(adapter) { _, which ->
                            val item = sortedItems[which]
                            if (item.isDirectory) {
                                browseLocalDirectory(item.path)
                            } else {
                                handleLocalSelection(item.path, item.name)
                            }
                        }
                        .setNegativeButton("Back", null)
                    
                    val dialog = builder.create()
                    dialog.show()

                    dialog.listView.setOnItemLongClickListener { _, _, which, _ ->
                        val item = sortedItems[which]
                        if (item.isDirectory) {
                            handleLocalSelection(item.path, item.name)
                            dialog.dismiss()
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleLocalSelection(uriString: String, name: String) {
        val context = activity ?: return
        val options = arrayOf("Add to current playlist", "Replace current playlist")
        
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(options) { _, which ->
                addLocalToPlaylist(uriString, name, which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addLocalToPlaylist(uriString: String, name: String, replace: Boolean) {
        val context = activity ?: return
        val mainActivity = activity as? MainActivity
        val controller = mainActivity?.getController() ?: return
        val rootUri = Uri.parse(uriString)

        Thread {
            val itemsToAdd = mutableListOf<MediaItem>()
            
            fun scanRecursive(uri: Uri, isDir: Boolean, displayName: String) {
                if (isDir) {
                    try {
                        android.provider.DocumentsContract.getTreeDocumentId(uri)
                        val docId = android.provider.DocumentsContract.getDocumentId(uri)
                        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
                        val projection = arrayOf(
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                        )
                        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val childId = cursor.getString(idCol)
                                val childName = cursor.getString(nameCol)
                                val childMime = cursor.getString(mimeCol)
                                val childUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                                scanRecursive(childUri, childMime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR, childName)
                            }
                        }
                    } catch (e: Exception) {
                        // Fallback if buildChildDocumentsUriUsingTree fails (might not be a tree URI)
                        if (isPlayable(displayName)) {
                            itemsToAdd.add(createMediaItem(displayName, "Local Library", uri.toString()))
                        }
                    }
                } else if (isPlayable(displayName)) {
                    if (displayName.lowercase().endsWith(".m3u") || displayName.lowercase().endsWith(".m3u8")) {
                        itemsToAdd.addAll(mainActivity.parseM3u(uri))
                    } else {
                        itemsToAdd.add(createMediaItem(displayName, "Local Library", uri.toString()))
                    }
                }
            }

            // Check if it's a directory first
            val isInitialDir = try {
                context.contentResolver.getType(rootUri) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
            } catch (e: Exception) { false }
            
            if (name.lowercase().endsWith(".m3u") || name.lowercase().endsWith(".m3u8")) {
                itemsToAdd.addAll(mainActivity.parseM3u(rootUri))
            } else if (isInitialDir) {
                scanRecursive(rootUri, true, name)
            } else {
                itemsToAdd.add(createMediaItem(name, "Local Library", uriString))
            }

            activity?.runOnUiThread {
                if (itemsToAdd.isNotEmpty()) {
                    if (replace) {
                        controller.setMediaItems(itemsToAdd)
                        controller.prepare()
                        controller.play()
                        val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                        startActivity(intent)
                    } else {
                        controller.addMediaItems(itemsToAdd)
                        Toast.makeText(context, "Added ${itemsToAdd.size} items to playlist", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "No playable music files found.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleSmbSelection(uri: String) {
        val context = activity ?: return
        if (context.isFinishing) return

        val loadingToast = Toast.makeText(context, "Checking file info...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val file = SmbFile(uri)
                file.isDirectory
                val fileName = file.name.removeSuffix("/")

                activity?.runOnUiThread {
                    loadingToast.cancel()
                    if (activity?.isFinishing == true) return@runOnUiThread

                    val options = arrayOf("Add to current playlist", "Replace current playlist")

                    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(fileName)
                        .setItems(options) { _, which ->
                            addSmbToPlaylist(file, replace = (which == 1))
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(context, "Error accessing file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun addSmbToPlaylist(file: SmbFile, replace: Boolean) {
        val context = activity ?: return
        val loadingToast = Toast.makeText(context, "Processing...", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val mainActivity = activity as? MainActivity
                val itemsToAdd = mutableListOf<MediaItem>()
                
                fun scanRecursive(f: SmbFile) {
                    if (f.isDirectory()) {
                        f.listFiles()?.forEach { scanRecursive(it) }
                    } else if (isPlayable(f.name)) {
                        if (f.name.lowercase().endsWith(".m3u") || f.name.lowercase().endsWith(".m3u8")) {
                            f.getInputStream().use { itemsToAdd.addAll(mainActivity?.parseM3uFromStream(it) ?: emptyList()) }
                        } else {
                            itemsToAdd.add(createMediaItem(f.name, "SMB Share", f.path))
                        }
                    }
                }

                scanRecursive(file)

                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    if (itemsToAdd.isNotEmpty()) {
                        val controller = mainActivity?.getController()
                        if (controller != null) {
                            if (replace) {
                                controller.setMediaItems(itemsToAdd)
                                controller.prepare()
                                controller.play()
                                val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                                startActivity(intent)
                            } else {
                                controller.addMediaItems(itemsToAdd)
                                Toast.makeText(context, "Added ${itemsToAdd.size} items to playlist", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, "No playable music files found.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    if (activity?.isFinishing == true) return@runOnUiThread
                    loadingToast.cancel()
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isPlayable(filename: String): Boolean {
        val extensions = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".m3u", ".m3u8")
        return extensions.any { filename.lowercase().endsWith(it) }
    }

    private fun hasPlayableContent(context: Context, treeUri: Uri, docId: String): Boolean {
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && !name.contains("thumbnail", ignoreCase = true)) {
                            return true // Non-hidden subfolder might have content
                        }
                    } else if (isPlayable(name)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {}
        return false
    }

    private fun getScreensaverLabel(): String {
        val mins = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_SCREENSAVER, 0)
        return when (mins) {
            0 -> "Off"
            1 -> "1 min"
            5 -> "5 min"
            15 -> "15 min"
            else -> "$mins min"
        }
    }

    private fun isResumeEnabled() = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_RESUME, false)

    private fun isRecentEnabled() = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        .getBoolean(KEY_RECENT, true)

    private fun loadRecentFiles(adapter: ArrayObjectAdapter) {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val recentJson = settings.getString("recent_list", "[]")
        try {
            val recentArray = JSONArray(recentJson)
            for (i in 0 until recentArray.length()) {
                val obj = recentArray.getJSONObject(i)
                adapter.add(createMediaItem(obj.getString("title"), obj.getString("artist"), obj.getString("uri")))
            }
        } catch (e: Exception) {}
    }

    private fun showScreensaverSettings() {
        val options = arrayOf("Delay: Off", "Delay: 1 min", "Delay: 5 min", "Delay: 15 min")
        val values = intArrayOf(0, 1, 5, 15)
        val current = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_SCREENSAVER, 0)
        var checkedItem = values.indexOf(current)
        if (checkedItem == -1) checkedItem = 0

        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Screensaver Settings")
            .setItems(options) { _, which ->
                requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SCREENSAVER, values[which]).apply()
                refreshRows()
            }
            .show()
    }

    private fun toggleResumePlayback() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_RESUME, false)
        prefs.edit().putBoolean(KEY_RESUME, !current).apply()
        refreshRows()
    }

    private fun toggleRecentFiles() {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val current = prefs.getBoolean(KEY_RECENT, true)
        prefs.edit().putBoolean(KEY_RECENT, !current).apply()
        refreshRows()
    }

    private data class BrowseItem(val name: String, val path: String, val icon: Int, val isDirectory: Boolean)

    private class BrowseAdapter(context: Context, items: List<BrowseItem>) : 
        android.widget.ArrayAdapter<BrowseItem>(context, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<android.widget.TextView>(R.id.item_text).text = item.name
            view.findViewById<android.widget.ImageView>(R.id.item_icon).setImageResource(item.icon)
            return view
        }
    }

    private fun manageMusicFolders() {
        val options = arrayOf("Add Local Music Folder", "Add SMB Network Share", "View/Remove Folders")
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Music Folders")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickLocalFolder()
                    1 -> showAddSmbDialog()
                    2 -> viewConfiguredFolders()
                }
            }
            .show()
    }

    private fun pickLocalFolder() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            // Fallback for devices without DocumentUI/SAF picker
            AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Compatible File Manager Required")
                .setMessage("Your device does not have a standard folder picker. Please install a file manager like 'X-plore' or 'MiXplorer' that supports SAF (Storage Access Framework).")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                saveMusicFolder(uri.toString(), true)
            }
        }
    }

    private fun saveMusicFolder(uri: String, isLocal: Boolean) {
        val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = prefs.getString(KEY_MUSIC_FOLDERS, "[]")
        try {
            val jsonArray = JSONArray(foldersJson)
            // Check if already exists
            for (i in 0 until jsonArray.length()) {
                if (jsonArray.getJSONObject(i).getString("uri") == uri) return
            }
            val newObj = JSONObject().apply {
                put("uri", uri)
                put("isLocal", isLocal)
                val rawName = Uri.parse(uri).lastPathSegment ?: uri
                val cleanName = if (rawName.contains(":")) rawName.substringAfterLast(":") else rawName
                put("name", cleanName)
            }
            jsonArray.put(newObj)
            prefs.edit().putString(KEY_MUSIC_FOLDERS, jsonArray.toString()).apply()
            refreshRows()
            Toast.makeText(requireContext(), "Folder added", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.getInetAddresses()
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {}
        return null
    }

    private fun scanNetworkForSmb(user: String, pass: String) {
        val activity = activity ?: return
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Toast.makeText(activity, "Could not determine local IP", Toast.LENGTH_SHORT).show()
            return
        }
        val prefix = localIp.substring(0, localIp.lastIndexOf(".") + 1)
        
        val foundIps = mutableListOf<String>()
        val loadingToast = Toast.makeText(activity, "Scanning local network for SMB servers...", Toast.LENGTH_LONG)
        loadingToast.show()
        
        Thread {
            val timeout = 300 // ms
            val pool = java.util.concurrent.Executors.newFixedThreadPool(30)
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            
            for (i in 1..254) {
                val testIp = prefix + i
                futures.add(pool.submit {
                    try {
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress(testIp, 445), timeout)
                        socket.close()
                        synchronized(foundIps) { foundIps.add(testIp) }
                    } catch (e: Exception) {}
                })
            }
            
            for (f in futures) {
                try { f.get() } catch (e: Exception) {}
            }
            pool.shutdown()
            
            activity.runOnUiThread {
                loadingToast.cancel()
                if (foundIps.isEmpty()) {
                    Toast.makeText(activity, "No SMB servers found on network.", Toast.LENGTH_LONG).show()
                } else {
                    val serverItems = foundIps.map { BrowseItem(it, it, R.drawable.ic_network, true) }
                    val adapter = BrowseAdapter(activity, serverItems)
                    AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Select SMB Server")
                        .setAdapter(adapter) { _, which ->
                            val selectedIp = foundIps[which]
                            // Prompt for credentials for the selected IP
                            showCredentialsDialog(selectedIp)
                        }
                        .setNegativeButton("Back") { _, _ -> showAddSmbDialog() }
                        .show()
                }
            }
        }.start()
    }

    private fun showCredentialsDialog(ip: String) {
        val activity = activity ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_smb, null)
        val editIp = view.findViewById<EditText>(R.id.edit_ip)
        val editShare = view.findViewById<EditText>(R.id.edit_share_name)
        val editUser = view.findViewById<EditText>(R.id.edit_username)
        val editPass = view.findViewById<EditText>(R.id.edit_password)
        
        editIp.setText(ip)
        editIp.isEnabled = false // Lock IP

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Credentials for $ip")
            .setView(view)
            .setPositiveButton("Scan Shares") { _, _ ->
                val user = editUser.text.toString().trim()
                val pass = editPass.text.toString().trim()
                scanSharesOnIp(ip, user, pass)
            }
            .setNeutralButton("Add Manually") { _, _ ->
                val share = editShare.text.toString().trim().removePrefix("/").removeSuffix("/")
                val user = editUser.text.toString().trim()
                val pass = editPass.text.toString().trim()
                if (share.isNotEmpty()) {
                    saveSmbShare(ip, share, user, pass)
                } else {
                    Toast.makeText(activity, "Share Name is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Back", { _, _ -> scanNetworkForSmb("", "") })
            .show()
    }

    private fun viewConfiguredFolders() {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val foldersJson = prefs.getString(KEY_MUSIC_FOLDERS, "[]")
        val smbJson = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SHARES, "[]")
        
        val browseItems = mutableListOf<BrowseItem>()
        val folderObjects = mutableListOf<JSONObject>()
        
        try {
            val localFolders = JSONArray(foldersJson)
            for (i in 0 until localFolders.length()) {
                val obj = localFolders.getJSONObject(i)
                folderObjects.add(obj)
                browseItems.add(BrowseItem("[Local] " + obj.getString("name"), obj.getString("uri"), R.drawable.ic_folder, true))
            }
            
            val smbFolders = JSONArray(smbJson)
            for (i in 0 until smbFolders.length()) {
                val obj = smbFolders.getJSONObject(i)
                val folderObj = JSONObject().apply {
                    put("uri", "smb://${obj.getString("ip")}/${obj.getString("share")}/")
                    put("isLocal", false)
                    put("isSmb", true)
                    put("index", i)
                }
                folderObjects.add(folderObj)
                browseItems.add(BrowseItem("[SMB] ${obj.getString("share")} on ${obj.getString("ip")}", folderObj.getString("uri"), R.drawable.ic_network, true))
            }
        } catch (e: Exception) {}

        if (browseItems.isEmpty()) {
            Toast.makeText(context, "No folders configured", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = BrowseAdapter(context, browseItems)
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Manage Folders (Select to Remove)")
            .setAdapter(adapter) { _, which ->
                val item = browseItems[which]
                AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove Folder")
                    .setMessage("Are you sure you want to remove '${item.name}'?")
                    .setPositiveButton("Remove") { _, _ ->
                        removeFolder(folderObjects[which])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun removeFolder(obj: JSONObject) {
        if (obj.optBoolean("isSmb")) {
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_SHARES, "[]"))
            val index = obj.getInt("index")
            val newArray = JSONArray()
            for (i in 0 until jsonArray.length()) {
                if (i != index) newArray.put(jsonArray.get(i))
            }
            prefs.edit().putString(KEY_SHARES, newArray.toString()).apply()
        } else {
            val prefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_MUSIC_FOLDERS, "[]"))
            val newArray = JSONArray()
            val uriToRemove = obj.getString("uri")
            for (i in 0 until jsonArray.length()) {
                if (jsonArray.getJSONObject(i).getString("uri") != uriToRemove) {
                    newArray.put(jsonArray.get(i))
                }
            }
            prefs.edit().putString(KEY_MUSIC_FOLDERS, newArray.toString()).apply()
        }
        refreshRows()
        Toast.makeText(requireContext(), "Folder removed", Toast.LENGTH_SHORT).show()
    }

    private fun resumeLastPlayed() {
        val settings = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val uri = settings.getString("last_played_uri", null) ?: return
        val pos = settings.getLong("last_played_pos", 0)
        val title = settings.getString("last_played_title", "Music") ?: "Music"
        val artist = settings.getString("last_played_artist", "") ?: ""
        
        val mediaItem = createMediaItem(title, artist, uri)
        
        val activity = activity as? MainActivity
        activity?.getController()?.let { controller ->
            controller.setMediaItem(mediaItem)
            controller.seekTo(pos)
            controller.prepare()
            controller.play()
            
            val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
            startActivity(intent)
        }
    }

    private fun handleAction(actionId: String) {
        val activity = activity as? MainActivity
        when {
            actionId == "action:Add Local Files" -> {
                activity?.pickFiles()
            }
            actionId == "action:Add SMB Source" -> {
                showAddSmbDialog()
            }
            actionId == "action:Screensaver" -> {
                showScreensaverSettings()
            }
            actionId == "action:Resume Playback" -> {
                toggleResumePlayback()
            }
            actionId == "action:Recent Files" -> {
                toggleRecentFiles()
            }
            actionId == "action:Music Folders" -> {
                manageMusicFolders()
            }
            actionId == "action:Scan Library" -> {
                updatePlaylistRow()
                refreshRows()
                Toast.makeText(requireContext(), "Music library updated", Toast.LENGTH_SHORT).show()
            }
            actionId == "action:Resume Last Played" -> {
                resumeLastPlayed()
            }
            actionId.startsWith("action:NOW:") -> {
                val intent = android.content.Intent(requireContext(), NowPlayingActivity::class.java)
                startActivity(intent)
            }
        }
    }
}
