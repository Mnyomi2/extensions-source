import os
import sys
import subprocess
import urllib.request
from io import BytesIO
from pathlib import Path

# ==========================================
# --- CONFIGURATION ---
# ==========================================
INPUT_NAME = "Mangasid" 
LANGUAGE_CODE = "ar"

# Paste EITHER an image URL (http/https...) OR raw SVG code (<svg...) here.
# The script will automatically detect which one you provided.
ICON_INPUT = """https://static.vecteezy.com/system/resources/previews/057/068/323/large_2x/single-fresh-red-strawberry-on-table-green-background-food-fruit-sweet-macro-juicy-plant-image-photo.jpg"""

# ==========================================


# --- Logic to handle casing automatically ---
EXTENSION_NAME = INPUT_NAME.strip().capitalize() 
LOWER_NAME = INPUT_NAME.strip().lower() 

def install_requirements(need_svg=False, need_pillow=False):
    resvg_py = None
    Image = None
    
    if need_svg:
        try:
            import resvg_py
        except ImportError:
            print("Installing resvg_py...")
            subprocess.check_call([sys.executable, "-m", "pip", "install", "resvg-py"])
            import resvg_py
            
    if need_pillow:
        try:
            from PIL import Image
        except ImportError:
            print("Installing Pillow for image processing...")
            subprocess.check_call([sys.executable, "-m", "pip", "install", "Pillow"])
            from PIL import Image
            
    return resvg_py, Image

BUILD_GRADLE_TEMPLATE = """ext {{
    extName = '{ext_name}'
    extClass = '.{ext_name}'
    extVersionCode = 2
    isNsfw = false
}}

apply plugin: "kei.plugins.extension.legacy"
"""

