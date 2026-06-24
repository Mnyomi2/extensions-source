package eu.kanade.tachiyomi.extension.ar.teamx

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

class Teamx :
    HttpSource(),
    ConfigurableSource {
    override val name = "Teamx"

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
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()
            .addQueryParameter("[PopularSortKey]", "[PopularSortValue]")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("[MangaCardSelector]").map { element ->
            SManga.create().apply {
                val titleEl = element.selectFirst("[CardTitleSelector]") ?: element.selectFirst("h3")
                title = titleEl?.text()?.trim() ?: ""
                url = element.selectFirst("a")?.attr("href") ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        val nextPageElement = document.selectFirst("[NextPageSelector]")
        val hasNextPage = nextPageElement != null &&
            nextPageElement.attr("href").isNotEmpty() &&
            nextPageElement.attr("aria-disabled") != "true"

        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()
            .addQueryParameter("[LatestSortKey]", "[LatestSortValue]")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search & Directory
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addQueryParameter("[SearchQueryKey]", query)
        }

        url.addQueryParameter("page", page.toString())

        // Apply filters
        val genresList = mutableListOf<String>()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("[SortParamKey]", filter.toUriValue())
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
            url.addQueryParameter("[GenreParamKey]", genresList.joinToString(","))
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("[DetailTitleSelector]")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("[DetailCoverSelector]")?.attr("abs:src")
            ?: document.selectFirst("img")?.attr("abs:src")

        description = document.select("[DetailDescSelector]")
            .joinToString("\n") { it.text().trim() }
            .takeIf { it.isNotEmpty() }

        // Wildcard class matcher used here to bypass potential slash selector parser crashes
        genre = document.select("[DetailGenreSelector]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

        val statusText = document.text()
        status = when {
            statusText.contains("[OngoingKeyword]") -> SManga.ONGOING
            statusText.contains("[CompletedKeyword]") -> SManga.COMPLETED
            statusText.contains("[HiatusKeyword]") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        author = document.selectFirst("[DetailAuthorSelector]")?.text()?.trim()
        artist = document.selectFirst("[DetailArtistSelector]")?.text()?.trim()
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val cleanNamePref = preferences.getBoolean(CLEAN_CHAPTER_NAME_KEY, true)
        val unwantedPatterns = listOf(
            "[UnwantedText1]",
            "[UnwantedText2]",
            "[UnwantedText3]",
        )

        // Select only anchors inside actual list cards to ignore main page "Read First" buttons
        val elements = document.select("[ChapterCardAnchorSelector]")
            .ifEmpty { document.select("a[href*=[ChapterUrlMatchPath]]") }
            .filterNot { element ->
                val text = element.text().lowercase()
                unwantedPatterns.any { pattern -> text.contains(pattern) }
            }

        val chapters = elements.map { element ->
            SChapter.create().apply {
                url = element.attr("href")

                var nameText = element.text().trim()
                if (cleanNamePref) {
                    val elementCopy = element.clone()
                    // Strip extraneous badges, dates, or tags dynamically
                    elementCopy.select("[UnwantedBadgesSelector], span:contains(/)").remove()
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
            cleanDate.contains("[RelativeJustNowWord]") -> System.currentTimeMillis()
            cleanDate.contains("[RelativeTodayWord]") -> System.currentTimeMillis()
            cleanDate.contains("[RelativeYesterdayWord]") -> System.currentTimeMillis() - 86400000L
            else -> 0L
        }
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Extract from serialized props element (e.g., Astro scripts) to bypass honeypot traps
        val viewerElement = document.selectFirst("[HydratedComponentSelector]")
        if (viewerElement != null) {
            val propsJson = viewerElement.attr("props")
            val unescapedProps = propsJson
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            val urlRegex = """https?://[^"\s]+\\.(?:jpg|jpeg|png|webp|gif)[^"\s]*""".toRegex()
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

        // Standard DOM fallback parsing
        val imageElements = document.select("[ReaderImgSelector]")
        if (imageElements.isNotEmpty()) {
            return imageElements.mapIndexed { index, img ->
                val imageUrl = img.attr("abs:data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("abs:src")
                Page(index, "", imageUrl)
            }
        }

        throw Exception("No pages were found for this chapter")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Preferences Setup
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF_KEY
            title = BASE_URL_PREF_TITLE
            val currentVal = preferences.getString(BASE_URL_PREF_KEY, BASE_URL_PREF_DEFAULT) ?: BASE_URL_PREF_DEFAULT
            summary = "[CurrentPrefPrefix] $currentVal"
            setDefaultValue(BASE_URL_PREF_DEFAULT)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue as String
                summary = "[CurrentPrefPrefix] $value"
                preferences.edit().putString(BASE_URL_PREF_KEY, value).commit()
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

    class GenreList(genres: List<Genre>) : Filter.Group<Genre>("[FiltersCategoryTitle]", genres)

    class SortFilter :
        Filter.Select<String>(
            "[SortFilterTitle]",
            arrayOf("[SortLabel1]", "[SortFilter2]", "[SortFilter3]"),
        ) {
        fun toUriValue() = when (state) {
            0 -> "[SortValue1]"
            1 -> "[SortValue2]"
            2 -> "[SortValue3]"
            else -> "[SortValue1]"
        }
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        GenreList(getGenres()),
    )

    private fun getGenres() = listOf(
        Genre("[Genre1]"),
        Genre("[Genre2]"),
        Genre("[Genre3]"),
    )

    companion object {
        private const val BASE_URL_PREF_KEY = "baseUrl_pref"
        private const val BASE_URL_PREF_TITLE = "[BaseUrlSettingTitle]"
        private const val BASE_URL_PREF_DEFAULT = "[DefaultBaseUrl]"

        private const val CLEAN_CHAPTER_NAME_KEY = "cleanChapterName_pref"
        private const val CLEAN_CHAPTER_NAME_TITLE = "[CleanNameSettingTitle]"
        private const val CLEAN_CHAPTER_NAME_SUMMARY = "[CleanNameSettingSummary]"
    }
}
