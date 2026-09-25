package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridProviderApis
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridResolveResult
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem

internal fun StreamItem.toDownloadSourceResolve(
    season: Int?,
    episode: Int?,
): DownloadSourceResolve? {
    clientResolve?.let { return it.toDownloadSourceResolve() }

    val identity = infoHash?.takeIf { it.isNotBlank() }
        ?: torrentMagnetUri?.takeIf { it.isNotBlank() }
        ?: return null
    val providerId = DebridSettingsRepository.snapshot().activeResolverProviderId ?: return null

    return DownloadSourceResolve(
        type = "torrent",
        infoHash = infoHash,
        fileIdx = fileIdx,
        magnetUri = torrentMagnetUri,
        sources = sources,
        torrentName = title ?: name,
        filename = behaviorHints.filename,
        title = title,
        season = season,
        episode = episode,
        service = providerId,
        isCached = true,
    ).takeIf { identity.isNotBlank() }
}

internal suspend fun refreshDownloadSource(item: DownloadItem): DirectDebridResolveResult.Success? {
    val source = item.sourceResolve ?: return null
    val providerId = DebridProviders.byId(source.service)?.id ?: return null
    val settings = DebridSettingsRepository.snapshot()
    val apiKey = settings.apiKeyFor(providerId).trim().takeIf { it.isNotBlank() } ?: return null
    val api = DebridProviderApis.apiFor(providerId) ?: return null

    val stream = StreamItem(
        name = item.streamTitle,
        title = source.title ?: item.title,
        description = item.streamSubtitle,
        url = null,
        infoHash = source.infoHash,
        fileIdx = source.fileIdx,
        externalUrl = source.magnetUri,
        sources = source.sources,
        addonName = item.providerName,
        addonId = item.providerAddonId ?: "addon:download-refresh",
        behaviorHints = StreamBehaviorHints(
            filename = source.filename ?: item.fileName,
            videoSize = item.totalBytes,
        ),
        clientResolve = source.toStreamClientResolve(),
    )

    return api.resolveClientStream(
        stream = stream,
        apiKey = apiKey,
        season = item.seasonNumber ?: source.season,
        episode = item.episodeNumber ?: source.episode,
    ) as? DirectDebridResolveResult.Success
}

private fun StreamClientResolve.toDownloadSourceResolve(): DownloadSourceResolve =
    DownloadSourceResolve(
        type = type,
        infoHash = infoHash,
        fileIdx = fileIdx,
        magnetUri = magnetUri,
        sources = sources,
        torrentName = torrentName,
        filename = filename,
        mediaType = mediaType,
        mediaId = mediaId,
        mediaOnlyId = mediaOnlyId,
        title = title,
        season = season,
        episode = episode,
        service = service,
        serviceIndex = serviceIndex,
        serviceExtension = serviceExtension,
        isCached = isCached,
    )

private fun DownloadSourceResolve.toStreamClientResolve(): StreamClientResolve =
    StreamClientResolve(
        type = type,
        infoHash = infoHash,
        fileIdx = fileIdx,
        magnetUri = magnetUri,
        sources = sources,
        torrentName = torrentName,
        filename = filename,
        mediaType = mediaType,
        mediaId = mediaId,
        mediaOnlyId = mediaOnlyId,
        title = title,
        season = season,
        episode = episode,
        service = service,
        serviceIndex = serviceIndex,
        serviceExtension = serviceExtension,
        isCached = isCached,
    )
