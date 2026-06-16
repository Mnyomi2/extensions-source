package eu.kanade.tachiyomi.extension.ar.mangasid

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

class Mangasid :
    HttpSource(),
    ConfigurableSource {
    override val name = "Mangasid"

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF_KEY, BASE_URL_PREF_DEFAULT) ?: BASE_URL_PREF_DEFAULT

    override val lang = "ar"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }

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

        genre = document.select("a[href*=genres=], span.bg-secondary, span[class*=bg-primary/20], .genre-badge")
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
        val cleanNamePref = preferences.getBoolean(CLEAN_CHAPTER_NAME_KEY, true)

        val chapters = document.select("a[href*=/reader/]").map { element ->
            SChapter.create().apply {
                url = element.attr("href")

                var nameText = element.text().trim()
                if (cleanNamePref) {
                    val elementCopy = element.clone()
                    // Remove nested labels containing dates like '16/2026/6' and AI tags
                    elementCopy.select("span:contains(/), span:matches(\\d+/\\d+/\\d+), .date, .time").remove()
                    elementCopy.select("span:contains(AI), .badge, .ai-badge").remove()
                    val cleaned = elementCopy.text().trim()
                        .replace("\n", " ")
                        .replace("\\s+".toRegex(), " ")
                    if (cleaned.isNotEmpty()) {
                        nameText = cleaned
                    }
                }

                name = nameText
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

        // Extract from serialized Astro ChapterImageViewer props to bypass scraper trap image scripts
        val viewerElement = document.selectFirst("astro-island[component-url*=ChapterImageViewer]")
        if (viewerElement != null) {
            val propsJson = viewerElement.attr("props")
            val unescapedProps = propsJson
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            val urlRegex = """https?://[^"\s]+\.(?:jpg|jpeg|png|webp|gif)[^"\s]*""".toRegex()
            val urls = urlRegex.findAll(unescapedProps)
                .map { it.value.trim() }
                .filterNot { it.contains("logo") || it.contains("avatar") || it.contains("cover") }
                .distinct()
                .toList()

            if (urls.isNotEmpty()) {
                return urls.mapIndexed { index, url ->
                    Page(index, "", url)
                }
            }
        }

        // Fallback parser in case elements render without active hydrated scripts
        val imageElements = document.select("div.manga-page img, div.reader-images img, div.reader-container img")
        if (imageElements.isNotEmpty()) {
            return imageElements.mapIndexed { index, img ->
                val imageUrl = img.attr("abs:data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("abs:src")
                Page(index, "", imageUrl)
            }
        }

        throw Exception("لم يتم العثور على صفحات لهذا الفصل.")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Preferences Setup
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_KEY
            title = BASE_URL_PREF_TITLE
            summary = "الحالي: %s"
            setDefaultValue(BASE_URL_PREF_DEFAULT)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(BASE_URL_PREF_KEY, newValue as String).commit()
            }
        }

        val cleanChapterPref = CheckBoxPreference(screen.context).apply {
            key = CLEAN_CHAPTER_NAME_KEY
            title = CLEAN_CHAPTER_NAME_TITLE
            summary = CLEAN_CHAPTER_NAME_SUMMARY
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(CLEAN_CHAPTER_NAME_KEY, newValue as Boolean).commit()
            }
        }

        screen.addPreference(baseUrlPref)
        screen.addPreference(cleanChapterPref)
    }

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
        Genre("أكاديمية"),
        Genre("أكشن"),
        Genre("ألعاب"),
        Genre("الات"),
        Genre("الجانب المظلم من الحياة"),
        Genre("الخيال العلمي"),
        Genre("النجاة"),
        Genre("الواقع الافتراضي"),
        Genre("الهة"),
        Genre("امرأة شريرة"),
        Genre("انتقال"),
        Genre("انتقام"),
        Genre("بطل خارق"),
        Genre("بطل غير اعتيادي"),
        Genre("بطل مخطط"),
        Genre("بوابات"),
        Genre("تاريخي"),
        Genre("تراجم"),
        Genre("تراجيدي"),
        Genre("تجسيد"),
        Genre("تحقيق"),
        Genre("تخطيط"),
        Genre("ترويض"),
        Genre("تشويق"),
        Genre("تصوف"),
        Genre("تلوين رسمي"),
        Genre("تنمر"),
        Genre("تناسخ"),
        Genre("جريمة"),
        Genre("جوسيه"),
        Genre("جواسيس"),
        Genre("جندر اسواب"),
        Genre("جندر بندر"),
        Genre("حديث"),
        Genre("حرب"),
        Genre("حريم"),
        Genre("حريم عكسى"),
        Genre("حياة مدرسية"),
        Genre("حيوانات"),
        Genre("خارق"),
        Genre("خارق للطبيعة"),
        Genre("خيال"),
        Genre("داخل رواية"),
        Genre("داخل لعبة"),
        Genre("دراما"),
        Genre("دموي"),
        Genre("راشد"),
        Genre("رعب"),
        Genre("رعاية اطفال"),
        Genre("رواية عربية"),
        Genre("رومانسي"),
        Genre("رومانسى"),
        Genre("رياضة"),
        Genre("رياضى"),
        Genre("ساموراي"),
        Genre("سايكوباث"),
        Genre("سحر"),
        Genre("سفر عبر الزمن"),
        Genre("سينين"),
        Genre("شريحة من الحياة"),
        Genre("شرطة"),
        Genre("شركة"),
        Genre("شوجو"),
        Genre("شونين"),
        Genre("شياطين"),
        Genre("طبخ"),
        Genre("طبي"),
        Genre("عائلى"),
        Genre("عالم مختلف"),
        Genre("عسكري"),
        Genre("عصور وسطى"),
        Genre("عنف"),
        Genre("غموض"),
        Genre("فانتازيا"),
        Genre("فنتازيا"),
        Genre("فنون الدفاع عن النفس"),
        Genre("فنون قتال"),
        Genre("فوق الطبيعة"),
        Genre("قوة خارقة"),
        Genre("كائنات فضائية"),
        Genre("كوميدي"),
        Genre("كوميك"),
        Genre("لعبه"),
        Genre("لعبة فيديو"),
        Genre("مافيا"),
        Genre("مانجا"),
        Genre("مانجا ملونة"),
        Genre("مانهوا"),
        Genre("مأساة"),
        Genre("مأساوي"),
        Genre("مؤامرات"),
        Genre("مجموعة قصص"),
        Genre("مدرسه"),
        Genre("مستذئب"),
        Genre("مصاصى الدماء"),
        Genre("معالج"),
        Genre("مغني"),
        Genre("مغامرة"),
        Genre("مقتبسة"),
        Genre("مقطع طولي"),
        Genre("ملائكة"),
        Genre("ملونة"),
        Genre("ممالك"),
        Genre("موريم"),
        Genre("موسيقى"),
        Genre("ناضج"),
        Genre("نبالة"),
        Genre("نفسي"),
        Genre("نهاية العالم"),
        Genre("ون شوت"),
    )

    companion object {
        private const val BASE_URL_PREF_KEY = "baseUrl_pref"
        private const val BASE_URL_PREF_TITLE = "عنوان الموقع (Base URL)"
        private const val BASE_URL_PREF_DEFAULT = "https://mangasid.com"

        private const val CLEAN_CHAPTER_NAME_KEY = "cleanChapterName_pref"
        private const val CLEAN_CHAPTER_NAME_TITLE = "تنظيف اسم الفصل"
        private const val CLEAN_CHAPTER_NAME_SUMMARY = "إزالة التواريخ والوسوم الإضافية المدمجة في اسم الفصل"
    }
}
