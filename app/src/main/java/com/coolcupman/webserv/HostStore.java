package com.coolcupman.webserv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Saved and recently used web interfaces, plus small per-host preferences. */
public final class HostStore {

    public static final class Entry {
        public String name;
        public String url;
        public boolean favorite;
        public long lastUsed;

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("name", name)
                    .put("url", url)
                    .put("favorite", favorite)
                    .put("lastUsed", lastUsed);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.name = o.optString("name", "");
            e.url = o.optString("url", "");
            e.favorite = o.optBoolean("favorite", false);
            e.lastUsed = o.optLong("lastUsed", 0);
            return e;
        }
    }

    private static final String PREFS = "hosts";
    private static final String KEY_LIST = "list";
    private static final int MAX_RECENT = 40;

    private final SharedPreferences prefs;

    public HostStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Favourites first, then most recently used. */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Entry.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
            // Corrupt list: start over rather than crash.
        }
        Collections.sort(out, (a, b) -> {
            if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
            return Long.compare(b.lastUsed, a.lastUsed);
        });
        return out;
    }

    /** Records a visit; keeps the existing name/favourite when the URL is already known. */
    public void touch(String url) {
        List<Entry> list = all();
        Entry found = find(list, url);
        if (found == null) {
            found = new Entry();
            found.url = url;
            found.name = UrlUtil.displayHost(url);
            list.add(found);
        }
        found.lastUsed = System.currentTimeMillis();
        save(trim(list));
    }

    public void rename(String url, String name) {
        List<Entry> list = all();
        Entry e = find(list, url);
        if (e == null) return;
        e.name = name;
        save(list);
    }

    public void setFavorite(String url, boolean favorite) {
        List<Entry> list = all();
        Entry e = find(list, url);
        if (e == null) {
            touch(url);
            list = all();
            e = find(list, url);
        }
        if (e == null) return;
        e.favorite = favorite;
        save(list);
    }

    public void remove(String url) {
        List<Entry> list = all();
        Entry e = find(list, url);
        if (e == null) return;
        list.remove(e);
        save(list);
    }

    public boolean isFavorite(String url) {
        Entry e = find(all(), url);
        return e != null && e.favorite;
    }

    // ---- per-host preferences ---------------------------------------------

    public boolean desktopMode(String hostKey) {
        return prefs.getBoolean("desktop:" + hostKey, false);
    }

    public void setDesktopMode(String hostKey, boolean on) {
        prefs.edit().putBoolean("desktop:" + hostKey, on).apply();
    }

    // ---- internals --------------------------------------------------------

    private static Entry find(List<Entry> list, String url) {
        for (Entry e : list) if (e.url.equals(url)) return e;
        return null;
    }

    /** Drops the oldest non-favourites beyond MAX_RECENT. */
    private static List<Entry> trim(List<Entry> list) {
        Collections.sort(list, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        List<Entry> out = new ArrayList<>();
        int recent = 0;
        for (Entry e : list) {
            if (e.favorite || recent++ < MAX_RECENT) out.add(e);
        }
        return out;
    }

    private void save(List<Entry> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : list) arr.put(e.toJson());
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
