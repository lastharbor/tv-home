package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Location from the public IP (no GPS on a TV), current weather from Open-Meteo (free, no key).
 * Both are cached in prefs: location for a day, weather for 30 minutes. Work runs on one
 * background thread; results are posted to the main thread. Offline, the last values stay up.
 */
final class WeatherClient {
    interface Listener {
        void onLocation(double lat, double lon, String city);
        void onWeather(Weather w);
    }

    static final class Weather {
        double temp, feels, wind;
        int code;
        boolean day;
    }

    static final long LOCATION_TTL = 24 * 3600_000L, WEATHER_TTL = 30 * 60_000L;

    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    WeatherClient(Context c, Listener l) {
        prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
        listener = l;
    }

    /** Show what's cached right away, then fetch whatever has gone stale. */
    void refresh() {
        if (prefs.contains("lat")) {
            listener.onLocation(prefs.getFloat("lat", 0), prefs.getFloat("lon", 0), prefs.getString("city", ""));
        }
        Weather cached = parse(prefs.getString("weather", null));
        if (cached != null) listener.onWeather(cached);

        long now = System.currentTimeMillis();
        boolean locStale = now - prefs.getLong("locAt", 0) > LOCATION_TTL;
        boolean wStale = now - prefs.getLong("weatherAt", 0) > WEATHER_TTL;
        if (busy || (!locStale && !wStale)) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (locStale || !prefs.contains("lat")) fetchLocation();
                if (prefs.contains("lat")) fetchWeather();
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

    private void fetchLocation() {
        double lat, lon;
        String city;
        try {
            JSONObject j = new JSONObject(get("https://ipwho.is/?fields=success,city,latitude,longitude&lang="
                    + Locale.getDefault().getLanguage()));
            if (!j.optBoolean("success")) throw new IllegalStateException("ipwho.is: no result");
            lat = j.getDouble("latitude"); lon = j.getDouble("longitude"); city = j.optString("city");
        } catch (Exception e) {
            try {
                JSONObject j = new JSONObject(get("https://get.geojs.io/v1/ip/geo.json"));
                lat = Double.parseDouble(j.getString("latitude")); lon = Double.parseDouble(j.getString("longitude"));
                city = j.optString("city");
            } catch (Exception e2) {
                return; // keep the old location, if any
            }
        }
        prefs.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon).putString("city", city)
                .putLong("locAt", System.currentTimeMillis()).apply();
        double la = lat, lo = lon;
        String c = city;
        main.post(() -> listener.onLocation(la, lo, c));
    }

    private void fetchWeather() {
        try {
            String body = get(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
                    + "&wind_speed_unit=ms&timezone=auto", prefs.getFloat("lat", 0), prefs.getFloat("lon", 0)));
            Weather w = parse(body);
            if (w == null) return;
            prefs.edit().putString("weather", body).putLong("weatherAt", System.currentTimeMillis()).apply();
            main.post(() -> listener.onWeather(w));
        } catch (Exception ignored) { }
    }

    private static Weather parse(String body) {
        if (body == null) return null;
        try {
            JSONObject c = new JSONObject(body).getJSONObject("current");
            Weather w = new Weather();
            w.temp = c.getDouble("temperature_2m");
            w.feels = c.optDouble("apparent_temperature", w.temp);
            w.wind = c.optDouble("wind_speed_10m", 0);
            w.code = c.getInt("weather_code");
            w.day = c.optInt("is_day", 1) == 1;
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tv-home-launcher");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