KOTLIN_TEMPLATE = """package eu.kanade.tachiyomi.extension.{lang}.{lower_name}

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

class {ext_name} :
    HttpSource(),
    ConfigurableSource {{
    override val name = "{ext_name}"

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF_KEY, BASE_URL_PREF_DEFAULT) ?: BASE_URL_PREF_DEFAULT

    override val lang = "{lang}"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    private val preferences: SharedPreferences by lazy {{
        Injekt.get<Application>().getSharedPreferences("source_$id", Context.MODE_PRIVATE)
    }}

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request {{
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()
            .addQueryParameter("[PopularSortKey]", "[PopularSortValue]")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }}

    override fun popularMangaParse(response: Response): MangasPage {{
        val document = response.asJsoup()
        val mangas = document.select("[MangaCardSelector]").map {{ element ->
            SManga.create().apply {{
                val titleEl = element.selectFirst("[CardTitleSelector]") ?: element.selectFirst("h3")
                title = titleEl?.text()?.trim() ?: ""
                url = element.selectFirst("a")?.attr("href") ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }}
        }}

        val nextPageElement = document.selectFirst("[NextPageSelector]")
        val hasNextPage = nextPageElement != null &&
            nextPageElement.attr("href").isNotEmpty() &&
            nextPageElement.attr("aria-disabled") != "true"

        return MangasPage(mangas, hasNextPage)
    }}

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {{
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()
            .addQueryParameter("[LatestSortKey]", "[LatestSortValue]")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }}

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search & Directory
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {{
        val url = "$baseUrl/[MangaListPath]".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {{
            url.addQueryParameter("[SearchQueryKey]", query)
        }}

        url.addQueryParameter("page", page.toString())

        // Apply filters
        val genresList = mutableListOf<String>()
        filters.forEach {{ filter ->
            when (filter) {{
                is SortFilter -> {{
                    url.addQueryParameter("[SortParamKey]", filter.toUriValue())
                    if (filter.toUriValue() == "title") {{
                        url.addQueryParameter("sortOrder", "ASC")
                    }}
                }}
                is GenreList -> {{
                    filter.state.forEach {{ genre ->
                        if (genre.state) {{
                            genresList.add(genre.name)
                        }}
                    }}
                }}
                else -> {{}}
            }}
        }}

        if (genresList.isNotEmpty()) {{
            url.addQueryParameter("[GenreParamKey]", genresList.joinToString(","))
        }}

        return GET(url.build(), headers)
    }}

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga Details
    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {{
        val document = response.asJsoup()
        title = document.selectFirst("[DetailTitleSelector]")?.text()?.trim() ?: ""
        thumbnail_url = document.selectFirst("[DetailCoverSelector]")?.attr("abs:src")
            ?: document.selectFirst("img")?.attr("abs:src")

        description = document.select("[DetailDescSelector]")
            .joinToString("\\n") {{ it.text().trim() }}
            .takeIf {{ it.isNotEmpty() }}

        // Wildcard class matcher used here to bypass potential slash selector parser crashes
        genre = document.select("[DetailGenreSelector]")
            .map {{ it.text().trim() }}
            .filter {{ it.isNotEmpty() }}
            .joinToString(", ")

        val statusText = document.text()
        status = when {{
            statusText.contains("[OngoingKeyword]") -> SManga.ONGOING
            statusText.contains("[CompletedKeyword]") -> SManga.COMPLETED
            statusText.contains("[HiatusKeyword]") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }}

        author = document.selectFirst("[DetailAuthorSelector]")?.text()?.trim()
        artist = document.selectFirst("[DetailArtistSelector]")?.text()?.trim()
    }}

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {{
        val document = response.asJsoup()
        val cleanNamePref = preferences.getBoolean(CLEAN_CHAPTER_NAME_KEY, true)
        val unwantedPatterns = listOf(
            "[UnwantedText1]",
            "[UnwantedText2]",
            "[UnwantedText3]",
        )

        // Select only anchors inside actual list cards to ignore main page "Read First" buttons
        val elements = document.select("[ChapterCardAnchorSelector]")
            .ifEmpty {{ document.select("a[href*=[ChapterUrlMatchPath]]") }}
            .filterNot {{ element ->
                val text = element.text().lowercase()
                unwantedPatterns.any {{ pattern -> text.contains(pattern) }}
            }}

        val chapters = elements.map {{ element ->
            SChapter.create().apply {{
                url = element.attr("href")

                var nameText = element.text().trim()
                if (cleanNamePref) {{
                    val elementCopy = element.clone()
                    // Strip extraneous badges, dates, or tags dynamically
                    elementCopy.select("[UnwantedBadgesSelector], span:contains(/)").remove()
                    val cleaned = elementCopy.text().trim()
                        .replace("\\n", " ")
                        .replace("\\\\s+".toRegex(), " ")
                    if (cleaned.isNotEmpty()) {{
                        nameText = cleaned
                    }}
                }}

                name = nameText
                date_upload = parseChapterDate(element.parent()?.text() ?: "")
            }}
        }}
        return chapters.distinctBy {{ it.url }}
    }}

    private fun parseChapterDate(dateStr: String): Long {{
        val cleanDate = dateStr.lowercase()
        return when {{
            cleanDate.contains("[RelativeJustNowWord]") -> System.currentTimeMillis()
            cleanDate.contains("[RelativeTodayWord]") -> System.currentTimeMillis()
            cleanDate.contains("[RelativeYesterdayWord]") -> System.currentTimeMillis() - 86400000L
            else -> 0L
        }}
    }}

    // Pages
    override fun pageListParse(response: Response): List<Page> {{
        val document = response.asJsoup()

        // Extract from serialized props element (e.g., Astro scripts) to bypass honeypot traps
        val viewerElement = document.selectFirst("[HydratedComponentSelector]")
        if (viewerElement != null) {{
            val propsJson = viewerElement.attr("props")
            val unescapedProps = propsJson
                .replace("&quot;", "\\"")
            val unescapedProps = propsJson
                .replace("&quot;", "\\"")
                .replace("&amp;", "&")
                .replace("\\\\/", "/")
                .replace("\\\\u0026", "&")

            val urlRegex = \"\"\"https?://[^"\\s]+\\\\.(?:jpg|jpeg|png|webp|gif)[^"\\s]*\"\"\".toRegex()
            val urls = urlRegex.findAll(unescapedProps)
                .map {{ it.value.trim() }}
                .filterNot {{ it.contains("logo") || it.contains("avatar") || it.contains("cover") }}
                .distinct()
                .toList()

            if (urls.isNotEmpty()) {{
                return urls.mapIndexed {{ index, url ->
                    Page(index, "", url)
                }}
            }}
        }}

        // Standard DOM fallback parsing
        val imageElements = document.select("[ReaderImgSelector]")
        if (imageElements.isNotEmpty()) {{
            return imageElements.mapIndexed {{ index, img ->
                val imageUrl = img.attr("abs:data-src").takeIf {{ it.isNotEmpty() }}
                    ?: img.attr("abs:src")
                Page(index, "", imageUrl)
            }}
        }}

        throw Exception("لم يتم العثور على صفحات لهذا الفصل.")
    }}

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Preferences Setup
    override fun setupPreferenceScreen(screen: PreferenceScreen) {{
        val baseUrlPref = EditTextPreference(screen.context).apply {{
            key = BASE_URL_PREF_KEY
            title = BASE_URL_PREF_TITLE
            val currentVal = preferences.getString(BASE_URL_PREF_KEY, BASE_URL_PREF_DEFAULT) ?: BASE_URL_PREF_DEFAULT
            summary = "[CurrentPrefPrefix] $currentVal"
            setDefaultValue(BASE_URL_PREF_DEFAULT)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener {{ _, newValue ->
                val value = newValue as String
                summary = "[CurrentPrefPrefix] $value"
                preferences.edit().putString(BASE_URL_PREF_KEY, value).commit()
            }}
        }}

        val cleanChapterPref = CheckBoxPreference(screen.context).apply {{
            key = CLEAN_CHAPTER_NAME_KEY
            title = CLEAN_CHAPTER_NAME_TITLE
            summary = CLEAN_CHAPTER_NAME_SUMMARY
            setDefaultValue(true)

            setOnPreferenceChangeListener {{ _, newValue ->
                preferences.edit().putBoolean(CLEAN_CHAPTER_NAME_KEY, newValue as Boolean).commit()
            }}
        }}

        screen.addPreference(baseUrlPref)
        screen.addPreference(cleanChapterPref)
    }}

    // Filters
    class Genre(name: String) : Filter.CheckBox(name)

    class GenreList(genres: List<Genre>) : Filter.Group<Genre>("[FiltersCategoryTitle]", genres)

    class SortFilter :
        Filter.Select<String>(
            "[SortFilterTitle]",
            arrayOf("[SortLabel1]", "[SortFilter2]", "[SortFilter3]"),
        ) {{
        fun toUriValue() = when (state) {{
            0 -> "[SortValue1]"
            1 -> "[SortValue2]"
            2 -> "[SortValue3]"
            else -> "[SortValue1]"
        }}
    }}

    override fun getFilterList() = FilterList(
        SortFilter(),
        GenreList(getGenres()),
    )

    private fun getGenres() = listOf(
        Genre("[Genre1]"),
        Genre("[Genre2]"),
        Genre("[Genre3]"),
    )

    companion object {{
        private const val BASE_URL_PREF_KEY = "baseUrl_pref"
        private const val BASE_URL_PREF_TITLE = "[BaseUrlSettingTitle]"
        private const val BASE_URL_PREF_DEFAULT = "[DefaultBaseUrl]"

        private const val CLEAN_CHAPTER_NAME_KEY = "cleanChapterName_pref"
        private const val CLEAN_CHAPTER_NAME_TITLE = "[CleanNameSettingTitle]"
        private const val CLEAN_CHAPTER_NAME_SUMMARY = "[CleanNameSettingSummary]"
    }}
}}
"""

