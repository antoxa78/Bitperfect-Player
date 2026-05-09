package com.example.bitperfectplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.IOException

@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {

    private var file: SmbFile? = null
    private var inputStream: SmbFileInputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val path = uri.toString()
        
        try {
            file = SmbFile(path)
            val inputStream = SmbFileInputStream(file!!)
            this.inputStream = inputStream
            
            if (dataSpec.position > 0) {
                val skipped = inputStream.skip(dataSpec.position)
                if (skipped < dataSpec.position) {
                    throw IOException("Could not skip to requested position")
                }
            }
            
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                val len = file?.length() ?: 0L
                if (len > 0) len - dataSpec.position else C.LENGTH_UNSET.toLong()
            }
        } catch (e: Exception) {
            throw IOException(e)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length 
                          else Math.min(bytesRemaining, length.toLong()).toInt()
        
        val bytesRead = try {
            inputStream?.read(buffer, offset, bytesToRead) ?: -1
        } catch (e: IOException) {
            throw IOException(e)
        }

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } catch (e: IOException) {
            throw IOException(e)
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
