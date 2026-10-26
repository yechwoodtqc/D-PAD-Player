package com.example.dpadplayer

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.provider.MediaStore
import com.example.dpadplayer.playback.Track
import java.io.File
import com.example.dpadplayer.db.AppDatabase

/**
 * Scans all MediaStore audio volumes (external + internal) and enriches each
 * track with full ID3/VorbisComment metadata via MediaMetadataRetriever.
 *
 * Strategy (mirrors Auxio's approach, adapted for Android MediaStore):
 *  1. Query MediaStore for file URIs + basic columns (fast bulk read).
 *  2. For each file, open MediaMetadataRetriever to read proper ID3 tags:
 *       title, artist, albumArtist, album, track#, disc#, year, genre, sortTitle, etc.
 *  3. Fall back to MediaStore column values when a tag is absent.
 *  4. Include is_pending files (freshly copied via adb push) via setIncludePending.
 *  5. Filter: duration >= 30 s OR duration IS NULL (pending files have NULL duration).
 */
object MediaStoreScanner {

    /** Optional coroutine scope for background tasks (set by caller). If null a default IO scope is used. */
    @Volatile
    var scope: CoroutineScope? = null

    private val defaultScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }


    suspend fun loadTracks(context: Context, sortOrder: String = "title"): List<Track> {
        val raw = collectRawRows(context)
        // Load track cache to avoid re-enriching files that were previously processed.
        val db = try { AppDatabase.getInstance(context) } catch (_: Exception) { null }
        val rawUris = raw.map { it.uri.toString() }
        val cacheRows = if (db != null && rawUris.isNotEmpty()) {
            val chunkSize = 900
            rawUris
                .chunked(chunkSize)
                .flatMap { chunk -> db.trackCacheDao().getBySourceUris(chunk) }
        } else {
            emptyList()
        }
        val cacheMap = cacheRows.associateBy { it.sourceUri }

        val tracks = raw.map { row ->
            val cached = cacheMap[row.uri.toString()]
            if (cached != null) {
                // Build Track from cached metadata without opening the file
                val artUri = if (cached.albumArtPath.isNotBlank()) android.net.Uri.fromFile(File(cached.albumArtPath)) else Track.albumArtUri(cached.albumId)
                Track(
                    id = row.id,
                    uri = row.uri,
                    filePath = row.msData,
                    title = cached.title,
                    sortTitle = cached.sortTitle,
                    artist = cached.artist,
                    sortArtist = cached.sortArtist,
                    albumArtist = cached.albumArtist,
                    sortAlbumArtist = cached.sortAlbumArtist,
                    album = cached.album,
                    sortAlbum = cached.sortAlbum,
                    albumId = cached.albumId,
                    trackNumber = cached.trackNumber,
                    discNumber = cached.discNumber,
                    year = cached.year,
                    genre = cached.genre,
                    duration = cached.duration,
                    dateAdded = cached.dateAdded,
                    albumArtUri = artUri,
                    mediaStoreAlbumArtUri = Track.albumArtUri(row.msAlbumId),
                )
            } else {
                enrichWithRetriever(row)
            }
        }
        return applySortOrder(tracks, sortOrder)
    }

    // ── Step 1: MediaStore bulk query ─────────────────────────────────────────

    private data class RawRow(
        val id: Long,
        val uri: android.net.Uri,
        val msData: String,
        val msTitle: String,
        val msArtist: String,
        val msAlbum: String,
        val msAlbumId: Long,
        val msTrack: Int,
        val msDuration: Long,
        val msDateAdded: Long,
        val isInternal: Boolean,
    )

    private suspend fun collectRawRows(context: Context): List<RawRow> {
        val projection = arrayOf(
            MediaStore.Audio.AudioColumns._ID,
            MediaStore.Audio.AudioColumns.DATA,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.ALBUM_ID,
            MediaStore.Audio.AudioColumns.TRACK,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.AudioColumns.DATE_ADDED,
            MediaStore.Audio.AudioColumns.IS_MUSIC,
        )

        val selection =
            "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0" +
            " AND (${MediaStore.Audio.AudioColumns.DURATION} >= 30000" +
            " OR ${MediaStore.Audio.AudioColumns.DURATION} IS NULL)"

        val baseUris = mutableListOf<Pair<android.net.Uri, Boolean>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Query each external MediaStore volume explicitly (includes removable SD cards)
            try {
                val volumes = MediaStore.getExternalVolumeNames(context)
                for (vol in volumes) {
                    baseUris.add(MediaStore.Audio.Media.getContentUri(vol) to false)
                }
            } catch (_: Exception) {
                // Fall back if getExternalVolumeNames is unavailable or fails
                baseUris.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false)
            }
            // Always include internal volume as well
            baseUris.add(MediaStore.Audio.Media.INTERNAL_CONTENT_URI to true)
        } else {
            baseUris.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false)
            baseUris.add(MediaStore.Audio.Media.INTERNAL_CONTENT_URI to true)
        }

        val rows = mutableListOf<RawRow>()
        val seenUris = mutableSetOf<String>()

        for ((baseUri, isInternal) in baseUris) {
            val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.setIncludePending(baseUri) else baseUri
            try {
                context.contentResolver.query(
                    queryUri, projection, selection, null,
                    "${MediaStore.Audio.AudioColumns.TITLE} ASC"
                )?.use { c ->
                    val colId       = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns._ID)
                    val colData     = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATA)
                    val colTitle    = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TITLE)
                    val colArtist   = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
                    val colAlbum    = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM)
                    val colAlbumId  = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID)
                    val colTrack    = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.TRACK)
                    val colDur      = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
                    val colAdded    = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DATE_ADDED)

                    while (c.moveToNext()) {
                        val id = c.getLong(colId)
                        val rowUri = ContentUris.withAppendedId(baseUri, id)
                        if (!seenUris.add(rowUri.toString())) continue
                        rows.add(RawRow(
                            id          = id,
                            uri         = rowUri,
                            msData      = c.getString(colData)    ?: "",
                            msTitle     = c.getString(colTitle)   ?: "",
                            msArtist    = c.getString(colArtist)  ?: "",
                            msAlbum     = c.getString(colAlbum)   ?: "",
                            msAlbumId   = c.getLong(colAlbumId),
                            msTrack     = c.getInt(colTrack),
                            msDuration  = c.getLong(colDur),
                            msDateAdded = c.getLong(colAdded),
                            isInternal  = isInternal,
                        ))
                    }
                }
            } catch (_: Exception) { /* volume unavailable */ }
            // Stagger queries across volumes to avoid overwhelming the system
            try { delay(200) } catch (_: InterruptedException) { /* ignore */ }
        }
        return rows
    }

    // ── Step 2: enrich with MediaMetadataRetriever metadata ─────────────────

    private fun enrichWithRetriever(row: RawRow): Track {
        var title       = row.msTitle.ifBlank { "Unknown" }
        var sortTitle   = title
        var artist      = row.msArtist.ifBlank { "Unknown Artist" }
        var sortArtist  = artist
        var albumArtist = artist
        var sortAlbumArtist = artist
        var album       = row.msAlbum.ifBlank { "Unknown Album" }
        var sortAlbum   = album
        val (msDiscNum, msTrackNum) = decodeMediaStoreTrack(row.msTrack)
        var trackNum    = msTrackNum
        var discNum     = msDiscNum
        var year        = 0
        var genre       = ""
        var duration    = row.msDuration
        val mediaStoreAlbumArtUri = Track.albumArtUri(row.msAlbumId)

        // Detailed metadata and embedded artwork are read lazily with Android's
        // MediaMetadataRetriever, rather than during the bulk MediaStore scan.

        return Track(
            id              = row.id,
            uri             = row.uri,
            filePath        = row.msData,
            title           = title,
            sortTitle       = sortTitle,
            artist          = artist,
            sortArtist      = sortArtist,
            albumArtist     = albumArtist,
            sortAlbumArtist = sortAlbumArtist,
            album           = album,
            sortAlbum       = sortAlbum,
            albumId         = row.msAlbumId,
            trackNumber     = trackNum,
            discNumber      = discNum,
            year            = year,
            genre           = genre,
            duration        = duration,
            dateAdded       = row.msDateAdded,
            albumArtUri     = mediaStoreAlbumArtUri,
            mediaStoreAlbumArtUri = mediaStoreAlbumArtUri,
        )
    }

    internal fun decodeMediaStoreTrack(rawTrack: Int): Pair<Int, Int> {
        if (rawTrack <= 0) return 0 to 0
        if (rawTrack >= 1000) {
            // Many libraries encode this as disc * 1000 + track.
            val candidateDisc = rawTrack / 1000
            val candidateTrack = rawTrack % 1000
            if (candidateDisc in 1..99 && candidateTrack in 1..99) {
                return candidateDisc to candidateTrack
            }
        }
        return 0 to rawTrack
    }

    /**
     * Lazily extract and cache embedded album art for a single track.
     * Call this on a background thread when the track is first played or its
     * detail view is opened — NOT during the bulk library scan.
     */
    fun loadEmbeddedArtwork(context: Context, track: com.example.dpadplayer.playback.Track): Uri? {
        val cached = File(context.cacheDir, "album_art/track_" + track.id + ".jpg")
        if (cached.exists()) return Uri.fromFile(cached)
        val retriever = openMetadataRetriever(context, track) ?: return null
        val bytes = try {
            retriever.embeddedPicture
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreScanner", "Could not read embedded artwork for track " + track.id, e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        } ?: return null
        return persistEmbeddedArtwork(context, track.id, bytes, track.albumId)
    }

    /**
     * Open metadata through Android's media stack. Both the MediaStore URI and
     * direct path are attempted because older devices and removable volumes
     * can expose files differently. This avoids java.nio.file and third-party
     * file-permission checks that are not available on API 23.
     */
    private fun openMetadataRetriever(
        context: Context,
        track: com.example.dpadplayer.playback.Track
    ): MediaMetadataRetriever? {
        val uriRetriever = MediaMetadataRetriever()
        try {
            uriRetriever.setDataSource(context, track.uri)
            return uriRetriever
        } catch (_: Exception) {
            try { uriRetriever.release() } catch (_: Exception) { }
        }
        if (track.filePath.isBlank()) return null
        val pathRetriever = MediaMetadataRetriever()
        return try {
            pathRetriever.setDataSource(track.filePath)
            pathRetriever
        } catch (e: Exception) {
            try { pathRetriever.release() } catch (_: Exception) { }
            android.util.Log.w("MediaStoreScanner", "No metadata source available for track " + track.id, e)
            null
        }
    }

    /**
     * Enrich a track using Android's built-in MediaMetadataRetriever. Individual
     * fields fall back to MediaStore values and an unreadable file cannot abort
     * the library scan.
     */
    fun enrichTrack(
        context: Context,
        track: com.example.dpadplayer.playback.Track
    ): com.example.dpadplayer.playback.Track {
        val retriever = openMetadataRetriever(context, track) ?: return track
        var title = track.title
        var sortTitle = track.sortTitle
        var artist = track.artist
        var sortArtist = track.sortArtist
        var albumArtist = track.albumArtist
        var sortAlbumArtist = track.sortAlbumArtist
        var album = track.album
        var sortAlbum = track.sortAlbum
        var trackNum = track.trackNumber
        var discNum = track.discNumber
        var year = track.year
        var genre = track.genre
        var duration = track.duration
        var artworkBytes: ByteArray? = null

        try {
            fun value(key: Int): String? = try {
                retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }

            value(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                title = it
                if (track.sortTitle == track.title) sortTitle = it
            }
            val tagArtist = value(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (tagArtist != null) {
                artist = tagArtist
                if (track.sortArtist == track.artist) sortArtist = tagArtist
                if (track.albumArtist == track.artist) {
                    albumArtist = tagArtist
                    if (track.sortAlbumArtist == track.albumArtist) sortAlbumArtist = tagArtist
                }
            }
            value(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let {
                albumArtist = it
                if (track.sortAlbumArtist == track.albumArtist) sortAlbumArtist = it
            }
            value(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                album = it
                if (track.sortAlbum == track.album) sortAlbum = it
            }
            value(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { genre = it }
            value(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let {
                year = it.take(4).toIntOrNull() ?: year
            } ?: value(MediaMetadataRetriever.METADATA_KEY_DATE)?.let {
                year = it.take(4).toIntOrNull() ?: year
            }
            value(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let {
                trackNum = it.substringBefore('/').trim().toIntOrNull() ?: trackNum
            }
            value(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let {
                discNum = it.substringBefore('/').trim().toIntOrNull() ?: discNum
            }
            value(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                duration = it.toLongOrNull() ?: duration
            }
            artworkBytes = try {
                retriever.embeddedPicture
            } catch (e: Exception) {
                android.util.Log.w("MediaStoreScanner", "Artwork extraction failed for track " + track.id, e)
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreScanner", "Metadata enrichment failed for track " + track.id, e)
            return track
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }

        val albumArtUri = try {
            artworkBytes?.let { persistEmbeddedArtwork(context, track.id, it, track.albumId) }
                ?: track.albumArtUri
        } catch (e: Exception) {
            android.util.Log.w("MediaStoreScanner", "Could not cache artwork for track " + track.id, e)
            track.albumArtUri
        }
        val enriched = track.copy(
            title = title,
            sortTitle = sortTitle,
            artist = artist,
            sortArtist = sortArtist,
            albumArtist = albumArtist,
            sortAlbumArtist = sortAlbumArtist,
            album = album,
            sortAlbum = sortAlbum,
            trackNumber = trackNum,
            discNumber = discNum,
            year = year,
            genre = genre,
            duration = duration,
            albumArtUri = albumArtUri
        )
        try { ArtRepository.publishMetadata(MetadataEvent(enriched.id, enriched)) } catch (_: Exception) { }

        try {
            val db = AppDatabase.getInstance(context)
            val cache = com.example.dpadplayer.db.TrackCacheEntity(
                sourceUri = enriched.uri.toString(),
                trackId = enriched.id,
                title = enriched.title,
                sortTitle = enriched.sortTitle,
                artist = enriched.artist,
                sortArtist = enriched.sortArtist,
                albumArtist = enriched.albumArtist,
                sortAlbumArtist = enriched.sortAlbumArtist,
                album = enriched.album,
                sortAlbum = enriched.sortAlbum,
                albumId = enriched.albumId,
                trackNumber = enriched.trackNumber,
                discNumber = enriched.discNumber,
                year = enriched.year,
                genre = enriched.genre,
                duration = enriched.duration,
                dateAdded = enriched.dateAdded,
                albumArtPath = enriched.albumArtUri.path ?: ""
            )
            val writeScope = scope ?: defaultScope
            writeScope.launch(Dispatchers.IO) {
                try { db.trackCacheDao().upsert(cache) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return enriched
    }

    private fun persistEmbeddedArtwork(context: Context, trackId: Long, bytes: ByteArray, albumId: Long = 0L): Uri? {
        return try {
            val dir = File(context.cacheDir, "album_art")
            if (!dir.exists()) dir.mkdirs()
            val trackFile = File(dir, "track_$trackId.jpg")
            trackFile.writeBytes(bytes)
            // Also write album_<albumId>.jpg for quick album-level lookup (if albumId provided)
            var albumFileUri: Uri? = null
            if (albumId > 0) {
                try {
                    val albumFile = File(dir, "album_$albumId.jpg")
                    // overwrite with the latest track artwork (small cost but keeps album in sync)
                    albumFile.writeBytes(bytes)
                    albumFileUri = Uri.fromFile(albumFile)
                } catch (_: Exception) { /* ignore album write failures */ }
            }
            val trackUri = Uri.fromFile(trackFile)
            // Publish event with albumId if available
            ArtRepository.publishArtwork(ArtworkEvent(albumId, trackId, albumFileUri ?: trackUri))
            // Persist album cache entry in Room so data survives restarts
            try {
                val db = AppDatabase.getInstance(context)
                val albumName = /* best effort: try reading tag or fallback */ ""
                // We schedule a background write; don't block caller
                // write cache entry on a coroutine on IO
                try {
                    val writeScope = scope ?: defaultScope
                    writeScope.launch(Dispatchers.IO) {
                        try {
                            if (albumId > 0 && albumFileUri != null) {
                                val path = File(albumFileUri.path ?: "").absolutePath
                                db.albumCacheDao().upsert(
                                    com.example.dpadplayer.db.AlbumCacheEntity(
                                        albumId = albumId,
                                        name = albumName,
                                        artist = "",
                                        artPath = path,
                                    )
                                )
                            }
                        } catch (_: Exception) { /* ignore DB failures */ }
                    }
                } catch (_: Exception) { /* ignore */ }
            } catch (_: Exception) { /* ignore */ }
            trackUri
        } catch (_: Exception) {
            null
        }
    }

    // ── Step 3: sort ──────────────────────────────────────────────────────────

    private fun applySortOrder(tracks: List<Track>, sortOrder: String): List<Track> {
        return when (sortOrder) {
            "artist"     -> tracks.sortedWith(compareBy(
                { it.sortArtist.lowercase() }, { it.sortAlbum.lowercase() },
                { it.discNumber }, { it.trackNumber }, { it.sortTitle.lowercase() }))
            "album"      -> tracks.sortedWith(compareBy(
                { it.sortAlbum.lowercase() }, { it.discNumber },
                { it.trackNumber }, { it.sortTitle.lowercase() }))
            "date_added" -> tracks.sortedByDescending { it.dateAdded }
            "year"       -> tracks.sortedWith(compareByDescending<Track> { it.year }
                .thenBy { it.sortAlbum.lowercase() }
                .thenBy { it.discNumber }.thenBy { it.trackNumber })
            else         -> tracks.sortedBy { it.sortTitle.lowercase() }  // "title"
        }
    }
}
