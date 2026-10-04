package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.provider.BaseColumns
import java.util.Locale

internal data class RecommendationChannel(
    val id: Long,
    val title: String,
    val packageName: String
)

internal data class MediaCard(
    val id: Long,
    val title: String,
    val description: String,
    val posterUri: String?,
    val intentUri: String?,
    val channelId: Long?
)

internal data class RecommendationsSnapshot(
    val channels: List<RecommendationChannel>,
    val programs: Map<Long, List<MediaCard>>,
    val watchNext: List<MediaCard>
)

private const val MAX_CHANNELS = 12
private const val MAX_CARDS_PER_CHANNEL = 24
private const val MAX_PROVIDER_ROWS = 512

/** Run off the main thread. Access to other apps' rows depends on the TV firmware. */
internal fun readRecommendations(context: Context): RecommendationsSnapshot {
    val channels = readPreviewChannels(context)
    val programs = channels.associate { channel ->
        channel.id to readMediaCards(
            context = context,
            uri = TvContract.buildPreviewProgramsUriForChannel(channel.id),
            channelId = channel.id
        )
    }
    return RecommendationsSnapshot(
        channels = channels,
        programs = programs,
        watchNext = readMediaCards(context, TvContract.WatchNextPrograms.CONTENT_URI, null)
    )
}

private fun readPreviewChannels(context: Context): List<RecommendationChannel> =
    queryTvRows(context, TvContract.Channels.CONTENT_URI, arrayOf(
        BaseColumns._ID,
        TvContract.Channels.COLUMN_DISPLAY_NAME,
        TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
        TvContract.Channels.COLUMN_TYPE,
        TvContract.Channels.COLUMN_BROWSABLE
    )) { cursor ->
        val channels = mutableListOf<RecommendationChannel>()
        val seen = mutableSetOf<Long>()
        var scanned = 0
        while (channels.size < MAX_CHANNELS && scanned < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
            scanned++
            if (cursor.text(TvContract.Channels.COLUMN_TYPE) != TvContract.Channels.TYPE_PREVIEW ||
                cursor.number(TvContract.Channels.COLUMN_BROWSABLE) != 1L) continue
            val id = cursor.number(BaseColumns._ID) ?: continue
            val title = cursor.text(TvContract.Channels.COLUMN_DISPLAY_NAME) ?: continue
            val packageName = cursor.text(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME) ?: continue
            if (id < 0 || !seen.add(id)) continue
            channels += RecommendationChannel(id, title, packageName)
        }
        channels
    }

private fun readMediaCards(context: Context, uri: Uri, channelId: Long?): List<MediaCard> =
    queryTvRows(context, uri, arrayOf(
        BaseColumns._ID,
        TvContract.PreviewPrograms.COLUMN_TITLE,
        TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
        TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
        TvContract.PreviewPrograms.COLUMN_INTENT_URI,
        TvContract.PreviewPrograms.COLUMN_BROWSABLE
    )) { cursor ->
        val cards = mutableListOf<MediaCard>()
        val seen = mutableSetOf<Long>()
        var scanned = 0
        while (cards.size < MAX_CARDS_PER_CHANNEL && scanned < MAX_PROVIDER_ROWS && cursor.moveToNext()) {
            scanned++
            if (cursor.number(TvContract.PreviewPrograms.COLUMN_BROWSABLE) != 1L) continue
            val id = cursor.number(BaseColumns._ID) ?: continue
            val title = cursor.text(TvContract.PreviewPrograms.COLUMN_TITLE) ?: continue
            val intentUri = cursor.text(TvContract.PreviewPrograms.COLUMN_INTENT_URI) ?: continue
            if (id < 0 || !seen.add(id)) continue
            cards += MediaCard(
                id = id,
                title = title,
                description = cursor.text(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION).orEmpty(),
                posterUri = cursor.text(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                intentUri = intentUri,
                channelId = channelId
            )
        }
        cards
    }

/** Preview and Watch Next providers do not support SQL selection arguments. */
private fun <T> queryTvRows(
    context: Context,
    uri: Uri,
    projection: Array<String>,
    read: (Cursor) -> List<T>
): List<T> = try {
    context.contentResolver.query(uri, projection, null, null, null)?.use(read).orEmpty()
} catch (_: SecurityException) {
    emptyList()
} catch (_: RuntimeException) {
    // A provider may be absent or implement only part of the Android TV contract.
    emptyList()
}

private fun Cursor.text(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Cursor.number(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

internal fun openMediaCard(context: Context, card: MediaCard): Boolean {
    val raw = card.intentUri?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 } ?: return false
    val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
    if (!isMediaPlaybackAction(intent.action) || !isSafeMediaScheme(intent.data?.scheme)) return false
    if (intent.data == null && intent.component == null && intent.`package` == null) return false

    // Never honor URI permission grants or a nested redirect from a content provider.
    intent.selector = null
    intent.clipData = null
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

internal fun isMediaPlaybackAction(action: String?): Boolean =
    action == Intent.ACTION_VIEW || action == Intent.ACTION_MAIN

internal fun isSafeMediaScheme(scheme: String?): Boolean =
    scheme?.lowercase(Locale.ROOT) !in setOf("javascript", "data", "file", "package")