def main():
    root = Path(LOWER_NAME)
    src_dir = root / "src" / "eu" / "kanade" / "tachiyomi" / "extension" / LANGUAGE_CODE / LOWER_NAME
    res_dir = root / "res"
    
    print(f"Generating extension: {EXTENSION_NAME}")
    src_dir.mkdir(parents=True, exist_ok=True)
    
    # 1. Create build.gradle
    with open(root / "build.gradle", "w", encoding="utf-8", newline='\n') as f:
        f.write(BUILD_GRADLE_TEMPLATE.format(ext_name=EXTENSION_NAME))
    
    # 2. Create Kotlin file
    kt_file = src_dir / f"{EXTENSION_NAME}.kt"
    with open(kt_file, "w", encoding="utf-8", newline='\n') as f:
        f.write(KOTLIN_TEMPLATE.format(
            ext_name=EXTENSION_NAME, 
            lang=LANGUAGE_CODE, 
            lower_name=LOWER_NAME
        ))

    icon_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192
    }

    # ==========================================
    # --- AUTO DETECT IMAGE VS SVG ---
    # ==========================================
    clean_input = ICON_INPUT.strip()
    
    if clean_input.startswith("http://") or clean_input.startswith("https://"):
        print(f"Detected URL. Downloading image...")
        _, Image = install_requirements(need_pillow=True)
        
        try:
            req = urllib.request.Request(clean_input, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req) as response:
                img_data = response.read()
            
            base_image = Image.open(BytesIO(img_data)).convert("RGBA")
            
            for folder_name, size in icon_sizes.items():
                folder_path = res_dir / folder_name
                folder_path.mkdir(parents=True, exist_ok=True)
                
                print(f"Generating image icon for {folder_name} ({size}x{size})...")
                resized_image = base_image.resize((size, size), Image.Resampling.LANCZOS)
                resized_image.save(folder_path / "ic_launcher.png", format="PNG")
                
        except Exception as e:
            print(f"Failed to download or process the image: {e}")
            sys.exit(1)
            
    elif clean_input.startswith("<svg"):
        print(f"Detected SVG data...")
        resvg, _ = install_requirements(need_svg=True)
        
        for folder_name, size in icon_sizes.items():
            folder_path = res_dir / folder_name
            folder_path.mkdir(parents=True, exist_ok=True)
            
            print(f"Generating SVG icon for {folder_name} ({size}x{size})...")
            png_bytes = resvg.svg_to_bytes(svg_string=clean_input, width=size, height=size)
            (folder_path / "ic_launcher.png").write_bytes(png_bytes)
            
    else:
        print("Warning: ICON_INPUT is neither a valid URL nor a valid SVG. No icons generated.")

    print("\nSuccess! Extension code and icons successfully generated.")

if __name__ == "__main__":
    main()