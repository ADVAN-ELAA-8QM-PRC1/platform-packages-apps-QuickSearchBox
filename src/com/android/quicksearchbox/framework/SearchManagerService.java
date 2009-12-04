/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.quicksearchbox.framework;

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.List;

/**
 * The search manager service handles the search UI, and maintains a registry of searchable
 * activities.
 */
public class SearchManagerService {

    // general debugging support
    private static final String TAG = "QSB.SearchManagerService";
    private static final boolean DBG = true;

    private static final int MSG_UPDATE_SEARCHABLES = 0;

    // The number of milliseconds that source update requests are delayed to
    // allow grouping multiple requests.
    private static final long UPDATE_SEARCHABLES_DELAY_MILLIS = 200;

    // Context that the service is running in.
    private final Context mContext;

    private UpdateHandler mHandler;

    // This field is initialized in ensureSearchablesCreated(), and then never modified.
    // Only accessed by ensureSearchablesCreated() and getSearchables()
    private Searchables mSearchables;

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
    }

    public void close() {
        mContext.unregisterReceiver(mPackageChangedReceiver);
    }

    private synchronized void ensureSearchablesCreated() {
        if (mSearchables != null) return;  // already created

        mSearchables = new Searchables(mContext);
        mSearchables.buildSearchableList();

        HandlerThread t = new HandlerThread("SearchManagerService.UpdateThread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        mHandler = new UpdateHandler(t.getLooper());

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(mPackageChangedReceiver, packageFilter);
    }

    private synchronized Searchables getSearchables() {
        ensureSearchablesCreated();
        return mSearchables;
    }

    /**
     * Refreshes the "searchables" list when packages are added/removed.
     */
    private BroadcastReceiver mPackageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                if (DBG) Log.d(TAG, "Got " + action);
                // Update list of searchable activities
                scheduleUpdateSearchables();
            }
        }
    };

    void scheduleUpdateSearchables() {
        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_SEARCHABLES, UPDATE_SEARCHABLES_DELAY_MILLIS);
    }

    private class UpdateHandler extends Handler {

        public UpdateHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_SEARCHABLES:
                    // Remove any duplicate update messages
                    removeMessages(MSG_UPDATE_SEARCHABLES);
                    updateSearchables();
                    break;
            }
        }
    }

    void updateSearchables() {
        if (DBG) Log.d(TAG, "updateSearchables()");
        getSearchables().buildSearchableList();
        broadcastSearchablesChanged();
    }

    /**
     * Informs all listeners that the list of searchables has been updated.
     */
    void broadcastSearchablesChanged() {
        Intent intent = new Intent(SearchManager.INTENT_ACTION_SEARCHABLES_CHANGED);
        if (DBG) Log.d(TAG, "sendBroadcast(" + intent + ")");
        mContext.sendBroadcast(intent);
    }

    //
    // Searchable activities API
    //

    /**
     * Returns the SearchableInfo for a given activity.
     *
     * @param launchActivity The activity from which we're launching this search.
     * @param globalSearch If false, this will only launch the search that has been specifically
     * defined by the application (which is usually defined as a local search).  If no default
     * search is defined in the current application or activity, no search will be launched.
     * If true, this will always launch a platform-global (e.g. web-based) search instead.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    public SearchableInfo getSearchableInfo(final ComponentName launchActivity,
            final boolean globalSearch) {
        if (globalSearch) {
            return getSearchables().getDefaultSearchable();
        } else {
            if (launchActivity == null) {
                Log.e(TAG, "getSearchableInfo(), activity == null");
                return null;
            }
            return getSearchables().getSearchableInfo(launchActivity);
        }
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        return getSearchables().getSearchablesInGlobalSearchList();
    }

    /**
     * Returns a list of the searchable activities that handle web searches.
     * Can be called from any thread.
     */
    public List<SearchableInfo> getSearchablesForWebSearch() {
        return getSearchables().getSearchablesForWebSearchList();
    }

    /**
     * Returns the default searchable activity for web searches.
     * Can be called from any thread.
     */
    public SearchableInfo getDefaultSearchableForWebSearch() {
        return getSearchables().getDefaultSearchableForWebSearch();
    }

    /**
     * Sets the default searchable activity for web searches.
     * Can be called from any thread.
     */
    public void setDefaultWebSearch(final ComponentName component) {
        getSearchables().setDefaultWebSearch(component);
        broadcastSearchablesChanged();
    }

}
