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
import kotlin.time.Duration.Companion.seconds

class Mangasid : HttpSource() {
    override val name = "Mangasid"
    override val baseUrl = "https://mangasid.com"
    override val lang = "ar"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga-list".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.manga-card").map { element ->
            SManga.create().apply {
                val titleEl = element.selectFirst("h3 a") ?: element.selectFirst("h3")
                title = titleEl?.text()?.trim() ?: ""
                url = element.selectFirst("a")?.attr("href") ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        val nextPageElement = document.selectFirst("nav a:contains(الصفحة التالية), nav a:has(i.fa-chevron-left)")
        val hasNextPage = nextPageElement != null &&
            nextPageElement.attr("href").isNotEmpty() &&
            nextPageElement.attr("aria-disabled") != "true"

        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga-list".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/manga-list".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addQueryParameter("search", query)
        }

        url.addQueryParameter("page", page.toString())

        // Apply filters
        val genresList = mutableListOf<String>()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.toUriValue())
                    if (filter.toUriValue() == "title") {
                        url.addQueryParameter("sortOrder", "ASC")
                    }
                }
                is GenreList -> {
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            genresList.add(genre.name)
                        }
                    }
                }
                else -> {}
            }
        }

        if (genresList.isNotEmpty()) {
            url.addQueryParameter("genres", genresList.joinToString(","))
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("img[src*=cover], img[src*=covers], img[alt=$title]")?.attr("abs:src")
            ?: document.selectFirst("img")?.attr("abs:src")

        description = document.select("p.text-gray-300, p.text-muted-foreground, .story, #story, .description")
            .joinToString("\n") { it.text().trim() }
            .takeIf { it.isNotEmpty() }

        genre = document.select("a[href*=genres=], span.bg-secondary, span.bg-primary/20, .genre-badge")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

        val statusText = document.text()
        status = when {
            statusText.contains("مستمر") -> SManga.ONGOING
            statusText.contains("مكتمل") -> SManga.COMPLETED
            statusText.contains("متوقف") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        author = document.selectFirst("*:contains(المؤلف) + *, *:contains(الكاتب) + *, .author-class")?.text()?.trim()
        artist = document.selectFirst("*:contains(الرسام) + *, .artist-class")?.text()?.trim()
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("a[href*=/reader/]").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = element.text().trim()
                date_upload = parseChapterDate(element.parent()?.text() ?: "")
            }
        }
        return chapters.distinctBy { it.url }
    }

    private fun parseChapterDate(dateStr: String): Long {
        val cleanDate = dateStr.lowercase()
        return when {
            cleanDate.contains("منذ قليل") || cleanDate.contains("الآن") -> System.currentTimeMillis()
            cleanDate.contains("اليوم") -> System.currentTimeMillis()
            cleanDate.contains("أمس") -> System.currentTimeMillis() - 86400000L
            else -> 0L
        }
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Select standard reader layout image elements
        val imageElements = document.select("div.reader-images img, div.reader-container img, div.reader img, .reader-image-container img, img.reader-img")

        if (imageElements.isNotEmpty()) {
            return imageElements.mapIndexed { index, img ->
                val imageUrl = img.attr("abs:data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("abs:src")
                Page(index, "", imageUrl)
            }
        }

        // Fallback for scripts declaring static image lists (if hydrated asynchronously)
        val scriptContent = document.select("script").html()
        val urlRegex = """https?://[^"\s]+\.(?:jpg|jpeg|png|webp|gif)""".toRegex()
        val foundUrls = urlRegex.findAll(scriptContent)
            .map { it.value }
            .filterNot { it.contains("logo") || it.contains("avatar") || it.contains("cover") }
            .distinct()
            .toList()

        if (foundUrls.isNotEmpty()) {
            return foundUrls.mapIndexed { index, url ->
                Page(index, "", url)
            }
        }

        throw Exception("لم يتم العثور على صفحات لهذا الفصل.")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters
    class Genre(name: String) : Filter.CheckBox(name)

    class GenreList(genres: List<Genre>) : Filter.Group<Genre>("التصنيفات", genres)

    class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("الأحدث", "الأكثر شعبية", "أ-ي"),
        ) {
        fun toUriValue() = when (state) {
            0 -> "latest"
            1 -> "views"
            2 -> "title"
            else -> "latest"
        }
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        GenreList(getGenres()),
    )

    private fun getGenres() = listOf(
        Genre("دراما"),
        Genre("أكشن"),
        Genre("خيال"),
        Genre("فانتازيا"),
        Genre("مغامرة"),
        Genre("رومانسي"),
        Genre("كوميدي"),
        Genre("إثارة"),
        Genre("شوجو"),
        Genre("رومانسى"),
        Genre("شونين"),
        Genre("مانهوا"),
        Genre("فنون قتال"),
        Genre("غموض"),
        Genre("سحر"),
        Genre("قوة خارقة"),
        Genre("تاريخي"),
        Genre("حياة مدرسية"),
        Genre("بطل غير اعتيادي"),
        Genre("ويب تون"),
        Genre("وحوش"),
        Genre("نظام"),
        Genre("خارق للطبيعة"),
        Genre("الحياة اليومية"),
        Genre("إيسيكاي"),
        Genre("تناسخ"),
        Genre("السفر عبر الزمن"),
        Genre("مانجا"),
        Genre("دموي"),
        Genre("جوسيه"),
        Genre("سينين"),
        Genre("شياطين"),
        Genre("اعادة احياء"),
        Genre("نفسي"),
        Genre("تشويق"),
        Genre("صقل"),
        Genre("رعب"),
        Genre("إنتقام"),
        Genre("موريم"),
        Genre("حديث"),
        Genre("شريحة من الحياة"),
        Genre("حريم"),
        Genre("ثأر"),
        Genre("تجسيد"),
        Genre("عنف"),
        Genre("الخيال العلمي"),
        Genre("عائلى"),
        Genre("حرب"),
        Genre("مأساة"),
        Genre("تراجيدي"),
        Genre("ملائكة"),
        Genre("داخل لعبة"),
        Genre("رياضه"),
        Genre("قتال"),
        Genre("خارق"),
        Genre("زنزانات"),
        Genre("عالم مختلف"),
        Genre("لعبه"),
        Genre("النجاة"),
        Genre("جريمة"),
        Genre("كوميك"),
        Genre("العاب"),
        Genre("أكاديمية"),
        Genre("نهاية العالم"),
    )
}
