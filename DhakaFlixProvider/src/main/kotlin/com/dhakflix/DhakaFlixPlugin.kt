package com.dhakflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DhakaFlix : MainAPI() {
    override var mainUrl = "http://172.16.50.14"
    override var name = "DhakaFlix"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    private val videoExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".webm")

    private fun isVideo(href: String) = videoExtensions.any { href.lowercase().endsWith(it) }

    private fun cleanTitle(raw: String) = raw
        .substringBeforeLast(".")
        .replace(".", " ")
        .replace("_", " ")
        .replace("-", " ")
        .trim()

    private fun fixUrl(base: String, href: String): String {
        return when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$mainUrl$href"
            else -> "$base/$href".replace("///", "/").replace("//", "/").replace("http:/", "http://")
        }
    }

    private suspend fun getLinks(url: String): List<Pair<String, String>> {
        return try {
            app.get(url).document.select("a[href]")
                .mapNotNull {
                    val href = it.attr("href")
                    val text = it.text().trim()
                    if (href.isBlank() || text == ".." || href.startsWith("?")) null
                    else Pair(text, fixUrl(url, href))
                }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun getMovies(folderUrl: String): List<SearchResponse> {
        return getLinks(folderUrl)
            .filter { isVideo(it.second) }
            .map { (name, url) ->
                val year = Regex("\\((\\d{4})\\)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                newMovieSearchResponse(cleanTitle(name), url, TvType.Movie) {
                    this.year = year
                }
            }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val categories = getLinks(mainUrl).filter {
            it.second != mainUrl && it.second.startsWith(mainUrl) && !isVideo(it.second)
        }

        for ((catName, catUrl) in categories) {
            val items = mutableListOf<SearchResponse>()
            val subLinks = getLinks(catUrl).filter {
                it.second != catUrl && !isVideo(it.second) && it.second.startsWith(mainUrl)
            }

            if (subLinks.isEmpty()) {
                items.addAll(getMovies(catUrl).take(20))
            } else {
                for ((_, subUrl) in subLinks.reversed().take(3)) {
                    items.addAll(getMovies(subUrl).take(10))
                    if (items.size >= 20) break
                }
            }

            if (items.isNotEmpty()) lists.add(HomePageList(catName, items))
        }

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val q = query.lowercase()
        val categories = getLinks(mainUrl).filter {
            it.second != mainUrl && it.second.startsWith(mainUrl) && !isVideo(it.second)
        }

        for ((_, catUrl) in categories) {
            val subLinks = getLinks(catUrl).filter {
                it.second != catUrl && !isVideo(it.second) && it.second.startsWith(mainUrl)
            }
            val foldersToSearch = if (subLinks.isEmpty()) listOf(catUrl) else subLinks.map { it.second }

            for (folderUrl in foldersToSearch) {
                getMovies(folderUrl).filter { it.name.lowercase().contains(q) }.forEach {
                    results.add(it)
                }
            }
            if (results.size >= 100) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val title = cleanTitle(url.substringAfterLast("/"))
        val year = Regex("\\((\\d{4})\\)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val quality = when {
            data.contains("1080", true) -> Qualities.P1080.value
            data.contains("720", true) -> Qualities.P720.value
            data.contains("480", true) -> Qualities.P480.value
            data.contains("4k", true) || data.contains("2160", true) -> Qualities.UHD.value
            else -> Qualities.Unknown.value
        }
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = data,
                referer = mainUrl,
                quality = quality,
                isM3u8 = false
            )
        )
        return true
    }
}
