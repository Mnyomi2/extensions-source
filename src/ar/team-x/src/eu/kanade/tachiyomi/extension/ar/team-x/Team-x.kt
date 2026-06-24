package eu.kanade.tachiyomi.extension.ar.`team-x`

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
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
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class TeamX :
    HttpSource(),
    ConfigurableSource {

    override val name = "Team-X"

    override val lang = "ar"

    override val supportsLatest = true

    private val defaultBaseUrl = "https://olympustaff.com"

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF_KEY, defaultBaseUrl) ?: defaultBaseUrl

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val nextPageSelector = "a[rel=next]"
    private val popularMangaSelector = "div.listupd div.bsx"

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series/" + if (page > 1) "?page=$page" else "", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val entries = document.select(popularMangaSelector).map { element ->
            SManga.create().apply {
                title = element.selectFirst("a")?.attr("title")?.trim() ?: ""
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                thumbnail_url = element.selectFirst("img")?.let {
                    if (it.hasAttr("data-src")) {
                        it.attr("abs:data-src")
                    } else {
                        it.attr("abs:src")
                    }
                }
            }
        }

        val hasNextPage = document.selectFirst(nextPageSelector) != null

        return MangasPage(entries, hasNextPage)
    }

    // Latest
    private val titlesAdded = mutableSetOf<String>()
    private val thumbnailSuffix = "thumbnail_"

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) titlesAdded.clear()
        return GET("$baseUrl/" + if (page > 1) "?page=$page" else "", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val unfilteredManga = document.select("div.last-chapter div.box")

        val mangaList = unfilteredManga
            .map { element ->
                SManga.create().apply {
                    val linkElement = element.select("div.info a")
                    title = linkElement.select("h3").text().trim()
                    setUrlWithoutDomain(linkElement.first()!!.attr("href"))
                    thumbnail_url = element.select("div.imgu img").first()!!.absUrl("src").replace(thumbnailSuffix, "")
                }
            }
            .distinctBy { it.title }
            .filter { !titlesAdded.contains(it.title) }

        titlesAdded.addAll(mangaList.map { it.title })

        val hasNextPage = document.selectFirst(nextPageSelector) != null

        return MangasPage(mangaList, hasNextPage)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/ajax/search".toHttpUrl().newBuilder()
            url.addQueryParameter("keyword", query)
            return GET(url.build(), headers)
        }

        val url = "$baseUrl/series".toHttpUrl().newBuilder()
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> url.addQueryParameter("type", filter.toUriPart() ?: "")
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart() ?: "")
                is GenreFilter -> url.addQueryParameter("genre", filter.toUriPart() ?: "")
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    private val searchMangaSelector = "a.items-center, $popularMangaSelector"

    override fun searchMangaParse(response: Response): MangasPage {
        if ("series" in response.request.url.pathSegments) {
            return popularMangaParse(response)
        }

        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector).map { element ->
            SManga.create().apply {
                title = element.selectFirst("h4")?.text()?.trim() ?: element.selectFirst("a")?.attr("title")?.trim() ?: ""
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                setUrlWithoutDomain(element.absUrl("href"))
            }
        }
        return MangasPage(mangas, false)
    }

    // Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("div.author-info-title h1")?.text()?.trim() ?: ""

        var desc = document.select("div.review-content").text().trim()
        if (desc.isEmpty()) {
            desc = document.select("div.review-content p").text().trim()
        }
        description = desc

        genre = document.select("div.review-author-info a").joinToString { it.text().trim() }
        thumbnail_url = document.selectFirst("div.text-right img")?.absUrl("src")

        status = document.selectFirst(".full-list-info > small:first-child:contains(الحالة) + small")
            ?.text()
            ?.trim()
            ?.toStatus() ?: SManga.UNKNOWN

        author = document.selectFirst(".full-list-info > small:first-child:contains(الرسام) + small")
            ?.text()
            ?.trim()
            ?.takeIf { it != "غير معروف" }
    }

    private fun String.toStatus() = when (this) {
        "مستمرة", "يصدر" -> SManga.ONGOING
        "قادم قريبًا" -> SManga.ONGOING
        "مكتمل", "مكتملة", "منتهي" -> SManga.COMPLETED
        "متوقف", "متوقفة" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val allElements = mutableListOf<Element>()
        var document = response.asJsoup()

        while (true) {
            val pageChapters = document.select("div.chapter-card")
            if (pageChapters.isEmpty()) {
                break
            }

            allElements += pageChapters

            val hasNextPage = document.selectFirst(nextPageSelector) != null
            if (!hasNextPage) {
                break
            }

            val nextUrl = document.selectFirst(nextPageSelector)!!.attr("href")
            document = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
        }

        val unwantedPatterns = listOf("ابدأ القراءة", "اقرأ الآن", "البداية", "أول فصل")

        return allElements.mapNotNull { element ->
            // Exclude strictly locked/paid chapters that require coins
            if (element.selectFirst("span.locked, i.fa-lock") != null) return@mapNotNull null
            if (element.selectFirst("a")?.attr("href") == "#") return@mapNotNull null

            val rawTitle = element.selectFirst("div.chapter-info div.chapter-title")?.text() ?: ""
            if (unwantedPatterns.any { rawTitle.contains(it, true) }) return@mapNotNull null

            SChapter.create().apply {
                val chpNum = element.attr("data-number")

                // Chapter List Cleaning using Jsoup clone removing badges/dates
                val copy = element.clone()
                copy.select("span:contains(/), .date, .time, .badge").remove()

                val cleanTitle = copy.selectFirst("div.chapter-info div.chapter-title")?.text()?.trim()

                name = buildString {
                    append("الفصل $chpNum")
                    cleanTitle
                        ?.takeIf {
                            it.isNotBlank() &&
                                it != chpNum &&
                                it != "الفصل $chpNum" &&
                                it != "الفصل رقم $chpNum"
                        }?.let { append(" - $it") }
                } + "\u200F"

                val dataDate = element.attr("data-date").toLongOrNull()
                date_upload = if (dataDate != null && dataDate > 0L) {
                    dataDate * 1000
                } else {
                    parseChapterDate(element.text())
                }

                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            }
        }
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

        return document.select("div.image_list canvas[data-src], div.image_list img[src], div[class*=image_list] img[class*=img-fluid]")
            .mapIndexed { i, element ->
                val url = when {
                    element.hasAttr("data-src") -> element.absUrl("data-src")
                    else -> element.absUrl("src")
                }
                Page(i, imageUrl = url)
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("تنبيه: يتم تجاهل الفلاتر عند استخدام البحث النصي."),
        Filter.Separator(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "نوع المانجا",
        arrayOf(
            Pair(null, "الكل"),
            Pair("مانها صيني", "مانها صيني"),
            Pair("مانجا ياباني", "مانجا ياباني"),
            Pair("ويب تون انجليزية", "ويب تون انجليزية"),
            Pair("مانهوا كورية", "مانهوا كورية"),
            Pair("ويب تون يابانية", "ويب تون يابانية"),
            Pair("عربي", "عربي"),
            Pair("مانجا تون يابانية", "مانجا تون يابانية"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "حالة المانجا",
        arrayOf(
            Pair(null, "الكل"),
            Pair("مستمرة", "مستمرة"),
            Pair("متوقف", "متوقف"),
            Pair("مكتمل", "مكتمل"),
            Pair("قادم قريبًا", "قادم قريبًا"),
            Pair("متروك", "متروك"),
            Pair("موسم منتهي", "موسم منتهي"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "تصنيف المانجا",
        arrayOf(
            Pair(null, "الكل"),
            Pair("أكشن", "أكشن"),
            Pair("إثارة", "إثارة"),
            Pair("إيسيكاي", "إيسيكاي"),
            Pair("بطل غير إعتيادي", "بطل غير إعتيادي"),
            Pair("خيال", "خيال"),
            Pair("دموي", "دموي"),
            Pair("نظام", "نظام"),
            Pair("صقل", "صقل"),
            Pair("قوة خارقة", "قوة خارقة"),
            Pair("فنون قتال", "فنون قتال"),
            Pair("غموض", "غموض"),
            Pair("وحوش", "وحوش"),
            Pair("شونين", "شونين"),
            Pair("حريم", "حريم"),
            Pair("خيال علمي", "خيال علمي"),
            Pair("مغامرات", "مغامرات"),
            Pair("دراما", "دراما"),
            Pair("خارق للطبيعة", "خارق للطبيعة"),
            Pair("سحر", "سحر"),
            Pair("كوميدي", "كوميدي"),
            Pair("ويب تون", "ويب تون"),
            Pair("زمكاني", "زمكاني"),
            Pair("رومانسي", "رومانسي"),
            Pair("شياطين", "شياطين"),
            Pair("فانتازيا", "فانتازيا"),
            Pair("عنف", "عنف"),
            Pair("ملائكة", "ملائكة"),
            Pair("بعد الكارثة", "بعد الكارثة"),
            Pair("إعادة إحياء", "إعادة إحياء"),
            Pair("اعمار", "اعمار"),
            Pair("ثأر", "ثأر"),
            Pair("زنزانات", "زنزانات"),
            Pair("تاريخي", "تاريخي"),
            Pair("حرب", "حرب"),
            Pair("خارق", "خارق"),
            Pair("سنين", "سنين"),
            Pair("عسكري", "عسكري"),
            Pair("بوليسي", "بوليسي"),
            Pair("حياة مدرسية", "حياة مدرسية"),
            Pair("واقع افتراضي", "واقع افتراضي"),
            Pair("داخل لعبة", "داخل لعبة"),
            Pair("داخل رواية", "داخل رواية"),
            Pair("الحياة اليومية", "الحياة اليومية"),
            Pair("رعب", "رعب"),
            Pair("طبخ", "طبخ"),
            Pair("مدرسي", "مدرسي"),
            Pair("زومبي", "زومبي"),
            Pair("شوجو", "شوجو"),
            Pair("معالج", "معالج"),
            Pair("شريحة من الحياة", "شريحة من الحياة"),
            Pair("نفسي", "نفسي"),
            Pair("تاريخ", "تاريخ"),
            Pair("أكاديمية", "أكاديمية"),
            Pair("أرواح", "أرواح"),
            Pair("تراجيدي", "تراجيدي"),
            Pair("ابراج", "ابراج"),
            Pair("رياضي", "رياضي"),
            Pair("مصاص دماء", "مصاص دماء"),
            Pair("طبي", "طبي"),
            Pair("مأساة", "مأساة"),
            Pair("إيتشي", "إيتشي"),
            Pair("جوسي", "جوسي"),
            Pair("مغني", "مغني"),
            Pair("تنمر", "تنمر"),
            Pair("حيوانات أليفة", "حيوانات أليفة"),
            Pair("حشرات", "حشرات"),
            Pair("جواسيس", "جواسيس"),
            Pair("ممثل", "ممثل"),
            Pair("نينجا", "نينجا"),
            Pair("تمثيل", "تمثيل"),
            Pair("أفلام", "أفلام"),
            Pair("فنون قتالية", "فنون قتالية"),
            Pair("عائلة", "عائلة"),
            Pair("تناسخ", "تناسخ"),
            Pair("دراما مدرسية", "دراما مدرسية"),
            Pair("حركة", "حركة"),
            Pair("انتقام", "انتقام"),
            Pair("مانهوا طبية", "مانهوا طبية"),
            Pair("موريم", "موريم"),
            Pair("دراما نفسية", "دراما نفسية"),
            Pair("نهاية العالم", "نهاية العالم"),
            Pair("بقاء", "بقاء"),
            Pair("نظام/ العاب", "نظام/ العاب"),
            Pair("مابعد نهاية العالم", "مابعد نهاية العالم"),
            Pair("سفر عبر الزمن", "سفر عبر الزمن"),
            Pair("إجرام", "إجرام"),
            Pair("عوالم", "عوالم"),
            Pair("روايات", "روايات"),
            Pair("فن", "فن"),
            Pair("أدب", "أدب"),
            Pair("أشباح", "أشباح"),
            Pair("بطل خارق", "بطل خارق"),
            Pair("جريمة", "جريمة"),
            Pair("طب", "طب"),
            Pair("العاب فيديو", "العاب فيديو"),
            Pair("ايسكاي", "ايسكاي"),
            Pair("فانتازي", "فانتازي"),
            Pair("فوق الطبيعة", "فوق الطبيعة"),
            Pair("حديث", "حديث"),
            Pair("قوى خارقة", "قوى خارقة"),
            Pair("الحياة المدرسية", "الحياة المدرسية"),
            Pair("ميكا", "ميكا"),
            Pair("رياضة", "رياضة"),
            Pair("كرة القدم", "كرة القدم"),
            Pair("كرة اليد", "كرة اليد"),
            Pair("موريوم", "موريوم"),
            Pair("طوائف", "طوائف"),
            Pair("تحقيق بوليسي", "تحقيق بوليسي"),
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

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_KEY
            title = "عنوان الموقع (Base URL)"
            val currentVal = preferences.getString(BASE_URL_PREF_KEY, defaultBaseUrl) ?: defaultBaseUrl
            summary = "الحالي: $currentVal"
            setDefaultValue(defaultBaseUrl)
            dialogTitle = "عنوان الموقع (Base URL)"
            dialogMessage = "الافتراضي: $defaultBaseUrl"

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as String
                summary = "الحالي: $value"
                preferences.edit().putString(BASE_URL_PREF_KEY, value).commit()
                Toast.makeText(screen.context, "لتطبيق الإعدادات الجديدة أعد تشغيل التطبيق.", Toast.LENGTH_LONG).show()
                true
            }
        }

        screen.addPreference(baseUrlPref)
    }

    companion object {
        private const val BASE_URL_PREF_KEY = "overrideBaseUrl"
    }
}
