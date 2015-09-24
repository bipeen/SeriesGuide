/*
 * Copyright 2014 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.battlelancer.seriesguide.dataliberation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;
import android.widget.Toast;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.dataliberation.model.Episode;
import com.battlelancer.seriesguide.dataliberation.model.List;
import com.battlelancer.seriesguide.dataliberation.model.ListItem;
import com.battlelancer.seriesguide.dataliberation.model.Movie;
import com.battlelancer.seriesguide.dataliberation.model.Season;
import com.battlelancer.seriesguide.dataliberation.model.Show;
import com.battlelancer.seriesguide.interfaces.OnTaskFinishedListener;
import com.battlelancer.seriesguide.interfaces.OnTaskProgressListener;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.ListItemTypes;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.ListItems;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Seasons;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.settings.AdvancedSettings;
import com.battlelancer.seriesguide.settings.BackupSettings;
import com.battlelancer.seriesguide.util.EpisodeTools;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonWriter;
import com.uwetrottmann.androidutils.AndroidUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import timber.log.Timber;

import static com.battlelancer.seriesguide.provider.SeriesGuideContract.Movies;

/**
 * Export the show database to a human-readable JSON file on external storage. By default meta-data
 * like descriptions, ratings, actors, etc. will not be included.
 */
public class JsonExportTask extends AsyncTask<Void, Integer, Integer> {

    public static final String EXPORT_FOLDER = "SeriesGuide";
    public static final String EXPORT_FOLDER_AUTO = "SeriesGuide" + File.separator + "AutoBackup";
    public static final String EXPORT_JSON_FILE_SHOWS = "sg-shows-export.json";
    public static final String EXPORT_JSON_FILE_LISTS = "sg-lists-export.json";
    public static final String EXPORT_JSON_FILE_MOVIES = "sg-movies-export.json";

