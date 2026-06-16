package eu.kanade.tachiyomi.extension.ar.mangasid

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import kotlin.time.Duration.Companion.seconds

class Mangasid : HttpSource() {
    override val name = "Mangasid"
    override val baseUrl = "https://mangasid.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = network.client
        .newBuilder()
        .connectTimeout(15.seconds)
        .readTimeout(30.seconds)
        .rateLimit(10, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private var filtersLoaded = false

    private val genreNames: MutableList<String> = mutableListOf()
    private val statusNames: MutableList<String> = mutableListOf()

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("src") -> attr("abs:src")
        else -> ""
    }

    private fun String?.toStatus(): Int = when {
        this == null -> SManga.UNKNOWN
        contains("مستمر", ignoreCase = true) -> SManga.ONGOING
        contains("مكتمل", ignoreCase = true) -> SManga.COMPLETED
        contains("متوقف", ignoreCase = true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // --- Popular ---

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga-list?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        return popularMangaParse(response.asJsoup())
    }

    private fun popularMangaParse(document: Document): MangasPage {
        val cards = document.select("div.manga-card")

        val mangas = cards.map { card ->
            SManga.create().apply {
                val link = card.selectFirst("h3 a")!!
                title = link.text()
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = card.selectFirst("img")?.imgAttr()
            }
        }

        val hasNextPage = document.selectFirst("nav a:last-child:not([aria-disabled=true])") != null

        fetchFiltersIfNeeded(document)

        return MangasPage(mangas, hasNextPage)
    }

    // --- Latest ---

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga-list?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response.asJsoup())
    }

    // --- Search ---

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        if (query.startsWith("http")) {
            val baseHost = baseUrl.toHttpUrl().host
            val seriesUrl = query.toHttpUrl()

            if (seriesUrl.host != baseHost) throw Exception("Unsupported URL")

            val manga = SManga.create().apply { url = seriesUrl.encodedPath }

            return fetchMangaDetails(manga)
                .map {
                    MangasPage(
                        listOf(
                            it.apply {
                                url = manga.url
                                initialized = true
                            },
                        ),
                        false,
                    )
                }
        }

        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("manga-list")

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        if (page > 1) {
            url.addQueryParameter("page", page.toString())
        }

        if (query.isBlank()) {
            var sortParam: String? = null
            var sortOrder: String? = null
            val selectedGenres = mutableListOf<String>()
            var selectedStatus: String? = null

            for (filter in filters) {
                when (filter) {
                    is SortFilter -> {
                        val (sort, order) = filter.toSortParam()
                        sortParam = sort
                        sortOrder = order
                    }
                    is GenreFilter -> {
                        val selected = filter.values[filter.state]
                        if (selected != GenreFilter.ALL) {
                            selectedGenres.add(selected)
                        }
                    }
                    is StatusFilter -> {
                        val selected = filter.values[filter.state]
                        if (selected != StatusFilter.ALL) {
                            selectedStatus = selected
                        }
                    }
                    else -> {}
                }
            }

            sortParam?.let { url.addQueryParameter("sort", it) }
            if (sortOrder != null) url.addQueryParameter("sortOrder", sortOrder)
            if (selectedGenres.isNotEmpty()) url.addQueryParameter("genres", selectedGenres.joinToString(","))
            selectedStatus?.let { url.addQueryParameter("status", it) }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response.asJsoup())
    }

    // --- Filters ---

    private fun fetchFiltersIfNeeded(document: Document) {
        if (filtersLoaded) return

        genreNames.clear()
        document.select("aside.filter-sidebar div.mb-4:has(h4:contains(التصنيفات)) button")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .forEach { genreNames.add(it) }

        statusNames.clear()
        document.select("aside.filter-sidebar div.mb-6:has(h4:contains(الحالة)) button")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .forEach { statusNames.add(it) }

        if (genreNames.isNotEmpty() || statusNames.isNotEmpty()) {
            filtersLoaded = true
        }
    }

    override fun getFilterList(): FilterList {
        if (!filtersLoaded) {
            return FilterList(
                Filter.Header(
                    "الرجاء فتح قائمة المانجا الشعبية أو الأحدث\n" +
                        "لتحميل خيارات التصفية",
                ),
            )
        }

        return FilterList(
            Filter.Header("ملاحظة: التصفية لا تعمل مع البحث النصي"),
            Filter.Separator(),
            SortFilter(),
            Filter.Separator(),
            StatusFilter(statusNames),
            Filter.Separator(),
            GenreFilter(genreNames),
        )
    }

    private class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("الأحدث", "الأكثر شعبية", "أ-ي"),
        ) {
        fun toSortParam(): Pair<String, String?> = when (state) {
            0 -> "latest" to null
            1 -> "views" to null
            2 -> "title" to "ASC"
            else -> "latest" to null
        }
    }

    private class StatusFilter(vals: List<String>) :
        Filter.Select<String>(
            "الحالة",
            vals.toTypedArray().ifEmpty { arrayOf(ALL) },
        ) {
        companion object {
            const val ALL = "الكل"
        }
    }

    private class GenreFilter(vals: List<String>) :
        Filter.Select<String>(
            "التصنيفات",
            (listOf(ALL) + vals).toTypedArray(),
        ) {
        companion object {
            const val ALL = "الكل"
        }
    }

    // --- Details ---

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""

            description = document.selectFirst(
                "p.text-gray-300, div.description, .description p, div.summary, .summary p",
            )?.text()

            thumbnail_url = document.selectFirst("img[class*='rounded'], main img, div.flex img")?.imgAttr()
                ?: document.selectFirst("img")?.imgAttr()

            val statusText = document.selectFirst(
                "span:contains(مستمر), span:contains(مكتمل), span:contains(متوقف)",
            )?.text()
            status = statusText.toStatus()

            genre = document.select(
                "a[class*=bg-], span[class*=bg-]",
            ).mapNotNull { element ->
                element.text().trim().takeIf { it.isNotBlank() && it.length < 20 }
            }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }

    // --- Chapters ---

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapterLinks = document.select("a[href*=\"/reader/\"]")

        val seen = mutableSetOf<String>()
        return chapterLinks.mapNotNull { link ->
            val href = link.attr("href")
            if (href.isBlank() || !seen.add(href)) return@mapNotNull null

            val chapterNum = href.substringAfterLast("/")

            SChapter.create().apply {
                name = link.text().ifBlank { "الفصل $chapterNum" }
                setUrlWithoutDomain(href)
                chapter_number = chapterNum.toFloatOrNull() ?: 0f
            }
        }
    }

    // --- Pages ---

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select("img[src]")

        return images.mapIndexedNotNull { i, img ->
            val url = img.imgAttr()
            if (url.isBlank() || url.contains("logo", ignoreCase = true) || url.contains("icon", ignoreCase = true)) {
                null
            } else {
                Page(i, imageUrl = url)
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
