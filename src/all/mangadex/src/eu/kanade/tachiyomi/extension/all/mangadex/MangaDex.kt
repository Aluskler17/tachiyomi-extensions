package eu.kanade.tachiyomi.extension.all.mangadex

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterListDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaListDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

abstract class MangaDex(override val lang: String, val dexLang: String) :
    ConfigurableSource,
    HttpSource() {
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"

    // after mvp comes out make current popular becomes latest (mvp doesnt have a browse page)
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val helper = MangaDexHelper()

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Tachiyomi " + System.getProperty("http.agent"))

    override val client = network.client.newBuilder()
        .addNetworkInterceptor(mdRateLimitInterceptor)
        .addInterceptor(coverInterceptor)
        .addInterceptor(MdAtHomeReportInterceptor(network.client, headersBuilder().build()))
        .build()

    // POPULAR Manga Section

    override fun popularMangaRequest(page: Int): Request {
        val url = MDConstants.apiMangaUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("order[updatedAt]", "desc")
            addQueryParameter("limit", MDConstants.mangaLimit.toString())
            addQueryParameter("offset", helper.getMangaListOffset(page))
            if (preferences.getBoolean(MDConstants.getContentRatingSafePrefKey(dexLang), false)) {
                addQueryParameter("contentRating[]", "safe")
            }
            if (preferences.getBoolean(
                    MDConstants.getContentRatingEroticaPrefKey(dexLang),
                    false
                )
            ) {
                addQueryParameter("contentRating[]", "suggestive")
            }
            if (preferences.getBoolean(
                    MDConstants.getContentRatingSuggestivePrefKey(dexLang),
                    false
                )
            ) {
                addQueryParameter("contentRating[]", "erotica")
            }
            if (preferences.getBoolean(
                    MDConstants.getContentRatingPornographicPrefKey(dexLang),
                    false
                )
            ) {
                addQueryParameter("contentRating[]", "pornographic")
            }
        }.build().toUrl().toString()
        return GET(
            url = url,
            headers = headers,
            cache = CacheControl.FORCE_NETWORK
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }

        if (response.code == 204) {
            return MangasPage(emptyList(), false)
        }
        val mangaListDto = helper.json.decodeFromString<MangaListDto>(response.body!!.string())
        val hasMoreResults = mangaListDto.limit + mangaListDto.offset < mangaListDto.total

        val idsAndCoverIds = mangaListDto.results.mapNotNull { mangaDto ->
            val mangaId = mangaDto.data.id
            val coverId = mangaDto.relationships.firstOrNull { relationshipDto ->
                relationshipDto.type.equals("cover_art", true)
            }?.id
            if (coverId == null) {
                null
            } else {
                Pair(mangaId, coverId)
            }
        }.toMap()

        val results = runCatching {
            helper.getBatchCoversUrl(idsAndCoverIds, client)
        }.getOrNull()!!

        val mangaList = mangaListDto.results.map {
            helper.createBasicManga(it).apply {
                thumbnail_url = results[url.substringAfter("/manga/")]
            }
        }

        return MangasPage(mangaList, hasMoreResults)
    }

    // LATEST section  API can't sort by date yet so not implemented
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    // SEARCH section

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith(MDConstants.prefixIdSearch)) {
            val url = MDConstants.apiMangaUrl.toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("ids[]", query.removePrefix(MDConstants.prefixIdSearch))
            return GET(url.toString(), headers, CacheControl.FORCE_NETWORK)
        }

        val tempUrl = MDConstants.apiMangaUrl.toHttpUrl().newBuilder()

        tempUrl.apply {
            addQueryParameter("limit", MDConstants.mangaLimit.toString())
            addQueryParameter("offset", (helper.getMangaListOffset(page)))
            val actualQuery = query.replace(MDConstants.whitespaceRegex, " ")
            if (actualQuery.isNotBlank()) {
                addQueryParameter("title", actualQuery)
            }
        }

        val finalUrl = helper.mdFilters.addFiltersToUrl(tempUrl, filters)

        return GET(finalUrl, headers, CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Manga Details section

    // Shenanigans to allow "open in webview" to show a webpage instead of JSON
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(apiMangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        //remove once redirect for /manga is fixed
        return GET("${baseUrl}${manga.url.replace("manga", "title")}", headers)
    }

    /**
     * get manga details url throws exception if the url is the old format so people migrate
     */
    fun apiMangaDetailsRequest(manga: SManga): Request {
        if (!helper.containsUuid(manga.url.trim())) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        return GET("${MDConstants.apiUrl}${manga.url}", headers, CacheControl.FORCE_NETWORK)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = helper.json.decodeFromString<MangaDto>(response.body!!.string())
        return helper.createManga(manga, client, lang.substringBefore("-"))
    }

    // Chapter list section
    /**
     * get chapter list if manga url is old format throws exception
     */
    override fun chapterListRequest(manga: SManga): Request {
        if (!helper.containsUuid(manga.url)) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        return actualChapterListRequest(helper.getUUIDFromUrl(manga.url), 0)
    }

    /**
     * Required because api is paged
     */
    private fun actualChapterListRequest(mangaId: String, offset: Int) =
        GET(
            url = helper.getChapterEndpoint(mangaId, offset, dexLang),
            headers = headers,
            cache = CacheControl.FORCE_NETWORK
        )

    override fun chapterListParse(response: Response): List<SChapter> {

        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }
        if (response.code == 204) {
            return emptyList()
        }
        try {
            val chapterListResponse = helper.json.decodeFromString<ChapterListDto>(response.body!!.string())

            val chapterListResults = chapterListResponse.results.toMutableList()

            val mangaId =
                response.request.url.toString().substringBefore("/feed")
                    .substringAfter("${MDConstants.apiMangaUrl}/")

            val limit = chapterListResponse.limit

            var offset = chapterListResponse.offset

            var hasMoreResults = (limit + offset) < chapterListResponse.total

            // max results that can be returned is 500 so need to make more api calls if limit+offset > total chapters
            while (hasMoreResults) {
                offset += limit
                val newResponse =
                    client.newCall(actualChapterListRequest(mangaId, offset)).execute()
                val newChapterList = helper.json.decodeFromString<ChapterListDto>(newResponse.body!!.string())
                chapterListResults.addAll(newChapterList.results)
                hasMoreResults = (limit + offset) < newChapterList.total
            }

            val groupMap = helper.createGroupMap(chapterListResults.toList(), client)

            val now = Date().time

            return chapterListResults.map { helper.createChapter(it, groupMap) }
                .filter { it.date_upload <= now && "MangaPlus" != it.scanlator }
        } catch (e: Exception) {
            Log.e("MangaDex", "error parsing chapter list", e)
            throw(e)
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (!helper.containsUuid(chapter.url)) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        return GET(MDConstants.apiUrl + chapter.url, headers, CacheControl.FORCE_NETWORK)
    }

    override fun pageListParse(response: Response): List<Page> {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }
        if (response.code == 204) {
            return emptyList()
        }
        val chapterDto = helper.json.decodeFromString<ChapterDto>(response.body!!.string()).data
        val usingStandardHTTPS =
            preferences.getBoolean(MDConstants.getStandardHttpsPreferenceKey(dexLang), false)

        val atHomeRequestUrl = if (usingStandardHTTPS) {
            "${MDConstants.apiUrl}/at-home/server/${chapterDto.id}?forcePort443=true"
        } else {
            "${MDConstants.apiUrl}/at-home/server/${chapterDto.id}"
        }

        val host =
            helper.getMdAtHomeUrl(atHomeRequestUrl, client, headers, CacheControl.FORCE_NETWORK)

        val usingDataSaver =
            preferences.getBoolean(MDConstants.getDataSaverPreferenceKey(dexLang), false)

        // have to add the time, and url to the page because pages timeout within 30mins now
        val now = Date().time

        val hash = chapterDto.attributes.hash
        val pageSuffix = if (usingDataSaver) {
            chapterDto.attributes.dataSaver.map { "/data-saver/$hash/$it" }
        } else {
            chapterDto.attributes.data.map { "/data/$hash/$it" }
        }

        return pageSuffix.mapIndexed { index, imgUrl ->
            val mdAtHomeMetadataUrl = "$host,$atHomeRequestUrl,$now"
            Page(index, mdAtHomeMetadataUrl, imgUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        return helper.getValidImageUrlForPage(page, headers, client)
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val dataSaverPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getDataSaverPreferenceKey(dexLang)
            title = "Data saver"
            summary = "Enables smaller, more compressed images"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getDataSaverPreferenceKey(dexLang), checkValue)
                    .commit()
            }
        }

        val standardHttpsPortPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getStandardHttpsPreferenceKey(dexLang)
            title = "Use HTTPS port 443 only"
            summary =
                "Enable to only request image servers that use port 443. This allows users with stricter firewall restrictions to access MangaDex images"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getStandardHttpsPreferenceKey(dexLang), checkValue)
                    .commit()
            }
        }

        val contentRatingSafePref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getContentRatingSafePrefKey(dexLang)
            title = "Safe"
            summary = "If enabled, shows content with the rating of safe (manga without any sexual themes)"
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getContentRatingSafePrefKey(dexLang), checkValue)
                    .commit()
            }
        }

        val contentRatingSuggestivePref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getContentRatingSuggestivePrefKey(dexLang)
            title = "Suggestive"
            summary = "If enabled, shows content with the rating of suggestive (manga usually tagged as ecchi)"
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getContentRatingSuggestivePrefKey(dexLang), checkValue)
                    .commit()
            }
        }

        val contentRatingEroticaPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getContentRatingEroticaPrefKey(dexLang)
            title = "Erotica"
            summary = "If enabled, shows content with the rating of erotica (manga usually tagged as smut)"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getContentRatingEroticaPrefKey(dexLang), checkValue)
                    .commit()
            }
        }

        val contentRatingPornographicPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getContentRatingPornographicPrefKey(dexLang)
            title = "Pornographic"
            summary = "If enabled, shows content with the rating of pornographic (manga usually tagged as hentai)"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean(
                    MDConstants.getContentRatingPornographicPrefKey(dexLang),
                    checkValue
                )
                    .commit()
            }
        }

        screen.addPreference(dataSaverPref)
        screen.addPreference(standardHttpsPortPref)
        screen.addPreference(contentRatingSafePref)
        screen.addPreference(contentRatingSuggestivePref)
        screen.addPreference(contentRatingEroticaPref)
        screen.addPreference(contentRatingPornographicPref)
    }

    override fun getFilterList(): FilterList =
        helper.mdFilters.getMDFilterList(preferences, dexLang)
}
