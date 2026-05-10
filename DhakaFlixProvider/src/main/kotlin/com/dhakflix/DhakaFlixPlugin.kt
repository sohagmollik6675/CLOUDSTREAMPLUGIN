package com.dhakflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class DhakaFlix : MainAPI() {
    override var mainUrl = "http://172.16.50.14"
    override var name = "DhakaFlix"
    override val hasMainPage = true
    override val hasSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    // Video file extensions to look for
    private val videoExtensions = listOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm")

    private fun isVideoFile(href: String): Boolean {
        val lower = href.lowercase()
        return videoExtensions.any { lower.endsWith(it) }
    }

    private fun isYearFolder(text: String): Boolean {
        return text.trim().matches(Regex("\\(\\d{4}.*\\)/?")) || text.trim().matches(Regex("\\d{4}/?"))
    }

    // Get all category links from the main page
    private suspend fun getCategories(): List<Pair<String, String>> {
        val doc = app.get(mainUrl).document
        return doc.select("a[href]")
            .filter { el ->
                val href = el.attr("href")
                val text = el.text().trim()
                href.isNotBlank() &&
                text.isNotBlank() &&
                !href.startsWith("http") &&
                !href.startsWith("?") &&
                !href.startsWith("#") &&
                text.length > 3
            }
            .map { el ->
                val href = el.attr("href").let {
                    if (it.startsWith("/")) "$mainUrl$it" else "$mainUrl/$it"
                }
                Pair(el.text().trim(), href)
            }
    }

    // Get year subfolders from a category page
    private suspend fun getYearFolders(catUrl: String): List<Pair<String, String>> {
        return try {
            val doc = app.get(catUrl).document
            doc.select("a[href]")
                .filter { el ->
                    val href = el.attr("href")
                    val text = el.text().trim()
                    href.endsWith("/") &&
                    !href.startsWith("?") &&
                    !href.startsWith("http") &&
                    text != ".." &&
                    text.isNotBlank()
                }
                .map { el ->
                    val href = el.attr("href").let {
                        if (it.startsWith("/")) "$mainUrl$it" else "$catUrl/$it".replace("//", "/").replace("http:/", "http://")
                    }
                    Pair(el.text().trim(), href)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Get video files from a folder (year folder or category folder)
    private suspend fun getMoviesFromFolder(folderUrl: String): List<SearchResponse> {
        return try {
            val doc = app.get(folderUrl).document
            doc.select("a[href]")
                .filter { el -> isVideoFile(el.attr("href")) }
                .map { el ->
                    val href = el.attr("href")
                    val fileUrl = if (href.startsWith("http")) href
                        else "$folderUrl/${href.trimStart('/')}".replace("///", "/").replace("//", "/").replace("http:/", "http://")

                    val title = el.text().trim()
                        .substringBeforeLast(".")     // remove extension
                        .replace(".", " ")
                        .replace("_", " ")
                        .trim()

                    MovieSearchResponse(
                        name = title,
                        url = fileUrl,
                        apiName = this.name,
                        type = TvType.Movie
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── MAIN PAGE ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = getCategories()
        val homePageLists = mutableListOf<HomePageList>()

        for ((catName, catUrl) in categories) {
            try {
                val items = mutableListOf<SearchResponse>()

                // Check if the category directly has videos
                val directMovies = getMoviesFromFolder(catUrl)
                if (directMovies.isNotEmpty()) {
                    items.addAll(directMovies.take(20))
                } else {
                    // It has subfolders (year folders) — get movies from latest years first
                    val yearFolders = getYearFolders(catUrl).reversed().take(3) // last 3 years
                    for ((_, yearUrl) in yearFolders) {
                        val movies = getMoviesFromFolder(yearUrl)
                        items.addAll(movies.take(10))
                        if (items.size >= 20) break
                    }
                }

                if (items.isNotEmpty()) {
                    homePageLists.add(HomePageList(catName, items, isHorizontalImages = false))
                }
            } catch (e: Exception) {
                // Skip this category if it fails
            }
        }

        return HomePageResponse(homePageLists)
    }

    // ─── SEARCH ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase()
        val categories = getCategories()

        for ((_, catUrl) in categories) {
            try {
                // First check direct videos
                val directMovies = getMoviesFromFolder(catUrl)
                results.addAll(directMovies.filter { it.name.lowercase().contains(queryLower) })

                // Then check year subfolders
                val yearFolders = getYearFolders(catUrl)
                for ((_, yearUrl) in yearFolders) {
                    val movies = getMoviesFromFolder(yearUrl)
                    results.addAll(movies.filter { it.name.lowercase().contains(queryLower) })
                }
            } catch (e: Exception) {
                // Skip failed categories
            }

            if (results.size >= 100) break // Limit results
        }

        return results
    }

    // ─── LOAD ──────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val fileName = url.substringAfterLast("/")
        val title = fileName
            .substringBeforeLast(".")
            .replace(".", " ")
            .replace("_", " ")
            .replace("-", " ")
            .trim()

        // Try to extract year from URL path
        val yearMatch = Regex("\\((\\d{4})\\)").find(url)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        return MovieLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            year = year,
            plot = "Direct stream from DhakaFlix local server"
        )
    }

    // ─── LOAD LINKS ────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = data,
                referer = mainUrl,
                quality = getQualityFromName(data),
                isM3u8 = false
            )
        )
        return true
    }

    // Detect quality from filename (e.g. 1080p, 720p, 4K)
    private fun getQualityFromName(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160p") -> Qualities.UHD.value
            lower.contains("1080p") || lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720p") || lower.contains("720") -> Qualities.P720.value
            lower.contains("480p") || lower.contains("480") -> Qualities.P480.value
            lower.contains("360p") || lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
