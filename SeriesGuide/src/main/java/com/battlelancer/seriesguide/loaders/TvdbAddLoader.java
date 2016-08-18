package com.battlelancer.seriesguide.loaders;

import android.content.Context;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.thetvdbapi.TvdbException;
import com.battlelancer.seriesguide.thetvdbapi.TvdbTools;
import com.battlelancer.seriesguide.traktapi.SgTrakt;
import com.battlelancer.seriesguide.util.ShowTools;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.androidutils.GenericSimpleLoader;
import com.uwetrottmann.trakt5.entities.Show;
import com.uwetrottmann.trakt5.entities.TrendingShow;
import com.uwetrottmann.trakt5.enums.Extended;
import com.uwetrottmann.trakt5.enums.Type;
import com.uwetrottmann.trakt5.services.Search;
import com.uwetrottmann.trakt5.services.Shows;
import dagger.Lazy;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import javax.inject.Inject;
import timber.log.Timber;

public class TvdbAddLoader extends GenericSimpleLoader<TvdbAddLoader.Result> {

    public static class Result {
        public List<SearchResult> results;
        public int emptyTextResId;
        /** Whether the network call completed. Does not mean there are any results. */
        public boolean successful;

        public Result(List<SearchResult> results, int emptyTextResId, boolean successful) {
            this.results = results;
            this.emptyTextResId = emptyTextResId;
            this.successful = successful;
        }
    }

    private final SgApp app;
    private final String query;
    private final String language;
    @Inject Lazy<Shows> traktShows;
    @Inject Lazy<Search> traktSearch;

    /**
     * Loads a list of trending shows from trakt (query is empty) or searches trakt/TheTVDB for
     * shows matching the given query.
     *
     * @param language If not provided, will search for results in all languages.
     */
    public TvdbAddLoader(SgApp app, @Nullable String query, @Nullable String language) {
        super(app);
        this.app = app;
        app.getServicesComponent().inject(this);
        this.query = query;
        this.language = language;
    }

    @Override
    public Result loadInBackground() {
        List<SearchResult> results;

        if (TextUtils.isEmpty(query)) {
            // no query? load a list of trending shows from trakt
            List<TrendingShow> trendingShows = SgTrakt.executeCall(app,
                    traktShows.get().trending(1, 35, Extended.FULLIMAGES),
                    "get trending shows"
            );
            if (trendingShows != null) {
                List<Show> shows = new LinkedList<>();
                for (TrendingShow show : trendingShows) {
                    if (show.show == null || show.show.ids == null || show.show.ids.tvdb == null) {
                        // skip if required values are missing
                        continue;
                    }
                    shows.add(show.show);
                }
                // manually set the language to the current search language
                results = TraktAddLoader.parseTraktShowsToSearchResults(getContext(), shows,
                        language);
                return buildResultSuccess(results, R.string.no_results);
            } else {
                return buildResultFailure(getContext(), R.string.trakt_error_general);
            }
        } else {
            // have a query?
            // search trakt (has better search) when using English
            // use TheTVDB search for all other (or any) languages
            if (DisplaySettings.LANGUAGE_EN.equals(language)) {
                List<com.uwetrottmann.trakt5.entities.SearchResult> searchResults
                        = SgTrakt.executeCall(app,
                        traktSearch.get().textQuery(query, Type.SHOW, null, 1, 30),
                        "search shows"
                );
                if (searchResults != null) {
                    List<Show> shows = new LinkedList<>();
                    for (com.uwetrottmann.trakt5.entities.SearchResult result : searchResults) {
                        if (result.show == null || result.show.ids == null
                                || result.show.ids.tvdb == null) {
                            // skip, TVDB id required
                            continue;
                        }
                        shows.add(result.show);
                    }

                    // manually set the language to English
                    results = TraktAddLoader.parseTraktShowsToSearchResults(getContext(),
                            shows, DisplaySettings.LANGUAGE_EN);
                    return buildResultSuccess(results, R.string.no_results);
                }
            } else {
                try {
                    if (TextUtils.isEmpty(language)) {
                        // use the v1 API to do an any language search not supported by v2
                        results = TvdbTools.searchShow(getContext(), query, null);
                    } else {
                        results = TvdbTools.getInstance(app).searchSeries(query, language);
                    }
                    markLocalShows(getContext(), results);
                    return buildResultSuccess(results, R.string.no_results);
                } catch (TvdbException e) {
                    Timber.e(e, "Searching show failed");
                }
            }
            return buildResultFailure(getContext(), R.string.search_error);
        }
    }

    private static void markLocalShows(Context context, @Nullable List<SearchResult> results) {
        HashSet<Integer> localShows = ShowTools.getShowTvdbIdsAsSet(context);
        if (localShows == null || results == null) {
            return;
        }

        for (SearchResult result : results) {
            result.overview = "(" + result.language + ") " + result.overview;

            if (localShows.contains(result.tvdbid)) {
                // is already in local database
                result.isAdded = true;
            }
        }
    }

    private static Result buildResultSuccess(List<SearchResult> results, int emptyTextResId) {
        if (results == null) {
            results = new LinkedList<>();
        }
        return new Result(results, emptyTextResId, true);
    }

    private static Result buildResultFailure(Context context, int emptyTextResId) {
        // only check for network here to allow hitting the response cache
        if (!AndroidUtils.isNetworkConnected(context)) {
            emptyTextResId = R.string.offline;
        }
        return new Result(new LinkedList<SearchResult>(), emptyTextResId, false);
    }
}