    private static final int EXPORT_SHOWS = 1;
    private static final int EXPORT_LISTS = 2;
    private static final int EXPORT_MOVIES = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            EXPORT_SHOWS,
            EXPORT_LISTS,
            EXPORT_MOVIES
    })
    private @interface ExportType {
    }

    private static final int SUCCESS = 1;
    private static final int ERROR_FILE_ACCESS = 0;
    private static final int ERROR = -1;

    /**
     * Show status used when exporting data. Compare with {@link com.battlelancer.seriesguide.util.ShowTools.Status}.
     */
    public interface ShowStatusExport {
        String CONTINUING = "continuing";
        String ENDED = "ended";
        String UNKNOWN = "unknown";
    }

    public interface ListItemTypesExport {
        String SHOW = "show";
        String SEASON = "season";
        String EPISODE = "episode";
    }

    private Context context;
    private OnTaskProgressListener progressListener;
    private OnTaskFinishedListener finishedListener;
    private boolean isFullDump;
    private boolean isAutoBackupMode;

    public static File getExportPath(boolean isAutoBackupMode) {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                isAutoBackupMode ? EXPORT_FOLDER_AUTO : EXPORT_FOLDER);
    }

    /**
     * Same as {@link JsonExportTask} but allows to set parameters.
     *
     * @param isFullDump Whether to also export meta-data like descriptions, ratings, actors, etc.
     * Increases file size about 2-4 times.
     * @param isSilentMode Whether to show result toasts.
     */
    public JsonExportTask(Context context, OnTaskProgressListener progressListener,
            OnTaskFinishedListener listener, boolean isFullDump,
            boolean isSilentMode) {
        this.context = context.getApplicationContext();
        this.progressListener = progressListener;
        finishedListener = listener;
        this.isFullDump = isFullDump;
        isAutoBackupMode = isSilentMode;
    }

    @SuppressLint("CommitPrefEdits")
    @Override
    protected Integer doInBackground(Void... params) {
        // use Storage Access Framework on KitKat and up
        // but for now still use fixed path for auto backup
        File exportPath = null;
        if (isAutoBackupMode || !AndroidUtils.isKitKatOrHigher()) {
            // Ensure external storage is available
            if (!AndroidUtils.isExtStorageAvailable()) {
                return ERROR_FILE_ACCESS;
            }

            // Ensure the export directory exists
            exportPath = getExportPath(isAutoBackupMode);
            if (!exportPath.mkdirs() && !exportPath.isDirectory()) {
                return ERROR_FILE_ACCESS;
            }
        }

        // last chance to abort
        if (isCancelled()) {
            return ERROR;
        }

        int result = exportData(exportPath, EXPORT_SHOWS);
        if (result != SUCCESS) {
            return result;
        }
        if (isCancelled()) {
            return ERROR;
        }

        result = exportData(exportPath, EXPORT_LISTS);
        if (result != SUCCESS) {
            return result;
        }
        if (isCancelled()) {
            return ERROR;
        }

        result = exportData(exportPath, EXPORT_MOVIES);
        if (result != SUCCESS) {
            return result;
        }
        // no need to return early here if canceled, we are almost done anyhow

        if (isAutoBackupMode) {
            // store current time = last backup time
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putLong(AdvancedSettings.KEY_LASTBACKUP, System.currentTimeMillis())
                    .commit();
        }

        return SUCCESS;
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        if (progressListener != null) {
            progressListener.onProgressUpdate(values);
        }
    }

    @Override
    protected void onPostExecute(Integer result) {
        if (!isAutoBackupMode) {
            int messageId;
            switch (result) {
                case SUCCESS:
                    messageId = R.string.backup_success;
                    break;
                case ERROR_FILE_ACCESS:
                    messageId = R.string.backup_failed_file_access;
                    break;
                default:
                    messageId = R.string.backup_failed;
                    break;
            }
            Toast.makeText(context, messageId, Toast.LENGTH_LONG).show();
        }

        if (finishedListener != null) {
            finishedListener.onTaskFinished();
        }
    }

    private int exportData(File exportPath, @ExportType int type) {
        // check if there is any data to export
        Cursor data = getDataCursor(type);
        if (data == null) {
            // query failed
            return ERROR;
        }
        if (data.getCount() == 0) {
            // There is no data? Done.
            data.close();
            return SUCCESS;
        }

        publishProgress(data.getCount(), 0);

        // try to export all data
        try {
            // use Storage Access Framework on KitKat and up
            // but for now still use fixed path for auto backup
            if (!isAutoBackupMode && AndroidUtils.isKitKatOrHigher()) {
                // ensure the user has selected a backup file
                Uri backupFileUri = getDataBackupFile(type);
                if (backupFileUri == null) {
                    return ERROR_FILE_ACCESS;
                }

                ParcelFileDescriptor pfd = context.getContentResolver()
                        .openFileDescriptor(backupFileUri, "w");
                FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());

                if (type == EXPORT_SHOWS) {
                    writeJsonStreamShows(out, data);
                } else if (type == EXPORT_LISTS) {
                    writeJsonStreamLists(out, data);
                } else if (type == EXPORT_MOVIES) {
                    writeJsonStreamMovies(out, data);
                }

                // let the document provider know we're done.
                pfd.close();
            } else {
                File backupFile;
                if (type == EXPORT_SHOWS) {
                    backupFile = new File(exportPath, EXPORT_JSON_FILE_SHOWS);
                } else if (type == EXPORT_LISTS) {
                    backupFile = new File(exportPath, EXPORT_JSON_FILE_LISTS);
                } else if (type == EXPORT_MOVIES) {
                    backupFile = new File(exportPath, EXPORT_JSON_FILE_MOVIES);
                } else {
                    return ERROR;
                }

                OutputStream out = new FileOutputStream(backupFile);
                if (type == EXPORT_SHOWS) {
                    writeJsonStreamShows(out, data);
                } else if (type == EXPORT_LISTS) {
                    writeJsonStreamLists(out, data);
                } else {
                    writeJsonStreamMovies(out, data);
                }
            }
        } catch (FileNotFoundException e) {
            Timber.e(e, "Backup file not found.");
            removeBackupFileUri(type);
            return ERROR_FILE_ACCESS;
        } catch (IOException | SecurityException e) {
            Timber.e(e, "Could not access backup file.");
            removeBackupFileUri(type);
            return ERROR_FILE_ACCESS;
        } catch (JsonParseException e) {
            Timber.e(e, "JSON export failed.");
            return ERROR;
        } finally {
            data.close();
        }

        return SUCCESS;
    }

    @Nullable
    private Cursor getDataCursor(@ExportType int type) {
        if (type == EXPORT_SHOWS) {
            return context.getContentResolver().query(
                    Shows.CONTENT_URI,
                    isFullDump ? ShowsQuery.PROJECTION_FULL : ShowsQuery.PROJECTION,
                    null, null, ShowsQuery.SORT);
        }
        if (type == EXPORT_LISTS) {
            return context.getContentResolver()
                    .query(SeriesGuideContract.Lists.CONTENT_URI,
                            ListsQuery.PROJECTION, null, null,
                            SeriesGuideContract.Lists.SORT_ORDER_THEN_NAME);
        }
        if (type == EXPORT_MOVIES) {
            return context.getContentResolver()
                    .query(Movies.CONTENT_URI,
                            MoviesQuery.PROJECTION, null, null, MoviesQuery.SORT_ORDER);
        }
        return null;
    }

    @Nullable
    private Uri getDataBackupFile(@ExportType int type) {
        if (type == EXPORT_SHOWS) {
            return BackupSettings.getFileUri(context, BackupSettings.KEY_SHOWS_EXPORT_URI);
        }
        if (type == EXPORT_LISTS) {
            return BackupSettings.getFileUri(context, BackupSettings.KEY_LISTS_EXPORT_URI);
        }
        if (type == EXPORT_MOVIES) {
            return BackupSettings.getFileUri(context, BackupSettings.KEY_MOVIES_EXPORT_URI);
        }
        return null;
    }

    private void removeBackupFileUri(@ExportType int type) {
        if (type == EXPORT_SHOWS) {
            BackupSettings.storeFileUri(context, BackupSettings.KEY_SHOWS_EXPORT_URI, null);
        } else if (type == EXPORT_LISTS) {
            BackupSettings.storeFileUri(context, BackupSettings.KEY_LISTS_EXPORT_URI, null);
        } else if (type == EXPORT_MOVIES) {
            BackupSettings.storeFileUri(context, BackupSettings.KEY_MOVIES_EXPORT_URI, null);
        }
    }

    private void writeJsonStreamShows(OutputStream out, Cursor shows) throws IOException {
        int numTotal = shows.getCount();
        int numExported = 0;

        Gson gson = new Gson();
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, "UTF-8"));
        writer.beginArray();

        while (shows.moveToNext()) {
            if (isCancelled()) {
                break;
            }

            Show show = new Show();
            show.tvdbId = shows.getInt(ShowsQuery.ID);
            show.title = shows.getString(ShowsQuery.TITLE);
            show.favorite = shows.getInt(ShowsQuery.FAVORITE) == 1;
            show.hidden = shows.getInt(ShowsQuery.HIDDEN) == 1;
            show.release_time = shows.getInt(ShowsQuery.RELEASE_TIME);
            show.release_weekday = shows.getInt(ShowsQuery.RELEASE_WEEKDAY);
            show.release_timezone = shows.getString(ShowsQuery.RELEASE_TIMEZONE);
            show.country = shows.getString(ShowsQuery.RELEASE_COUNTRY);
            show.lastWatchedEpisode = shows.getInt(ShowsQuery.LASTWATCHEDID);
            show.poster = shows.getString(ShowsQuery.POSTER);
            show.contentRating = shows.getString(ShowsQuery.CONTENTRATING);
            show.status = DataLiberationTools.decodeShowStatus(shows.getInt(ShowsQuery.STATUS));
            show.runtime = shows.getInt(ShowsQuery.RUNTIME);
            show.network = shows.getString(ShowsQuery.NETWORK);
            show.imdbId = shows.getString(ShowsQuery.IMDBID);
            show.firstAired = shows.getString(ShowsQuery.FIRSTAIRED);
            show.rating_user = shows.getInt(ShowsQuery.RATING_USER);
            if (isFullDump) {
                show.overview = shows.getString(ShowsQuery.OVERVIEW);
                show.rating = shows.getDouble(ShowsQuery.RATING_GLOBAL);
                show.rating_votes = shows.getInt(ShowsQuery.RATING_VOTES);
                show.genres = shows.getString(ShowsQuery.GENRES);
                show.actors = shows.getString(ShowsQuery.ACTORS);
                show.lastUpdated = shows.getLong(ShowsQuery.LAST_UPDATED);
                show.lastEdited = shows.getLong(ShowsQuery.LAST_EDITED);
            }

            addSeasons(show);

            gson.toJson(show, Show.class, writer);

            publishProgress(numTotal, ++numExported);
        }

        writer.endArray();
        writer.close();
    }

    private void addSeasons(Show show) {
        show.seasons = new ArrayList<>();
        final Cursor seasonsCursor = context.getContentResolver().query(
                Seasons.buildSeasonsOfShowUri(String.valueOf(show.tvdbId)),
                new String[] {
                        Seasons._ID,
                        Seasons.COMBINED
                }, null, null, null
        );

        if (seasonsCursor == null) {
            return;
        }

        while (seasonsCursor.moveToNext()) {
            Season season = new Season();
            season.tvdbId = seasonsCursor.getInt(0);
            season.season = seasonsCursor.getInt(1);

            addEpisodes(season);

            show.seasons.add(season);
        }

        seasonsCursor.close();
    }

    private void addEpisodes(Season season) {
        season.episodes = new ArrayList<>();
        final Cursor episodesCursor = context.getContentResolver().query(
                Episodes.buildEpisodesOfSeasonUri(String.valueOf(season.tvdbId)),
                isFullDump ? EpisodesQuery.PROJECTION_FULL : EpisodesQuery.PROJECTION, null, null,
                EpisodesQuery.SORT);

        if (episodesCursor == null) {
            return;
        }

        while (episodesCursor.moveToNext()) {
            Episode episode = new Episode();
            episode.tvdbId = episodesCursor.getInt(EpisodesQuery.ID);
            episode.episode = episodesCursor.getInt(EpisodesQuery.NUMBER);
            episode.episodeAbsolute = episodesCursor.getInt(EpisodesQuery.NUMBER_ABSOLUTE);
            episode.episodeDvd = episodesCursor.getDouble(EpisodesQuery.NUMBER_DVD);
            int episodeFlag = episodesCursor.getInt(EpisodesQuery.WATCHED);
            episode.watched = EpisodeTools.isWatched(episodeFlag);
            episode.skipped = EpisodeTools.isSkipped(episodeFlag);
            episode.collected = episodesCursor.getInt(EpisodesQuery.COLLECTED) == 1;
            episode.title = episodesCursor.getString(EpisodesQuery.TITLE);
            episode.firstAired = episodesCursor.getLong(EpisodesQuery.FIRSTAIRED);
            episode.imdbId = episodesCursor.getString(EpisodesQuery.IMDBID);
            episode.rating_user = episodesCursor.getInt(EpisodesQuery.RATING_USER);
            if (isFullDump) {
                episode.overview = episodesCursor.getString(EpisodesQuery.OVERVIEW);
                episode.image = episodesCursor.getString(EpisodesQuery.IMAGE);
                episode.writers = episodesCursor.getString(EpisodesQuery.WRITERS);
                episode.gueststars = episodesCursor.getString(EpisodesQuery.GUESTSTARS);
                episode.directors = episodesCursor.getString(EpisodesQuery.DIRECTORS);
                episode.rating = episodesCursor.getDouble(EpisodesQuery.RATING_GLOBAL);
                episode.rating_votes = episodesCursor.getInt(EpisodesQuery.RATING_VOTES);
                episode.lastEdited = episodesCursor.getLong(EpisodesQuery.LAST_EDITED);
            }

            season.episodes.add(episode);
        }

        episodesCursor.close();
    }

    private void writeJsonStreamLists(OutputStream out, Cursor lists) throws IOException {
        int numTotal = lists.getCount();
        int numExported = 0;

        Gson gson = new Gson();
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, "UTF-8"));
        writer.beginArray();

        while (lists.moveToNext()) {
            if (isCancelled()) {
                break;
            }

            List list = new List();
            list.listId = lists.getString(ListsQuery.ID);
            list.name = lists.getString(ListsQuery.NAME);
            list.order = lists.getInt(ListsQuery.ORDER);

            addListItems(list);

            gson.toJson(list, List.class, writer);

            publishProgress(numTotal, ++numExported);
        }

        writer.endArray();
        writer.close();
    }

    private void addListItems(List list) {
        final Cursor listItems = context.getContentResolver().query(
                ListItems.CONTENT_URI, ListItemsQuery.PROJECTION,
                ListItemsQuery.SELECTION,
                new String[] {
                        list.listId
                }, null
        );
        if (listItems == null) {
            return;
        }

        list.items = new ArrayList<>();
        while (listItems.moveToNext()) {
            ListItem item = new ListItem();
            item.listItemId = listItems.getString(ListItemsQuery.ID);
            item.tvdbId = listItems.getInt(ListItemsQuery.ITEM_REF_ID);
            switch (listItems.getInt(ListItemsQuery.TYPE)) {
                case ListItemTypes.SHOW:
                    item.type = ListItemTypesExport.SHOW;
                    break;
                case ListItemTypes.SEASON:
                    item.type = ListItemTypesExport.SEASON;
                    break;
                case ListItemTypes.EPISODE:
                    item.type = ListItemTypesExport.EPISODE;
                    break;
            }

            list.items.add(item);
        }

        listItems.close();
    }

    private void writeJsonStreamMovies(OutputStream out, Cursor movies) throws IOException {
        int numTotal = movies.getCount();
        int numExported = 0;

        Gson gson = new Gson();
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, "UTF-8"));
        writer.beginArray();

        while (movies.moveToNext()) {
            if (isCancelled()) {
                break;
            }

            Movie movie = new Movie();
            movie.tmdbId = movies.getInt(MoviesQuery.TMDB_ID);
            movie.imdbId = movies.getString(MoviesQuery.IMDB_ID);
            movie.title = movies.getString(MoviesQuery.TITLE);
            movie.releasedUtcMs = movies.getLong(MoviesQuery.RELEASED_UTC_MS);
            movie.runtimeMin = movies.getInt(MoviesQuery.RUNTIME_MIN);
            movie.poster = movies.getString(MoviesQuery.POSTER);
            movie.inCollection = movies.getInt(MoviesQuery.IN_COLLECTION) == 1;
            movie.inWatchlist = movies.getInt(MoviesQuery.IN_WATCHLIST) == 1;
            movie.watched = movies.getInt(MoviesQuery.WATCHED) == 1;
            if (isFullDump) {
                movie.overview = movies.getString(MoviesQuery.OVERVIEW);
            }

            gson.toJson(movie, Movie.class, writer);

            publishProgress(numTotal, ++numExported);
        }

        writer.endArray();
        writer.close();
    }

    public interface ShowsQuery {
        String[] PROJECTION = new String[] {
                Shows._ID,
                Shows.TITLE,
                Shows.FAVORITE,
                Shows.HIDDEN,
                Shows.RELEASE_TIME,
                Shows.RELEASE_WEEKDAY,
                Shows.RELEASE_TIMEZONE,
                Shows.RELEASE_COUNTRY,
                Shows.LASTWATCHEDID,
                Shows.POSTER,
                Shows.CONTENTRATING,
                Shows.STATUS,
                Shows.RUNTIME,
                Shows.NETWORK,
                Shows.IMDBID,
                Shows.FIRST_RELEASE,
                Shows.RATING_USER
        };
        String[] PROJECTION_FULL = new String[] {
                Shows._ID,
                Shows.TITLE,
                Shows.FAVORITE,
                Shows.HIDDEN,
                Shows.RELEASE_TIME,
                Shows.RELEASE_WEEKDAY,
                Shows.RELEASE_TIMEZONE,
                Shows.RELEASE_COUNTRY,
                Shows.LASTWATCHEDID,
                Shows.POSTER,
                Shows.CONTENTRATING,
                Shows.STATUS,
                Shows.RUNTIME,
                Shows.NETWORK,
                Shows.IMDBID,
                Shows.FIRST_RELEASE,
                Shows.RATING_USER,
                Shows.OVERVIEW,
                Shows.RATING_GLOBAL,
                Shows.RATING_VOTES,
                Shows.GENRES,
                Shows.ACTORS,
                Shows.LASTUPDATED,
                Shows.LASTEDIT
        };

        String SORT = Shows.TITLE + " COLLATE NOCASE ASC";

        int ID = 0;
        int TITLE = 1;
        int FAVORITE = 2;
        int HIDDEN = 3;
        int RELEASE_TIME = 4;
        int RELEASE_WEEKDAY = 5;
        int RELEASE_TIMEZONE = 6;
        int RELEASE_COUNTRY = 7;
        int LASTWATCHEDID = 8;
        int POSTER = 9;
        int CONTENTRATING = 10;
        int STATUS = 11;
        int RUNTIME = 12;
        int NETWORK = 13;
        int IMDBID = 14;
        int FIRSTAIRED = 15;
        int RATING_USER = 16;
        // Full dump only
        int OVERVIEW = 17;
        int RATING_GLOBAL = 18;
        int RATING_VOTES = 19;
        int GENRES = 20;
        int ACTORS = 21;
        int LAST_UPDATED = 22;
        int LAST_EDITED = 23;
    }

    public interface EpisodesQuery {
        String[] PROJECTION = new String[] {
                Episodes._ID,
                Episodes.NUMBER,
                Episodes.ABSOLUTE_NUMBER,
                Episodes.WATCHED,
                Episodes.COLLECTED,
                Episodes.TITLE,
                Episodes.FIRSTAIREDMS,
                Episodes.IMDBID,
                Episodes.DVDNUMBER,
                Episodes.RATING_USER
        };
        String[] PROJECTION_FULL = new String[] {
                Episodes._ID,
                Episodes.NUMBER,
                Episodes.ABSOLUTE_NUMBER,
                Episodes.WATCHED,
                Episodes.COLLECTED,
                Episodes.TITLE,
                Episodes.FIRSTAIREDMS,
                Episodes.IMDBID,
                Episodes.DVDNUMBER,
                Episodes.RATING_USER,
                // Full dump only
                Episodes.OVERVIEW,
                Episodes.IMAGE,
                Episodes.WRITERS,
                Episodes.GUESTSTARS,
                Episodes.DIRECTORS,
                Episodes.RATING_GLOBAL,
                Episodes.RATING_VOTES,
                Episodes.LAST_EDITED
        };

        String SORT = Episodes.NUMBER + " ASC";

        int ID = 0;
        int NUMBER = 1;
        int NUMBER_ABSOLUTE = 2;
        int WATCHED = 3;
        int COLLECTED = 4;
        int TITLE = 5;
        int FIRSTAIRED = 6;
        int IMDBID = 7;
        int NUMBER_DVD = 8;
        int RATING_USER = 9;
        // Full dump only
        int OVERVIEW = 10;
        int IMAGE = 11;
        int WRITERS = 12;
        int GUESTSTARS = 13;
        int DIRECTORS = 14;
        int RATING_GLOBAL = 15;
        int RATING_VOTES = 16;
        int LAST_EDITED = 17;
    }

    public interface ListsQuery {
        String[] PROJECTION = new String[] {
                SeriesGuideContract.Lists.LIST_ID,
                SeriesGuideContract.Lists.NAME,
                SeriesGuideContract.Lists.ORDER
        };

        int ID = 0;
        int NAME = 1;
        int ORDER = 2;
    }

    public interface ListItemsQuery {
        String[] PROJECTION = new String[] {
                ListItems.LIST_ITEM_ID, SeriesGuideContract.Lists.LIST_ID, ListItems.ITEM_REF_ID,
                ListItems.TYPE
        };

        String SELECTION = SeriesGuideContract.Lists.LIST_ID + "=?";

        int ID = 0;
        int LIST_ID = 1;
        int ITEM_REF_ID = 2;
        int TYPE = 3;
    }

    public interface MoviesQuery {
        String[] PROJECTION = new String[] {
                Movies._ID,
                Movies.TMDB_ID,
                Movies.IMDB_ID,
                Movies.TITLE,
                Movies.RELEASED_UTC_MS,
                Movies.RUNTIME_MIN,
                Movies.POSTER,
                Movies.IN_COLLECTION,
                Movies.IN_WATCHLIST,
                Movies.WATCHED,
                Movies.OVERVIEW
        };

        String SORT_ORDER = Movies.TITLE + " COLLATE NOCASE ASC";

        int TMDB_ID = 1;
        int IMDB_ID = 2;
        int TITLE = 3;
        int RELEASED_UTC_MS = 4;
        int RUNTIME_MIN = 5;
        int POSTER = 6;
        int IN_COLLECTION = 7;
        int IN_WATCHLIST = 8;
        int WATCHED = 9;
        // only in FULL dump
        int OVERVIEW = 10;
    }
}
