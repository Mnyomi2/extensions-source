package eu.kanade.tachiyomi.extension.ar.azora

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class Azora :
    HttpSource(),
    ConfigurableSource {
    override val name = "Azora"

    override val baseUrl = "https://azoramoon.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .build()

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series?sortBy=popular&page=$page", headers)
    }

    override fun popularMangaSelector() = "div.grid > div > div.relative"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val linkElement = element.selectFirst("div.flex-1 a.font-bold")!!
        val imgElement = element.selectFirst("img")

        manga.setUrlWithoutDomain(linkElement.attr("href"))
        manga.title = linkElement.text().trim()

        imgElement?.attr("abs:src")?.let { src ->
            manga.thumbnail_url = if (src.contains("wsrv.nl/?url=")) {
                URLDecoder.decode(src.substringAfter("url=").substringBefore("&"), "UTF-8")
            } else {
                src
            }
        }

        return manga
    }

    override fun popularMangaNextPageSelector() = "button[aria-label=الصفحة التالية]:not([disabled])"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series?sortBy=latest_chapters&page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("searchTerm", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sortBy", filter.toUriPart())
                is TypeFilter -> url.addQueryParameter("seriesType", filter.toUriPart())
                is StatusFilter -> url.addQueryParameter("seriesStatus", filter.toUriPart())
                is GenreFilter -> filter.toUriPart()?.let { url.addQueryParameter("genres", it) }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaParse(response: okhttp3.Response): MangasPage {
        val page = super.searchMangaParse(response)
        // Filter out Text Novels
        val mangas = page.mangas.filterNot { it.title.contains("رواية") }
        return MangasPage(mangas, page.hasNextPage)
    }

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: ""

        val imgElement = document.selectFirst("img[itemprop=image]")
        imgElement?.attr("abs:src")?.let { src ->
            manga.thumbnail_url = if (src.contains("wsrv.nl/?url=")) {
                URLDecoder.decode(src.substringAfter("url=").substringBefore("&"), "UTF-8")
            } else {
                src
            }
        }

        manga.description = document.select("div[itemprop=description] p").joinToString("\n") { it.text() }
        manga.genre = document.select("a[itemprop=genre]").joinToString { it.text().trim() }
        manga.author = document.selectFirst("a[href^=/teams/] p.font-bold")?.text()?.trim()

        val statusText = document.selectFirst("svg.lucide-dna + h1")
            ?.parent()
            ?.selectFirst("p")
            ?.text()
            ?.trim()
            ?.lowercase()

        manga.status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("مستمر") || statusText == "ongoing" -> SManga.ONGOING
            statusText.contains("مكتمل") || statusText == "completed" -> SManga.COMPLETED
            statusText.contains("متوقف") || statusText == "hiatus" -> SManga.ON_HIATUS
            statusText.contains("ملغي") || statusText == "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    // ============================== Chapters ==============================
    override fun chapterListSelector() = "div[data-series-tab-panel=chapters] a[href*=/chapter-]"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(element.attr("href"))
        chapter.name = element.selectFirst("span:not([class])")?.text()?.trim() ?: "فصل"
        
        val dateString = element.selectFirst("time")?.attr("datetime")
        if (dateString != null) {
            chapter.date_upload = parseDate(dateString)
        }

        return chapter
    }

    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        return super.chapterListParse(response).filterNot { chapter ->
            // Use URL matching or DOM checking to exclude strictly locked chapters if needed
            // Our selector doesn't pass the Element here, but we can filter inside `chapterFromElement`
            false
        }
    }

    override fun documentParse(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        document.select(chapterListSelector()).forEach { element ->
            // Skip locked/paid chapters that require coins
            val isLocked = element.selectFirst("svg.lucide-lock") != null
            if (!isLocked) {
                chapters.add(chapterFromElement(element))
            }
        }
        return chapters
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        return document.select("figure.image-container img").mapIndexed { i, element ->
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

    private class SortFilter : UriPartFilter(
        "ترتيب حسب",
        arrayOf(
            Pair("popular", "الأكثر شعبية"),
            Pair("latest_chapters", "أحدث الفصول"),
            Pair("title", "العنوان"),
            Pair("created_at", "تاريخ الإضافة"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "النوع",
        arrayOf(
            Pair("", "الكل"),
            Pair("MANHWA", "مانهوا"),
            Pair("MANGA", "مانجا"),
            Pair("SPANISH", "إسباني"),
            Pair("RUSSIAN", "روسي"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "الحالة",
        arrayOf(
            Pair("", "الكل"),
            Pair("ONGOING", "مستمر"),
            Pair("COMPLETED", "مكتمل"),
            Pair("CANCELLED", "ملغي"),
            Pair("HIATUS", "متوقف"),
        ),
    )

    private class GenreFilter : UriPartFilter(
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
