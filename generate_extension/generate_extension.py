import os
import sys
import subprocess
import urllib.request
import base64
from io import BytesIO
from pathlib import Path

# ==========================================
# --- CONFIGURATION ---
# ==========================================
INPUT_NAME = "TeamX" 
LANGUAGE_CODE = "ar"

# Paste ONE of the following here:
# 1. Image URL (http:// or https://)
# 2. Raw SVG code (<svg...)
# 3. Data URI (data:image/png;base64,...)
# 4. Plain Base64 text (iVBORw0KGgo...)

# ICON_INPUT = """ """
ICON_INPUT = """<svg viewBox='0 0 100 100' fill='none' xmlns='http://www.w3.org/2000/svg'><defs><linearGradient id='bgGrad' x1='0' y1='0' x2='0' y2='1'><stop offset='0%' stop-color='#160F29'/><stop offset='100%' stop-color='#050716'/></linearGradient><linearGradient id='borderGrad' x1='0' y1='0' x2='1' y2='1'><stop offset='0%' stop-color='#00F2FE'/><stop offset='50%' stop-color='#FF007F'/><stop offset='100%' stop-color='#6366F1'/></linearGradient><linearGradient id='g1' x1='0' y1='0' x2='1' y2='1'><stop offset='0%' stop-color='#00F2FE'/><stop offset='60%' stop-color='#4F46E5'/><stop offset='100%' stop-color='#1E1B4B'/></linearGradient><linearGradient id='g2' x1='0' y1='0' x2='1' y2='1'><stop offset='0%' stop-color='#FF007F'/><stop offset='50%' stop-color='#FF5E3A'/><stop offset='100%' stop-color='#FFD000'/></linearGradient><filter id='sh' x='-20%' y='-20%' width='140%' height='140%'><feDropShadow dx='10' dy='16' stdDeviation='12' flood-color='#000000' flood-opacity='0.75'/></filter><filter id='appShadow' x='-10%' y='-10%' width='120%' height='120%'><feDropShadow dx='0' dy='5' stdDeviation='5' flood-color='#000000' flood-opacity='0.6'/></filter></defs><g filter='url(#appShadow)'><rect x='6' y='6' width='88' height='88' rx='22' fill='url(#bgGrad)'/><rect x='6.5' y='6.5' width='87' height='87' rx='21.5' stroke='url(#borderGrad)' stroke-width='1.5' stroke-opacity='0.8' fill='none'/></g><g transform='translate(50, 50) scale(0.65) translate(-50, -50)' filter='url(#sh)'><g transform='translate(0.0000, 0.0000) scale(0.097656)'><g transform='translate(12, 16)'><path fill='#090714' d=' M 705.52 194.52 C 706.67 193.34 708.41 193.55 709.93 193.46 C 812.66 193.56 915.39 193.42 1018.12 193.53 C 1014.67 197.62 1010.35 200.83 1006.48 204.48 C 903.55 297.22 800.60 389.95 697.66 482.68 C 687.18 492.24 676.48 501.56 666.11 511.23 C 668.46 514.08 671.43 516.34 674.17 518.81 C 786.73 619.27 899.30 719.71 1011.86 820.17 C 1015.73 823.73 1019.93 826.98 1023.47 830.90 C 920.63 831.16 817.78 830.88 714.94 831.04 C 712.98 831.02 710.81 831.13 709.41 829.53 C 646.80 772.48 584.17 715.45 521.55 658.42 C 518.45 655.75 515.74 652.57 512.27 650.38 C 499.64 660.67 487.36 671.40 475.01 682.03 C 460.48 694.79 445.80 707.43 432.28 721.27 C 421.68 732.85 411.47 744.99 403.78 758.73 C 399.94 766.04 396.17 774.36 398.02 782.80 C 400.22 791.64 403.56 800.47 409.55 807.48 C 414.00 812.79 419.67 816.88 425.60 820.39 C 429.97 823.13 434.80 825.31 438.44 829.06 C 431.77 827.49 425.59 824.39 419.33 821.67 C 391.56 808.72 365.38 791.00 346.11 766.90 C 333.06 750.61 323.85 731.08 320.74 710.38 C 318.51 696.75 319.43 682.47 324.53 669.57 C 330.20 654.92 340.59 642.54 352.63 632.65 C 367.51 620.46 384.84 611.62 402.68 604.64 C 421.00 597.59 440.06 592.49 459.42 589.22 C 472.32 587.12 485.45 585.58 498.54 586.64 C 517.07 588.09 535.28 592.70 552.60 599.37 C 563.81 603.84 574.92 609.04 584.42 616.60 C 587.86 619.26 590.44 622.78 593.04 626.23 C 593.27 611.60 590.39 596.86 584.58 583.43 C 579.79 572.40 572.01 562.87 562.86 555.16 C 558.51 551.53 553.62 548.48 548.24 546.65 C 543.16 544.82 537.70 544.34 532.69 542.30 C 527.96 540.41 524.78 536.21 521.59 532.45 C 511.20 519.51 501.45 506.04 490.16 493.85 C 480.18 483.04 469.12 472.89 455.91 466.16 C 437.98 457.02 419.23 449.65 400.43 442.53 C 384.79 436.74 369.10 431.07 353.21 426.01 C 358.17 430.95 363.35 435.71 367.88 441.06 C 371.35 445.18 374.98 449.41 376.60 454.65 C 370.02 453.37 363.85 450.70 357.58 448.45 C 340.00 441.67 322.52 433.95 307.31 422.67 C 301.39 418.36 296.38 412.95 292.16 406.99 C 296.21 419.90 300.44 432.77 305.76 445.22 C 291.41 433.33 278.38 419.82 266.75 405.26 C 254.36 389.29 242.92 371.28 240.56 350.77 C 237.13 322.35 243.72 293.45 255.79 267.75 C 260.69 257.46 266.53 247.57 273.72 238.71 C 284.87 226.17 295.75 213.40 306.43 200.46 C 308.36 198.26 310.18 195.85 312.73 194.34 C 375.77 250.25 438.47 306.58 501.39 362.63 C 504.92 365.44 507.79 369.27 511.85 371.29 C 576.57 312.56 640.91 253.37 705.52 194.52 Z' /><path fill='#090714' d=' M 265.30 194.51 C 266.00 194.43 267.39 194.26 268.09 194.18 C 267.07 195.88 265.56 197.19 263.84 198.14 C 264.34 196.94 264.81 195.72 265.30 194.51 Z' /><path fill='#090714' d=' M 251.99 226.99 C 261.71 217.69 271.40 208.25 282.19 200.18 C 272.14 213.10 260.78 225.10 252.13 239.07 C 237.53 264.00 225.06 290.83 220.77 319.62 C 219.44 328.68 219.08 337.90 219.98 347.02 C 221.81 366.63 225.20 386.27 232.17 404.77 C 238.11 420.48 246.85 435.30 258.84 447.16 C 269.48 458.02 282.76 465.75 295.92 473.15 C 319.69 486.27 344.85 496.65 370.09 506.57 C 344.41 507.75 318.65 508.78 292.96 507.07 C 285.86 506.70 278.86 505.39 271.90 503.97 C 289.72 519.57 309.47 532.87 330.01 544.60 C 310.61 542.48 291.43 537.91 273.28 530.70 C 257.70 524.44 242.76 516.09 230.35 504.67 C 215.10 490.79 205.15 472.12 198.03 453.01 C 189.89 430.61 185.95 406.79 185.47 382.99 C 185.33 369.23 185.83 355.22 189.98 341.99 C 198.90 311.25 209.40 280.30 227.75 253.76 C 234.56 243.76 243.43 235.45 251.99 226.99 Z' /><path fill='#090714' d=' M 422.89 511.35 C 432.23 511.59 441.66 512.00 450.75 514.29 C 457.92 516.16 463.00 521.90 468.56 526.40 C 474.68 531.55 482.41 534.11 489.03 538.48 C 484.02 538.54 478.99 538.09 474.00 538.57 C 470.96 539.46 468.26 541.37 465.07 541.80 C 459.33 542.63 452.87 540.26 449.93 535.07 C 447.88 531.64 449.86 527.03 447.01 523.97 C 441.46 516.10 431.24 514.72 422.89 511.35 Z' /><path fill='#3B001A' d=' M 0.85 193.81 C 2.23 193.68 3.60 193.60 4.98 193.58 C 61.99 193.60 119.00 193.97 176.01 194.06 C 205.77 194.36 235.55 194.08 265.30 194.51 C 264.81 195.72 264.34 196.94 263.84 198.14 C 263.24 199.08 262.43 199.86 261.64 200.65 C 248.53 212.52 236.12 225.19 224.75 238.75 C 215.21 250.24 206.40 262.47 200.04 276.03 C 191.03 295.01 185.09 315.30 180.48 335.75 C 179.26 340.98 178.68 346.37 177.09 351.52 C 173.40 349.08 170.29 345.91 167.00 342.98 C 114.77 296.22 62.53 249.47 10.30 202.71 C 7.07 199.83 3.67 197.11 0.85 193.81 Z' /></g><g><path fill='url(#g1)' stroke='#FFFFFF' stroke-width='8' stroke-opacity='0.25' d=' M 705.52 194.52 C 706.67 193.34 708.41 193.55 709.93 193.46 C 812.66 193.56 915.39 193.42 1018.12 193.53 C 1014.67 197.62 1010.35 200.83 1006.48 204.48 C 903.55 297.22 800.60 389.95 697.66 482.68 C 687.18 492.24 676.48 501.56 666.11 511.23 C 668.46 514.08 671.43 516.34 674.17 518.81 C 786.73 619.27 899.30 719.71 1011.86 820.17 C 1015.73 823.73 1019.93 826.98 1023.47 830.90 C 920.63 831.16 817.78 830.88 714.94 831.04 C 712.98 831.02 710.81 831.13 709.41 829.53 C 646.80 772.48 584.17 715.45 521.55 658.42 C 518.45 655.75 515.74 652.57 512.27 650.38 C 499.64 660.67 487.36 671.40 475.01 682.03 C 460.48 694.79 445.80 707.43 432.28 721.27 C 421.68 732.85 411.47 744.99 403.78 758.73 C 399.94 766.04 396.17 774.36 398.02 782.80 C 400.22 791.64 403.56 800.47 409.55 807.48 C 414.00 812.79 419.67 816.88 425.60 820.39 C 429.97 823.13 434.80 825.31 438.44 829.06 C 431.77 827.49 425.59 824.39 419.33 821.67 C 391.56 808.72 365.38 791.00 346.11 766.90 C 333.06 750.61 323.85 731.08 320.74 710.38 C 318.51 696.75 319.43 682.47 324.53 669.57 C 330.20 654.92 340.59 642.54 352.63 632.65 C 367.51 620.46 384.84 611.62 402.68 604.64 C 421.00 597.59 440.06 592.49 459.42 589.22 C 472.32 587.12 485.45 585.58 498.54 586.64 C 517.07 588.09 535.28 592.70 552.60 599.37 C 563.81 603.84 574.92 609.04 584.42 616.60 C 587.86 619.26 590.44 622.78 593.04 626.23 C 593.27 611.60 590.39 596.86 584.58 583.43 C 579.79 572.40 572.01 562.87 562.86 555.16 C 558.51 551.53 553.62 548.48 548.24 546.65 C 543.16 544.82 537.70 544.34 532.69 542.30 C 527.96 540.41 524.78 536.21 521.59 532.45 C 511.20 519.51 501.45 506.04 490.16 493.85 C 480.18 483.04 469.12 472.89 455.91 466.16 C 437.98 457.02 419.23 449.65 400.43 442.53 C 384.79 436.74 369.10 431.07 353.21 426.01 C 358.17 430.95 363.35 435.71 367.88 441.06 C 371.35 445.18 374.98 449.41 376.60 454.65 C 370.02 453.37 363.85 450.70 357.58 448.45 C 340.00 441.67 322.52 433.95 307.31 422.67 C 301.39 418.36 296.38 412.95 292.16 406.99 C 296.21 419.90 300.44 432.77 305.76 445.22 C 291.41 433.33 278.38 419.82 266.75 405.26 C 254.36 389.29 242.92 371.28 240.56 350.77 C 237.13 322.35 243.72 293.45 255.79 267.75 C 260.69 257.46 266.53 247.57 273.72 238.71 C 284.87 226.17 295.75 213.40 306.43 200.46 C 308.36 198.26 310.18 195.85 312.73 194.34 C 375.77 250.25 438.47 306.58 501.39 362.63 C 504.92 365.44 507.79 369.27 511.85 371.29 C 576.57 312.56 640.91 253.37 705.52 194.52 Z' /><path fill='#FFFFFF' opacity='0.95' d=' M 265.30 194.51 C 266.00 194.43 267.39 194.26 268.09 194.18 C 267.07 195.88 265.56 197.19 263.84 198.14 C 264.34 196.94 264.81 195.72 265.30 194.51 Z' /><path fill='url(#g1)' stroke='#FFFFFF' stroke-width='8' stroke-opacity='0.25' d=' M 251.99 226.99 C 261.71 217.69 271.40 208.25 282.19 200.18 C 272.14 213.10 260.78 225.10 252.13 239.07 C 237.53 264.00 225.06 290.83 220.77 319.62 C 219.44 328.68 219.08 337.90 219.98 347.02 C 221.81 366.63 225.20 386.27 232.17 404.77 C 238.11 420.48 246.85 435.30 258.84 447.16 C 269.48 458.02 282.76 465.75 295.92 473.15 C 319.69 486.27 344.85 496.65 370.09 506.57 C 344.41 507.75 318.65 508.78 292.96 507.07 C 285.86 506.70 278.86 505.39 271.90 503.97 C 289.72 519.57 309.47 532.87 330.01 544.60 C 310.61 542.48 291.43 537.91 273.28 530.70 C 257.70 524.44 242.76 516.09 230.35 504.67 C 215.10 490.79 205.15 472.12 198.03 453.01 C 189.89 430.61 185.95 406.79 185.47 382.99 C 185.33 369.23 185.83 355.22 189.98 341.99 C 198.90 311.25 209.40 280.30 227.75 253.76 C 234.56 243.76 243.43 235.45 251.99 226.99 Z' /><path fill='#FFFFFF' opacity='0.85' d=' M 422.89 511.35 C 432.23 511.59 441.66 512.00 450.75 514.29 C 457.92 516.16 463.00 521.90 468.56 526.40 C 474.68 531.55 482.41 534.11 489.03 538.48 C 484.02 538.54 478.99 538.09 474.00 538.57 C 470.96 539.46 468.26 541.37 465.07 541.80 C 459.33 542.63 452.87 540.26 449.93 535.07 C 447.88 531.64 449.86 527.03 447.01 523.97 C 441.46 516.10 431.24 514.72 422.89 511.35 Z' /><path fill='url(#g2)' stroke='#FFFFFF' stroke-width='8' stroke-opacity='0.3' d=' M 0.85 193.81 C 2.23 193.68 3.60 193.60 4.98 193.58 C 61.99 193.60 119.00 193.97 176.01 194.06 C 205.77 194.36 235.55 194.08 265.30 194.51 C 264.81 195.72 264.34 196.94 263.84 198.14 C 263.24 199.08 262.43 199.86 261.64 200.65 C 248.53 212.52 236.12 225.19 224.75 238.75 C 215.21 250.24 206.40 262.47 200.04 276.03 C 191.03 295.01 185.09 315.30 180.48 335.75 C 179.26 340.98 178.68 346.37 177.09 351.52 C 173.40 349.08 170.29 345.91 167.00 342.98 C 114.77 296.22 62.53 249.47 10.30 202.71 C 7.07 199.83 3.67 197.11 0.85 193.81 Z' /></g></g></g></svg>"""

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
    extVersionCode = 1
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

        throw Exception("No pages were found for this chapter")
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
    # --- AUTO DETECT ICON INPUT FORMAT ---
    # ==========================================
    clean_input = ICON_INPUT.strip()
    img_data = None
    is_svg = False
    
    if not clean_input:
        print("Warning: ICON_INPUT is empty. No icons generated.")
        return

    try:
        if clean_input.startswith("<svg"):
            print("Detected SVG data...")
            is_svg = True
            
        elif clean_input.startswith("http://") or clean_input.startswith("https://"):
            print("Detected Image URL. Downloading...")
            req = urllib.request.Request(clean_input, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req) as response:
                img_data = response.read()
                
        elif clean_input.startswith("data:image/"):
            print("Detected Data URI Base64. Decoding...")
            if ";base64," in clean_input:
                base64_str = clean_input.split(";base64,", 1)[1]
                img_data = base64.b64decode(base64_str)
            else:
                print("Error: Invalid Data URI format. Missing ';base64,'")
                sys.exit(1)
                
        else:
            print("Detected Plain Base64 string. Decoding...")
            # Fix missing padding commonly found in plain base64 strings
            padded_input = clean_input + '=' * (-len(clean_input) % 4)
            img_data = base64.b64decode(padded_input, validate=True)
            
    except Exception as e:
        print(f"Error parsing ICON_INPUT: {e}")
        sys.exit(1)

    # ==========================================
    # --- PROCESS AND SAVE ICONS ---
    # ==========================================
    if is_svg:
        resvg, _ = install_requirements(need_svg=True)
        for folder_name, size in icon_sizes.items():
            folder_path = res_dir / folder_name
            folder_path.mkdir(parents=True, exist_ok=True)
            
            print(f"Generating SVG icon for {folder_name} ({size}x{size})...")
            png_bytes = resvg.svg_to_bytes(svg_string=clean_input, width=size, height=size)
            (folder_path / "ic_launcher.png").write_bytes(png_bytes)
            
    elif img_data:
        _, Image = install_requirements(need_pillow=True)
        try:
            base_image = Image.open(BytesIO(img_data)).convert("RGBA")
            
            for folder_name, size in icon_sizes.items():
                folder_path = res_dir / folder_name
                folder_path.mkdir(parents=True, exist_ok=True)
                
                print(f"Generating image icon for {folder_name} ({size}x{size})...")
                resized_image = base_image.resize((size, size), Image.Resampling.LANCZOS)
                resized_image.save(folder_path / "ic_launcher.png", format="PNG")
                
        except Exception as e:
            print(f"Failed to process the downloaded/decoded image: {e}")
            sys.exit(1)

    print("\nSuccess! Extension code and icons successfully generated.")

if __name__ == "__main__":
    main()
