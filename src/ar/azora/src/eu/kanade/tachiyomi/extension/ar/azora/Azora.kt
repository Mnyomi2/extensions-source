package eu.kanade.tachiyomi.extension.ar.azora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Azora : ParsedHttpSource() {
    override val name = "Azora"

    override val baseUrl = "https://azoramoon.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?sortBy=popular&page=$page", headers)

    override fun popularMangaSelector() = "div.grid > div"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val linkElement = element.selectFirst("a[href*=/series/]")!!
        setUrlWithoutDomain(linkElement.attr("href"))
        title = linkElement.attr("title").trim()

        val imgElement = element.selectFirst("img")
        imgElement?.attr("abs:src")?.let { src ->
            thumbnail_url = if (src.contains("wsrv.nl/?url=")) {
                try {
                    URLDecoder.decode(src.substringAfter("url=").substringBefore("&"), "UTF-8")
                } catch (e: Exception) {
                    src
                }
            } else {
                src
            }
        }
    }

    override fun popularMangaNextPageSelector() = "button[aria-label=الصفحة التالية]:not([disabled])"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).mapNotNull { element ->
            val isNovel = element.selectFirst("span:contains(رواية)") != null
            if (isNovel) {
                null
            } else {
                popularMangaFromElement(element)
            }
        }
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("searchTerm", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sortBy", filter.toUriPart() ?: "")
                is TypeFilter -> url.addQueryParameter("seriesType", filter.toUriPart() ?: "")
                is StatusFilter -> url.addQueryParameter("seriesStatus", filter.toUriPart() ?: "")
                is GenreFilter -> filter.toUriPart()?.let { url.addQueryParameter("genres", it) }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: ""

        val rawSrc = document.selectFirst("div[itemprop=image] img, img[alt*=Cover], img[src*=storage]")?.attr("abs:src")
        thumbnail_url = if (rawSrc != null && rawSrc.contains("wsrv.nl/?url=")) {
            try {
                URLDecoder.decode(rawSrc.substringAfter("url=").substringBefore("&"), "UTF-8")
            } catch (e: Exception) {
                rawSrc
            }
        } else {
            rawSrc
        }

        description = document.select("div[itemprop=description] p").joinToString("\n") { it.text().trim() }
        genre = document.select("a[itemprop=genre]").joinToString { it.text().trim() }
        author = document.selectFirst("a[href^=/teams/] p.font-bold")?.text()?.trim()

        val statusText = document.selectFirst("h1:contains(الحالة) ~ div p")?.text()?.trim()?.lowercase()
            ?: document.selectFirst("h1:contains(الحالة) + div p")?.text()?.trim()?.lowercase()

        status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("ongoing") || statusText.contains("مستمر") -> SManga.ONGOING
            statusText.contains("completed") || statusText.contains("مكتمل") -> SManga.COMPLETED
            statusText.contains("hiatus") || statusText.contains("متوقف") -> SManga.ON_HIATUS
            statusText.contains("cancelled") || statusText.contains("ملغي") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListSelector() = "div.mt-4.space-y-2 > div"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a[href*=/chapter-]")!!
        setUrlWithoutDomain(link.attr("href"))
        name = link.selectFirst("span")?.text()?.trim() ?: "فصل"
        date_upload = parseChapterDate(element)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(chapterListSelector()).mapNotNull { element ->
            val isLocked = element.selectFirst("div.bg-black/50, svg.lucide-lock") != null
            if (isLocked) {
                null
            } else {
                chapterFromElement(element)
            }
        }
    }

    private fun parseChapterDate(element: Element): Long {
        val timeElement = element.selectFirst("time")
        if (timeElement != null) {
            val datetime = timeElement.attr("datetime")
            if (datetime.isNotEmpty()) {
                return parseDate(datetime)
            }
        }

        val spanWithDate = element.selectFirst("span[title]")
        if (spanWithDate != null) {
            val title = spanWithDate.attr("title")
            if (title.isNotEmpty()) {
                return parseRelativeDate(title)
            }
        }
        return 0L
    }

    private fun parseDate(dateStr: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun parseRelativeDate(dateStr: String): Long {
        val cleanDate = dateStr.lowercase().trim()
        val number = cleanDate.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1

        return when {
            cleanDate.contains("ثاني") || cleanDate.contains("seconds") -> System.currentTimeMillis()
            cleanDate.contains("دقيق") || cleanDate.contains("minute") -> {
                val actualNum = when {
                    cleanDate.contains("دقيقة واحدة") -> 1
                    cleanDate.contains("دقيقتين") -> 2
                    else -> number
                }
                System.currentTimeMillis() - (actualNum * 60 * 1000L)
            }
            cleanDate.contains("ساع") || cleanDate.contains("hour") -> {
                val actualNum = when {
                    cleanDate.contains("ساعة واحدة") -> 1
                    cleanDate.contains("ساعتين") -> 2
                    else -> number
                }
                System.currentTimeMillis() - (actualNum * 60 * 60 * 1000L)
            }
            cleanDate.contains("يوم") || cleanDate.contains("أيام") || cleanDate.contains("day") -> {
                val actualNum = if (cleanDate.contains("يومين")) 2 else number
                System.currentTimeMillis() - (actualNum * 24 * 60 * 60 * 1000L)
            }
            cleanDate.contains("أسبوع") || cleanDate.contains("أسابيع") || cleanDate.contains("week") -> {
                System.currentTimeMillis() - (number * 7 * 24 * 60 * 60 * 1000L)
            }
            cleanDate.contains("شهر") || cleanDate.contains("أشهر") || cleanDate.contains("month") -> {
                System.currentTimeMillis() - (number * 30 * 24 * 60 * 60 * 1000L)
            }
            cleanDate.contains("سنة") || cleanDate.contains("سنوات") || cleanDate.contains("عام") || cleanDate.contains("year") -> {
                System.currentTimeMillis() - (number * 365 * 24 * 60 * 60 * 1000L)
            }
            else -> 0L
        }
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.comic-images-wrapper img, figure.image-container img").mapIndexed { i, element ->
            val url = element.attr("abs:src").ifEmpty { element.attr("abs:data-src") }
            Page(i, imageUrl = url)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // =============================== Filters ==============================
    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        Filter.Separator(),
        GenreFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "ترتيب حسب",
            arrayOf(
                Pair("popular", "الأكثر شعبية"),
                Pair("latest_chapters", "أحدث الفصول"),
                Pair("title", "العنوان"),
                Pair("created_at", "تاريخ الإضافة"),
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "النوع",
            arrayOf(
                Pair("", "الكل"),
                Pair("MANHWA", "مانهوا"),
                Pair("MANGA", "مانجا"),
                Pair("SPANISH", "إسباني"),
                Pair("RUSSIAN", "روسي"),
            ),
        )

    private class StatusFilter :
        UriPartFilter(
            "الحالة",
            arrayOf(
                Pair("", "الكل"),
                Pair("ONGOING", "مستمر"),
                Pair("COMPLETED", "مكتمل"),
                Pair("CANCELLED", "ملغي"),
                Pair("HIATUS", "متوقف"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "التصنيف",
            arrayOf(
                Pair(null, "الكل"),
                Pair("+1", "أكشن"),
                Pair("+7", "خيال"),
                Pair("+8", "رومانسي"),
                Pair("+9", "كوميدي"),
                Pair("+12", "دراما"),
                Pair("+47", "فانتازيا"),
                Pair("+2", "حريم"),
                Pair("+13", "تاريخي"),
                Pair("+95", "جريمة"),
                Pair("+5", "شونين"),
                Pair("+29", "شوجو"),
                Pair("+19", "جوسي"),
                Pair("+15", "سينين"),
                Pair("+25", "غموض"),
                Pair("+26", "مأساة"),
                Pair("+38", "رعب"),
                Pair("+6", "مغامرات"),
                Pair("+16", "خارق للطبيعة"),
                Pair("+17", "شياطين"),
                Pair("+18", "حياة مدرسية"),
                Pair("+27", "شريحة من الحياة"),
                Pair("+28", "فنون قتالية"),
                Pair("+30", "إيسيكاي"),
                Pair("+34", "نفسي"),
                Pair("+39", "عسكري"),
                Pair("+40", "رياضي"),
                Pair("+44", "زومبي"),
                Pair("+57", "انتقام"),
                Pair("+72", "نظام"),
            ),
        )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String?, String>>,
    ) : Filter.Select<String>(
        displayName,
        vals.map { it.second }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].first
    }
}
