package com.mihir.tvhome;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * "Continue watching": the Watch Next list that video apps publish to the system TvProvider
 * (the same data the stock Android TV home shows), plus poster loading with a small disk cache.
 * Reading other apps' rows needs READ_TV_LISTINGS, granted over ADB at install.
 */
final class WatchNext {
    static final class Item {
        long id, position, duration, engaged;
        String pkg, title, episode, poster, intentUri;
        int season, number;
    }

    static final int MAX_ITEMS = 10;
    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    };

    private final Context ctx;
    private final ExecutorService query = Executors.newSingleThreadExecutor(), posters = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<>(12);
    private final File diskDir;

    WatchNext(Context c) {
        ctx = c;
        diskDir = new File(c.getCacheDir(), "posters");
    }

    /** Newest first, one entry per show, only apps that can be launched, finished ones dropped. */
    void load(Predicate<String> launchable, Consumer<List<Item>> done) {
        query.execute(() -> {
            List<Item> all = new ArrayList<>();
            try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (c.getInt(12) == 0) continue;
                    Item it = new Item();
                    it.id = c.getLong(0);
                    it.pkg = c.getString(1);
                    it.title = c.getString(2);
                    it.episode = c.getString(3);
                    it.season = parseInt(c.getString(4));
                    it.number = parseInt(c.getString(5));
                    it.poster = c.getString(6) != null ? c.getString(6) : c.getString(7);
                    it.intentUri = c.getString(8);
                    it.engaged = c.getLong(9);
                    it.position = c.getLong(10);
                    it.duration = c.getLong(11);
                    if (it.title == null || it.pkg == null) continue;
                    if (it.duration > 0 && it.position > it.duration * 0.97) continue;
                    all.add(it);
                }
            } catch (RuntimeException e) {
                // No TvProvider or no permission: the row just stays hidden.
            }
            Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
            List<Item> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Item it : all) {
                if (out.size() >= MAX_ITEMS) break;
                if (!launchable.test(it.pkg) || !seen.add(it.pkg + "\n" + it.title)) continue;
                out.add(it);
            }
            main.post(() -> done.accept(out));
        });
    }

    /** The app's own deep link; falls back to just opening the app. */
    static Intent intentFor(Item it) {
        if (it.intentUri != null) try {
            Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() == null && i.getPackage() == null) i.setPackage(it.pkg);
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception ignored) { }
        return null;
    }

    void poster(String uri, ImageView into) {
        into.setTag(uri);
        if (uri == null) return;
        Bitmap hit = memory.get(uri);
        if (hit != null) { into.setImageBitmap(hit); return; }
        posters.execute(() -> {
            Bitmap bm = fetch(uri);
            if (bm == null) return;
            main.post(() -> {
                memory.put(uri, bm);
                if (uri.equals(into.getTag())) {
                    into.setImageBitmap(bm);
                    into.setAlpha(0f);
                    into.animate().alpha(1f).setDuration(300).start();
                }
            });
        });
    }

    private Bitmap fetch(String uri) {
        File f = new File(diskDir, Integer.toHexString(uri.hashCode()) + "_" + uri.length());
        byte[] data = null;
        try {
            if (f.isFile()) data = readAll(new java.io.FileInputStream(f));
            else {
                if (uri.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(10000);
                    try { if (c.getResponseCode() == 200) data = readAll(c.getInputStream()); } finally { c.disconnect(); }
                } else {
                    data = readAll(ctx.getContentResolver().openInputStream(Uri.parse(uri)));
                }
                if (data != null) {
                    diskDir.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (data == null) return null;
        // Cards are ~400px wide on the stick: decode no bigger than needed.
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 420) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static int parseInt(String s) {
        try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
