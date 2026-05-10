package com.dhakflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DhakaFlix : MainAPI() {
    override var mainUrl = "http://172.16.50.14"
    override var name = "DhakaFlix"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"

    private val videoExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".webm")

    private fun isVideo(href: String) = videoExtensions.any { href.lowercase().endsWith(it) }

    private fun cleanTitle(raw: String) = raw
        .substringBeforeLast(".")
        .replace(".", " ").replace("_", " ").replace("-", " ").trim()

    private fun fixUrl(base: String, href: String): String {
        val cleanBase = base.trimEnd('/')
        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$cleanBase/$href"
        }
    }

    private suspend fun getLinks(url: String): List<Pair<String, String>> {
        return try {
            app.get(url).document.select("a[href]").mapNotNull {
                val href = it.attr("href")
                val text = it.text().trim()
                if (href.isBlank() || text == ".." || href.startsWith("?") || href.startsWith("http") && !href.startsWith(mainUrl)) null
                else Pair(text, fixUrl(url, href))
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getMovies(folderUrl: String): List<SearchResponse> {
        return getLinks(folderUrl).filter { isVideo(it.second) }.map { (n, url) ->
            MovieSearchResponse(
                name = cleanTitle(n),
                url = url,
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = null,
                year = Regex("\\((\\d{4})\\)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val cats = getLinks(mainUrl).filter {
            it.second.startsWith(mainUrl) && it.second != "$mainUrl/" && !isVideo(it.second)
        }
        for ((catName, catUrl) in cats) {
            val items = mutableListOf<SearchResponse>()
            val subs = getLinks(catUrl).filter {
                it.second.startsWith(mainUrl) && it.second != catUrl && !isVideo(it.second)
            }
            if (subs.isEmpty()) {
                items.addAll(getMovies(catUrl).take(20))
            } else {
                for ((_, subUrl) in subs.reversed().take(3)) {
                    items.addAll(getMovies(subUrl).take(10))
                    if (items.size >= 20) break
                }
            }
            if (items.isNotEmpty()) lists.add(HomePageList(catName, items))
        }
        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val q = query.lowercase()
        val cats = getLinks(mainUrl).filter {
            it.second.startsWith(mainUrl) && it.second != "$mainUrl/" && !isVideo(it.second)
        }
        for ((_, catUrl) in cats) {
            val subs = getLinks(catUrl).filter {
                it.second.startsWith(mainUrl) && it.second != catUrl && !isVideo(it.second)
            }
            val folders = if (subs.isEmpty()) listOf(catUrl) else subs.map { it.second }
            for (f in folders) {
                results.addAll(getMovies(f).filter { it.name.lowercase().contains(q) })
            }
            if (results.size >= 100) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val title = cleanTitle(url.substringAfterLast("/"))
        val year = Regex("\\((\\d{4})\\)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            posterUrl = null,
            year = year
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(ExtractorLink(
            source = name,
            name = name,
            url = data,
            referer = mainUrl,
            quality = when {
                data.contains("1080", true) -> Qualities.P1080.value
                data.contains("720", true) -> Qualities.P720.value
                data.contains("480", true) -> Qualities.P480.value
                else -> Qualities.Unknown.value
            },
            isM3u8 = false
        ))
        return true
    }
}
