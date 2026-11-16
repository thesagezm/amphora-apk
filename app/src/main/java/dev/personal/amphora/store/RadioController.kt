//
//  RadioController.kt
//  app
//
//  Port of Amphora/Store/RadioController.swift — discovery state for the
//  Radio tab: every radio-browser directory fetch goes through here
//  (AGENTS.md: no networking in Compose views — the views render this
//  controller's state and dispatch playback intents to `AmphoraStore`).
//  Shares one `RadioBrowserClient` with the store, so the mirror
//  rotation and the play-click ping see the same servers.
//
//  Port deltas (documented per AGENTS.md):
//  - iOS `@MainActor @Observable` → Compose `mutableStateOf` fields read
//    in composition, with all work on one main-dispatcher scope (the
//    AmphoraStore pattern).
//  - The `searchText` didSet debounce becomes a setter that cancels and
//    relaunches the 400 ms job — same semantics.
//

package dev.personal.amphora.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.amphora.support.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pandoracore.RadioBrowserClient
import pandoracore.RadioBrowserError
import pandoracore.RadioCountry
import pandoracore.RadioStation
import pandoracore.RadioTag

class RadioController(
    private val client: RadioBrowserClient,
) {

    /**
     * What the current `results` list represents — set by [show].
     * (The port of the nested `Mode` enum; associated values make it a
     * sealed class.)
     */
    sealed class Mode {
        data object Popular : Mode()
        data class Search(val query: String) : Mode()
        data class Country(val code: String, val name: String) : Mode()
        data class Tag(val tag: String) : Mode()

        val title: String
            get() = when (this) {
                is Popular -> "Popular"
                is Search -> "Search"
                is Country -> if (name.isEmpty()) "Stations" else name
                is Tag -> tag
            }
    }

    // MARK: Observable state

    /** The station list currently shown (search results at the tab root,
     *  or the pushed popular/country/tag list). */
    var results: List<RadioStation> by mutableStateOf(emptyList())
        private set

    /** True once any station load completed — distinguishes the initial
     *  spinner from a real "no results". */
    var didLoadResults: Boolean by mutableStateOf(false)
        private set

    var activeMode: Mode? by mutableStateOf(null)
        private set

    /** True while any directory request is in flight. */
    var isLoading: Boolean by mutableStateOf(false)
        private set

    /** User-facing copy for the last failed load; null when healthy. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    /** The list pages 50 stations at a time ("Show More"). `false` once
     *  a page came back short of the page size — the end of the ranking. */
    var canLoadMore: Boolean by mutableStateOf(false)
        private set

    /** True while a "Show More" page is in flight (separate from
     *  `isLoading`, which owns the whole-list first page). */
    var isLoadingMore: Boolean by mutableStateOf(false)
        private set

    /** Copy for a failed "Show More" page — shown as a footer under the
     *  stations already on screen, never replacing them. */
    var moreErrorMessage: String? by mutableStateOf(null)
        private set

    /** The countries / tags catalogs — null until first fetched this
     *  session (same fetch-once caching as the genre browse sheet). */
    var countries: List<RadioCountry>? by mutableStateOf(null)
        private set
    var tags: List<RadioTag>? by mutableStateOf(null)
        private set

    /**
     * Bound to the Radio tab's search field. Non-empty text switches the
     * tab to search results (debounced — never hammer the directory);
     * clearing it returns to the recents / favorites / discovery home.
     */
    private val _searchText = mutableStateOf("")
    var searchText: String
        get() = _searchText.value
        set(value) {
            if (value == _searchText.value) return
            _searchText.value = value
            searchJob?.cancel()
            val query = value.trim()
            if (query.isEmpty()) return
            searchJob = scope.launch {
                delay(400)
                if (!isActive) return@launch
                show(Mode.Search(query))
            }
        }

    // MARK: Internals

    private val scope = CoroutineScope(Dispatchers.Main)
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var moreJob: Job? = null

    // MARK: Station lists

    /** The page size every discovery list loads — "Show More" extends
     *  it (user request 2026-11-12: the country pages showed the top 50
     *  with no way to the rest). */
    companion object {
        const val PAGE_SIZE = 50
    }

    /** Loads the station list for [mode], replacing any in-flight load
     *  (first page only — paging continues through [loadMore]). */
    fun show(mode: Mode) {
        activeMode = mode
        moreJob?.cancel()
        isLoadingMore = false
        moreErrorMessage = null
        canLoadMore = false
        run {
            results = fetch(mode = mode, offset = 0)
            didLoadResults = true
            canLoadMore = results.size >= PAGE_SIZE
        }
    }

    /**
     * Appends the next page for the active mode. A short page (fewer
     * than the page size) marks the end of the ranking and the button
     * goes away. A failure keeps the stations already on screen and
     * surfaces in the footer.
     */
    fun loadMore() {
        val mode = activeMode ?: return
        if (!canLoadMore || isLoading || isLoadingMore) return
        val offset = results.size
        isLoadingMore = true
        moreErrorMessage = null
        moreJob = scope.launch {
            try {
                val page = fetch(mode = mode, offset = offset)
                // A cancelled or superseded page never touches state.
                if (!isActive || activeMode != mode) return@launch
                // The directory's ranking shifts between requests — a
                // station that moved across the page boundary must not
                // appear twice.
                val known = results.map { it.uuid }.toSet()
                results = results + page.filter { it.uuid !in known }
                canLoadMore = page.size >= PAGE_SIZE
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isActive || activeMode != mode) return@launch
                AppLog.info(
                    "radio: directory page failed — $error",
                    AppLog.Category.network,
                )
                moreErrorMessage = userMessage(error)
            }
            if (!isActive) return@launch
            isLoadingMore = false
        }
    }

    /** The one fetch every mode pages through. */
    private suspend fun fetch(mode: Mode, offset: Int): List<RadioStation> = when (mode) {
        is Mode.Popular -> client.topStations(offset = offset)
        is Mode.Search -> client.searchStations(name = mode.query, offset = offset)
        is Mode.Country -> client.stationsByCountryCode(
            countryCode = mode.code,
            offset = offset,
        )
        is Mode.Tag -> client.stationsByTag(tag = mode.tag, offset = offset)
    }

    // MARK: Catalogs

    fun loadCountries() {
        if (countries != null) return
        run { countries = client.countries() }
    }

    /** Clears the cached catalog and refetches (the error state's Retry). */
    fun retryCountries() {
        countries = null
        loadCountries()
    }

    fun loadTags() {
        if (tags != null) return
        run { tags = client.tags() }
    }

    fun retryTags() {
        tags = null
        loadTags()
    }

    // MARK: Internals

    /**
     * Runs one directory request against the shared loading/error
     * state; a newer call cancels the older one.
     */
    private fun run(work: suspend () -> Unit) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        loadJob = scope.launch {
            try {
                work()
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Superseded by a newer call — that one owns the state.
                throw error
            } catch (error: Exception) {
                // The typed error carries no URL material (status code
                // only), so logging the raw case is safe.
                AppLog.info(
                    "radio: directory request failed — $error",
                    AppLog.Category.network,
                )
                errorMessage = userMessage(error)
            }
            if (!isActive) return@launch
            isLoading = false
        }
    }

    /** The user-facing sentence for a directory failure (the port of
     *  `RadioController.userMessage(for:)`). */
    private fun userMessage(error: Exception): String = when (error) {
        is RadioBrowserError.HttpStatus ->
            "The radio directory returned an error (${error.statusCode})."
        RadioBrowserError.BadResponse ->
            "The radio directory sent an unexpected reply."
        RadioBrowserError.NetworkFailure ->
            "Couldn't reach the radio directory. Check your connection."
        else -> "Couldn't load from the radio directory."
    }
}
