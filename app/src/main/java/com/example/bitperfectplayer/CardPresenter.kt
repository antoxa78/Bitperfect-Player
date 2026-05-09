package com.example.bitperfectplayer

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.media3.common.MediaItem

class CardPresenter(private val onLongClickListener: ((MediaItem) -> Unit)? = null) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return
        val cardView = viewHolder.view as? ImageCardView ?: return
        val context = cardView.context
        
        cardView.titleText = mediaItem.mediaMetadata.title
        cardView.contentText = mediaItem.mediaMetadata.artist ?: mediaItem.mediaMetadata.subtitle
        cardView.setMainImageDimensions(313, 176)
        cardView.mainImageView?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        cardView.mainImageView?.setPadding(24, 24, 24, 24)
        cardView.mainImageView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set icon based on media ID or extras
        val iconRes = when {
            mediaItem.mediaId.startsWith("action:Add Local") -> R.drawable.ic_add
            mediaItem.mediaId.startsWith("action:Add SMB") -> R.drawable.ic_network
            mediaItem.mediaId.startsWith("action:Screensaver") -> R.drawable.ic_settings
            mediaItem.mediaId.startsWith("action:Resume") -> android.R.drawable.ic_media_play
            mediaItem.mediaId.startsWith("action:Recent") -> android.R.drawable.ic_menu_recent_history
            mediaItem.mediaId.startsWith("action:Music Folders") -> R.drawable.ic_folder
            mediaItem.mediaId.startsWith("smb://") -> R.drawable.ic_network
            mediaItem.mediaId.startsWith("content://") -> R.drawable.ic_folder
            mediaItem.mediaId.lowercase().endsWith(".m3u") || 
            mediaItem.mediaId.lowercase().endsWith(".m3u8") ||
            mediaItem.mediaMetadata.artist?.toString()?.contains("Playlist") == true -> R.drawable.ic_playlist
            mediaItem.mediaId.startsWith("action:NOW:") -> android.R.drawable.ic_media_play
            else -> R.drawable.ic_audio
        }
        cardView.mainImage = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)

        cardView.setOnLongClickListener {
            onLongClickListener?.invoke(mediaItem)
            true
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as? ImageCardView
        cardView?.mainImage = null
        cardView?.setOnLongClickListener(null)
    }
}
