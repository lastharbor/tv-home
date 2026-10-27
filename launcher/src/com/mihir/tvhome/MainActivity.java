package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, "continue watching" from the system Watch Next list, and every
 * other app in a grid that scrolls up on Down. Long press (or Menu) on an app: move, app settings,
 * uninstall. After a few idle minutes the screen turns into a clock/weather/sun ambient view.
 * Plain framework views only (no AndroidX); network only for weather and posters, off the main thread.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private final GradientDrawable glowDrawable = new GradientDrawable();
    static final float GLOW_HEIGHT = 0.62f;
    static final int[] SCRIM = {0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000};
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;
    private TextView inputsPill; // null when the TV has nothing to switch to
    private SunArcView sun;
    private LinearLayout weatherRow;
    private WeatherIconView weatherIcon;
    private TextView weatherTemp, weatherDesc;
    private WeatherClient weather;
    private float sunLat = Float.NaN, sunLon;
    private String city = "";

    // Order of apps: dock and grid, as the user arranged them (prefs), else config + alphabetical.
    static final int DOCK_MAX = 6;
    private final Map<String, App> apps = new HashMap<>();
    private final List<String> dockOrder = new ArrayList<>(), gridOrder = new ArrayList<>();
    private final List<String> shownDock = new ArrayList<>(), shownGrid = new ArrayList<>();
    private String movingPkg;        // set while an app is being moved with the D-pad
    private int swallowKeyUp = -1;   // a key whose DOWN was consumed: its UP must not click anything
    private PopupWindow menuPopup;
    private TextView moveHint;

    private WatchNext watchNext;
    private LinearLayout nextSection, nextRow;
    private final List<View> nextCards = new ArrayList<>();
    private String nextSignature = "";

    private FrameLayout ambient;
    private LinearLayout ambientBox;
    private TextView ambClock, ambDate, ambWeather;
    private WeatherIconView ambIcon;
    private SunArcView ambSun;
    private boolean ambientOn;
    private long lastInput;
    private final Runnable idleCheck = this::checkIdle;

    private final Map<Character, List<String>> wallsByTheme = new HashMap<>();
    private char shownTheme;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateClock(); updateWifi(); sun.update(false); ambSun.update(false); weather.refresh();
            if (wallTheme() != shownTheme) nextWallpaper();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        watchNext = new WatchNext(this);
        loadOrder();
        buildApps();
        refreshNext(); // ready before the first resume
        weather = new WeatherClient(this, new WeatherClient.Listener() {
            @Override public void onLocation(double lat, double lon, String c) { showLocation(lat, lon, c); }
            @Override public void onWeather(WeatherClient.Weather w) { showWeather(w); }
        });

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        weather.refresh();
        sun.update(true);
        refreshNext();
        lastInput = SystemClock.elapsedRealtime();
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, 5000);
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        else if (scroll.getScrollY() == 0) playReturn();
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
        if (menuPopup != null) menuPopup.dismiss();
        handler.removeCallbacks(idleCheck);
        finishMove();
        exitAmbient();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (menuPopup != null) menuPopup.dismiss();
        finishMove();
        exitAmbient();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // The scrim (darker top and bottom so text stays readable) is baked into each photo when it
        // is decoded: one less full-screen layer to blend every frame on a weak GPU.
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }
        // File name prefix = theme: n nature, c cities, s space (see WALLPAPER-CREDITS.md).
        for (String w : wallpapers) {
            List<String> l = wallsByTheme.get(w.charAt(0));
            if (l == null) wallsByTheme.put(w.charAt(0), l = new ArrayList<>());
            l.add(w);
        }

        // Glow covers only the lower part of the screen (where the dock is): drawn at half width and
        // stretched 2x so the radial gradient is an ellipse that fades out before the view's top edge.
        glow = new View(this);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        root.addView(glow, new FrameLayout.LayoutParams(sw / 2, Math.round(sh * GLOW_HEIGHT), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        glow.setScaleX(2f);
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientCenter(0.5f, 0.65f);
        glowDrawable.setGradientRadius(sh * GLOW_HEIGHT * 0.65f);
        glow.setBackground(glowDrawable);
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        content.addView(buildNextSection(), new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText(getString(R.string.all_apps).toUpperCase(Locale.getDefault()));
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));

        root.addView(buildAmbient(), new FrameLayout.LayoutParams(-1, -1));

        moveHint = text(10, "sans-serif-medium", 0xFF111111);
        moveHint.setText(getString(R.string.move_hint));
        moveHint.setPadding(dp(16), dp(5), dp(16), dp(5));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setCornerRadius(dp(14));
        hintBg.setColor(0xF2FFFFFF);
        moveHint.setBackground(hintBg);
        moveHint.setVisibility(View.GONE);
        root.addView(moveHint, lp(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dp(3)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);

        // Weather under the date: glyph, temperature, condition and city.
        weatherRow = new LinearLayout(this);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setAlpha(0f);
        weatherIcon = new WeatherIconView(this);
        weatherRow.addView(weatherIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        weatherTemp = text(19, "sans-serif-light", Color.WHITE);
        weatherRow.addView(weatherTemp, llp(-2, -2, dp(8), 0, dp(10), 0));
        weatherDesc = text(9, "sans-serif-medium", 0x99FFFFFF);
        weatherDesc.setLetterSpacing(0.18f);
        weatherDesc.setSingleLine(true);
        weatherDesc.setPadding(0, 0, dp(16), 0);
        weatherRow.addView(weatherDesc);
        for (TextView t : new TextView[]{weatherTemp, weatherDesc}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        left.addView(weatherRow, llp(-2, -2, 0, dp(12), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        // Between the clock and the pills: where the sun is right now on today's path.
        sun = new SunArcView(this);
        // A glass tile just right of the clock column, clear of the pills.
        header.addView(sun, lp(dp(460), dp(118), Gravity.START | Gravity.TOP, dp(390), 0, 0, 0));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        wifiPill = pill(getString(R.string.wifi), "◉");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill(getString(R.string.settings), "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        List<TextView> row = new ArrayList<>();
        // Inputs only where there is something to switch to (HDMI inputs, a Live TV app or an input picker).
        if (hasInputs()) {
            inputsPill = pill(getString(R.string.inputs), "⇥");
            inputsPill.setOnClickListener(v -> showInputs(v));
            row.add(inputsPill);
        }
        row.add(wifiPill);
        row.add(settings);
        for (TextView p : row) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        scanApps();
        layoutApps();
    }

    private void scanApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }
        apps.clear();
        apps.putAll(byPkg);
    }

    /** Build the dock and grid views from the scanned apps and the saved order. */
    private void layoutApps() {
        List<App> dock = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String p : dockOrder) if (apps.containsKey(p) && dock.size() < DOCK_MAX && placed.add(p)) dock.add(apps.get(p));
        List<App> rest = new ArrayList<>();
        for (String p : gridOrder) if (apps.containsKey(p) && placed.add(p)) rest.add(apps.get(p));
        // Apps not arranged yet (new installs, or no custom order at all) follow alphabetically.
        List<App> fresh = new ArrayList<>();
        for (App a : apps.values()) if (!placed.contains(a.pkg)) fresh.add(a);
        Collections.sort(fresh, (x, y) -> x.label.compareToIgnoreCase(y.label));
        rest.addAll(fresh);
        shownDock.clear();
        for (App a : dock) shownDock.add(a.pkg);
        shownGrid.clear();
        for (App a : rest) shownGrid.add(a.pkg);

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty() && movingPkg == null) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (movingPkg != null) focusPkg(movingPkg);
        else if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    private void focusPkg(String pkg) {
        for (View v : dockCards) if (((App) v.getTag()).pkg.equals(pkg)) { v.requestFocus(); return; }
        for (View v : gridCards) if (((App) v.getTag()).pkg.equals(pkg)) { v.requestFocus(); return; }
    }

    private void loadOrder() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        String d = p.getString("dock", null);
        dockOrder.clear();
        dockOrder.addAll(d == null ? Arrays.asList(dockApps) : split(d));
        gridOrder.clear();
        gridOrder.addAll(split(p.getString("grid", "")));
    }

    private void saveOrder() {
        getPreferences(MODE_PRIVATE).edit().putString("dock", TextUtils.join(",", dockOrder))
                .putString("grid", TextUtils.join(",", gridOrder)).apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String x : s.split(",")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        boolean moving = app.pkg.equals(movingPkg);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(moving ? 1f : 0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        if (moving) {
            // Being moved: amber ring and arrows over the art.
            ring.setStroke(dp(3), 0xFFFFC85A);
            TextView arrows = text(22, "sans-serif-light", Color.WHITE);
            arrows.setText("‹   ›");
            arrows.setGravity(Gravity.CENTER);
            arrows.setBackgroundColor(0x73000000);
            card.addView(arrows, new FrameLayout.LayoutParams(-1, -1));
        }
        // The lifted card's shadow takes the app's colour.
        card.setOutlineSpotShadowColor(app.color);
        card.setOutlineAmbientShadowColor(app.color);
        // A diagonal streak of light that crosses the card once when it gains focus.
        View sweep = new View(this);
        sweep.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x00FFFFFF}));
        sweep.setVisibility(View.INVISIBLE);
        card.addView(sweep, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has || moving ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) { sweep.animate().cancel(); sweep.setVisibility(View.INVISIBLE); return; }
            playSweep(sweep, v.getWidth());
            int from = lastFocusedId;
            lastFocusedId = v.getId();
            if (inDock) {
                swapTitle(app.label, dockIndexOf(from) > dockCards.indexOf(v) ? -1 : 1);
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                animateGlow(app.color);
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });
        card.setOnLongClickListener(v -> {
            if (movingPkg == null) showAppMenu(v, app);
            return true;
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size(), n = nextCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(n > 0 ? nextCards.get(Math.min(i, n - 1)).getId()
                    : g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        for (int k = 0; k < n; k++) {
            View c = nextCards.get(k);
            c.setNextFocusUpId(d > 0 ? dockCards.get(Math.min(k, d - 1)).getId() : topId);
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(k, g - 1)).getId() : c.getId());
            c.setNextFocusLeftId(k == 0 ? c.getId() : nextCards.get(k - 1).getId());
            c.setNextFocusRightId(k == n - 1 ? c.getId() : nextCards.get(k + 1).getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (n > 0 ? nextCards.get(Math.min(col, n - 1)).getId() : d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First row under the dock: slide the dock band up to the top edge; later rows centre on screen.
        boolean first = grid.indexOfChild(row) == 0 && nextCards.isEmpty();
        scrollTo(first ? bandTop() : y - (int) (screenH * 0.42f));
    }

    private int bandTop() {
        View band = (View) dockRow.getParent();
        return hero.getTop() + band.getTop() + dockRow.getTop() - dp(22);
    }

    private void scrollTo(int target) {
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow(getString(R.string.live_tv), () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow(getString(R.string.all_sources), () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow(getString(R.string.settings), () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private boolean hasInputs() {
        if (isInstalled(liveTvPkg) || isInstalled(inputPickerPkg)) return true;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) return true;
        }
        return false;
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(getString(greetingFor(h)).toUpperCase(Locale.getDefault()));
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        flipClock(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : new DateFormatSymbols(Locale.getDefault()).getAmPmStrings()[h < 12 ? 0 : 1]);
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        ambClock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ambDate.setText(date.getText());
    }

    /** Where the day's parts start differs by language, so the hours are resources too. */
    private int greetingFor(int h) {
        if (h < getResources().getInteger(R.integer.morning_starts)
                || h >= getResources().getInteger(R.integer.night_starts)) return R.string.good_night;
        if (h < getResources().getInteger(R.integer.afternoon_starts)) return R.string.good_morning;
        if (h < getResources().getInteger(R.integer.evening_starts)) return R.string.good_afternoon;
        return R.string.good_evening;
    }

    private void updateWifi() {
        String name = getString(R.string.wifi);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = getString(R.string.offline);
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText("◉  " + name);
    }

    // ---------------------------------------------------------------- sun & weather

    private void showLocation(double lat, double lon, String c) {
        city = c == null ? "" : c;
        if ((float) lat == sunLat && (float) lon == sunLon) return;
        sunLat = (float) lat;
        sunLon = (float) lon;
        sun.setLocation(lat, lon);
        ambSun.setLocation(lat, lon);
    }

    private void showWeather(WeatherClient.Weather w) {
        long t = Math.round(w.temp);
        weatherTemp.setText((t > 0 ? "+" : t < 0 ? "−" : "") + Math.abs(t) + "°");
        String desc = getString(conditionText(w.code));
        if (!city.isEmpty()) desc += "  ·  " + city;
        weatherDesc.setText(desc.toUpperCase(Locale.getDefault()));
        weatherIcon.set(WeatherIconView.kindOf(w.code), w.day);
        ambIcon.set(WeatherIconView.kindOf(w.code), w.day);
        ambWeather.setText(weatherTemp.getText() + "   " + getString(conditionText(w.code)) + (city.isEmpty() ? "" : "  ·  " + city));
        if (weatherRow.getAlpha() == 0f) {
            weatherRow.setTranslationY(-dp(6));
            weatherRow.animate().alpha(1f).translationY(0).setDuration(600).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private static int conditionText(int code) {
        switch (code) {
            case 0: return R.string.wx_clear;
            case 1: return R.string.wx_mainly_clear;
            case 2: return R.string.wx_partly_cloudy;
            case 3: return R.string.wx_overcast;
            case 45: case 48: return R.string.wx_fog;
            case 51: case 53: case 55: return R.string.wx_drizzle;
            case 56: case 57: case 66: case 67: return R.string.wx_freezing_rain;
            case 61: case 63: case 65: return R.string.wx_rain;
            case 71: case 73: case 75: case 77: return R.string.wx_snow;
            case 80: case 81: case 82: return R.string.wx_showers;
            case 85: case 86: return R.string.wx_snow_showers;
            case 95: return R.string.wx_thunder;
            case 96: case 99: return R.string.wx_thunder_hail;
            default: return R.string.wx_overcast;
        }
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        glowDrawable.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.18f), Color.TRANSPARENT});
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        // Each theme keeps its own place in its list, so returning to a theme continues where it left off.
        char theme = wallTheme();
        List<String> pool = wallsByTheme.get(theme);
        if (pool == null || pool.isEmpty()) pool = Arrays.asList(wallpapers);
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        wallIndex = (prefs.getInt("wall_" + theme, -1) + 1) % pool.size();
        prefs.edit().putInt("wall_" + theme, wallIndex).apply();
        shownTheme = theme;
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + pool.get(wallIndex);
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inMutable = true;
                bm = BitmapFactory.decodeStream(in, null, o);
                if (bm != null) bakeScrim(bm);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private static void bakeScrim(Bitmap bm) {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setDither(true); // RGB_565 bands without it
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, bm.getHeight(), SCRIM, null,
                android.graphics.Shader.TileMode.CLAMP));
        new Canvas(bm).drawRect(0, 0, bm.getWidth(), bm.getHeight(), p);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        // The new photo settles in: fades up while easing back from a slight zoom.
        wallFront.setScaleX(1.06f);
        wallFront.setScaleY(1.06f);
        wallFront.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(new DecelerateInterpolator(1.6f))
                .setDuration(firstWallpaper ? 900 : 2600).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
            // The photo is opaque and full-screen: the black window and root fills under it are wasted fill rate.
            getWindow().setBackgroundDrawable(null);
            root.setBackground(null);
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    /** Nature by day, city lights from dusk and before dawn, space late at night. */
    private char wallTheme() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        if (h >= 23 || h < 5) return 's';
        boolean dark = Float.isNaN(sunLat) ? (h < 7 || h >= 18)
                : SunArcView.elevation(sunLat, sunLon, now.getTimeInMillis()) < 2;
        return dark ? 'c' : 'n';
    }

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    /** Back from an app: the dock rises a little, tile by tile. Short, so it never delays input. */
    private void playReturn() {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0.4f);
            t.setTranslationY(dp(14));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(320).setStartDelay(i * 40L).start();
        }
    }

    private void playSweep(View sweep, int width) {
        if (width == 0) return;
        sweep.animate().cancel();
        sweep.setVisibility(View.VISIBLE);
        sweep.setTranslationX(-width);
        sweep.animate().translationX(width).setStartDelay(120).setDuration(650)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> sweep.setVisibility(View.INVISIBLE)).start();
    }

    private int dockIndexOf(int viewId) {
        for (int i = 0; i < dockCards.size(); i++) if (dockCards.get(i).getId() == viewId) return i;
        return -1;
    }

    /** The app name slides in from the side the focus moved to (dir 1 = moved right). */
    private void swapTitle(CharSequence label, int dir) {
        if (label.toString().contentEquals(title.getText()) && title.getAlpha() == 1f) return;
        title.animate().cancel();
        title.setText(label);
        title.setAlpha(0f);
        title.setTranslationX(dir * dp(18));
        title.animate().alpha(1f).translationX(0).setStartDelay(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
    }

    /** Minute change: the old time drifts up and out, the new one comes up from below. */
    private void flipClock(String time) {
        if (time.contentEquals(clock.getText())) return;
        if (clock.getText().length() == 0) { clock.setText(time); return; }
        clock.animate().cancel();
        clock.animate().alpha(0f).translationY(-dp(8)).setDuration(160).withEndAction(() -> {
            clock.setText(time);
            clock.setTranslationY(dp(8));
            clock.animate().alpha(1f).translationY(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }).start();
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        lastInput = SystemClock.elapsedRealtime();
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && code == swallowKeyUp) { swallowKeyUp = -1; return true; }
        if (ambientOn) {
            // Any key just wakes the home screen; it doesn't also act on it.
            if (down) { exitAmbient(); swallowKeyUp = code; }
            return true;
        }
        if (movingPkg != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (down) moveBy(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) moveBy(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: if (down) moveBy(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (down) moveBy(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE:
                    if (down && e.getRepeatCount() == 0) { finishMove(); swallowKeyUp = code; }
                    return true;
            }
        }
        if (code == KeyEvent.KEYCODE_MENU && down && e.getRepeatCount() == 0) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof App) { showAppMenu(f, (App) f.getTag()); swallowKeyUp = code; return true; }
            // Anywhere else, Menu opens the Inputs dropdown.
            if (inputsPill != null) {
                showInputs(inputsPill);
                swallowKeyUp = code;
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- app menu & moving

    /** Long press / Menu on an app: move it, open its Android settings page, or uninstall it. */
    private void showAppMenu(View anchor, App app) {
        if (menuPopup != null) menuPopup.dismiss();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(10), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        TextView head = text(9, "sans-serif-medium", 0x99FFFFFF);
        head.setText(app.label.toUpperCase(Locale.getDefault()));
        head.setLetterSpacing(0.18f);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setPadding(dp(14), 0, dp(14), dp(6));
        list.addView(head);

        PopupWindow pw = new PopupWindow(list, dp(240), -2, true);
        List<TextView> rows = new ArrayList<>();
        rows.add(inputRow("⇄  " + getString(R.string.menu_move), () -> startMove(app.pkg)));
        rows.add(inputRow("⚙  " + getString(R.string.menu_app_info), () -> openAppInfo(app.pkg)));
        if (removable(app.pkg)) rows.add(inputRow("✕  " + getString(R.string.menu_uninstall),
                () -> launch(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> {
            menuPopup = null;
            if (movingPkg == null && anchor.isAttachedToWindow()) anchor.requestFocus();
        });
        menuPopup = pw;
        // Under the card if it fits, otherwise above it (dock cards sit near the bottom edge).
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int h = list.getMeasuredHeight(), screenH = getResources().getDisplayMetrics().heightPixels;
        int below = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY()) + dp(10);
        int y = below + h <= screenH - dp(12) ? below : Math.max(dp(12), at[1] - h - dp(10));
        pw.showAtLocation(anchor, Gravity.START | Gravity.TOP, at[0], y);
        rows.get(0).requestFocus();
    }

    private void openAppInfo(String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        // Two apps answer this intent on the stick (phone and TV settings): go straight to the TV one.
        if (!launch(new Intent(i).setPackage("com.android.tv.settings"))) launch(i);
    }

    private boolean removable(String pkg) {
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        moveHint.animate().cancel();
        moveHint.setAlpha(0f);
        moveHint.setVisibility(View.VISIBLE);
        moveHint.animate().alpha(1f).setDuration(200).start();
        layoutApps();
    }

    /**
     * One step of the moving app. Dock: left/right swaps, down drops it into the grid's first row.
     * Grid: left/right/up/down shifts it through the grid, up from the first row lifts it into the dock.
     */
    private void moveBy(int dx, int dy) {
        List<String> dock = new ArrayList<>(shownDock), grid = new ArrayList<>(shownGrid);
        int i = dock.indexOf(movingPkg), j = grid.indexOf(movingPkg);
        if (i >= 0) {
            if (dy < 0) return;
            if (dy > 0) {
                if (dock.size() == 1) return; // keep at least one app in the dock
                dock.remove(i);
                grid.add(Math.min(i, grid.size()), movingPkg);
            } else {
                int k = i + dx;
                if (k < 0 || k >= dock.size()) return;
                Collections.swap(dock, i, k);
            }
        } else if (j >= 0) {
            if (dy < 0 && j < cols) {
                if (dock.size() >= DOCK_MAX) return;
                grid.remove(j);
                dock.add(Math.min(j, dock.size()), movingPkg);
            } else {
                int k = dy != 0 ? j + dy * cols : j + dx;
                if (dy > 0 && k >= grid.size()) k = grid.size() - 1;
                if (k < 0 || k >= grid.size() || k == j) return;
                grid.remove(j);
                grid.add(k, movingPkg);
            }
        } else {
            return;
        }
        dockOrder.clear();
        dockOrder.addAll(dock);
        gridOrder.clear();
        gridOrder.addAll(grid);
        layoutApps();
    }

    private void finishMove() {
        if (movingPkg == null) return;
        String pkg = movingPkg;
        movingPkg = null;
        saveOrder();
        moveHint.animate().alpha(0f).setDuration(200).withEndAction(() -> moveHint.setVisibility(View.GONE)).start();
        layoutApps();
        focusPkg(pkg);
    }

    // ---------------------------------------------------------------- continue watching

    private View buildNextSection() {
        nextSection = new LinearLayout(this);
        nextSection.setOrientation(LinearLayout.VERTICAL);
        nextSection.setClipChildren(false);
        nextSection.setClipToPadding(false);
        nextSection.setVisibility(View.GONE);
        TextView label = text(11, "sans-serif-medium", 0x99FFFFFF);
        label.setText(getString(R.string.continue_watching).toUpperCase(Locale.getDefault()));
        label.setLetterSpacing(0.22f);
        nextSection.addView(label, llp(-2, -2, dp(68), dp(8), 0, dp(8)));

        HorizontalScrollView hsv = new PaddedHScroll(this, dp(68));
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(68), dp(12), dp(68), dp(30));
        nextRow = new LinearLayout(this);
        nextRow.setClipChildren(false);
        nextRow.setClipToPadding(false);
        hsv.addView(nextRow, new FrameLayout.LayoutParams(-2, -2));
        nextSection.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
        return nextSection;
    }

    private void refreshNext() {
        Set<String> launchable = new HashSet<>(apps.keySet());
        watchNext.load(launchable::contains, this::showNext);
    }

    private void showNext(List<WatchNext.Item> items) {
        android.util.Log.i("tvhome", "watch next: " + items.size() + " items");
        StringBuilder sig = new StringBuilder();
        for (WatchNext.Item it : items) sig.append(it.id).append(':').append(it.position / 60_000).append(',');
        if (sig.toString().equals(nextSignature)) return;
        nextSignature = sig.toString();
        boolean hadFocus = nextCards.contains(getCurrentFocus());
        nextRow.removeAllViews();
        nextCards.clear();
        int sw = getResources().getDisplayMetrics().widthPixels;
        int w = (sw - dp(68) * 2 - dp(20) * 3) / 4; // four cards across, the rest scroll in
        for (int k = 0; k < items.size(); k++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, -2);
            if (k < items.size() - 1) p.rightMargin = dp(20);
            nextRow.addView(nextCard(items.get(k)), p);
        }
        nextSection.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();
        if (hadFocus) {
            if (!nextCards.isEmpty()) nextCards.get(0).requestFocus();
            else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        }
    }

    /** Poster, title, episode and time left over a dark fade, and a progress line along the bottom. */
    private View nextCard(WatchNext.Item it) {
        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        card.setBackground(bg);
        App owner = apps.get(it.pkg);
        int accent = owner != null ? owner.color : 0xFF3A3A3A;
        card.setOutlineSpotShadowColor(accent);
        card.setOutlineAmbientShadowColor(accent);

        ImageView img = new TopCropImageView(this);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        watchNext.poster(it.poster, img);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x1A000000, 0xE6000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, dp(12), dp(11));
        TextView t = text(12, "sans-serif-medium", Color.WHITE);
        t.setText(it.title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(t);
        TextView s = text(9, "sans-serif", 0xB3FFFFFF);
        s.setText(nextSubtitle(it, owner));
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(s, llp(-1, -2, 0, dp(2), 0, 0));
        card.addView(txt, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (it.duration > 0 && it.position > 0) {
            LinearLayout bar = new LinearLayout(this);
            float done = Math.min(1f, (float) it.position / it.duration);
            View fill = new View(this);
            fill.setBackgroundColor(0xE6FFFFFF);
            bar.addView(fill, new LinearLayout.LayoutParams(0, -1, done));
            View rest = new View(this);
            rest.setBackgroundColor(0x40FFFFFF);
            bar.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f - done));
            card.addView(bar, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        card.setOnClickListener(v -> {
            Intent i = WatchNext.intentFor(it);
            if (i == null || !launch(i)) openPackage(it.pkg);
        });
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            if (!has) return;
            lastFocusedId = v.getId();
            animateGlow(accent);
            fadeGlow(0.35f);
            title.animate().alpha(0f).setDuration(200).start();
            scrollTo(bandTop());
        });
        nextCards.add(card);
        return card;
    }

    private String nextSubtitle(WatchNext.Item it, App owner) {
        List<String> parts = new ArrayList<>();
        if (owner != null) parts.add(owner.label);
        if (it.season > 0 && it.number > 0) parts.add(getString(R.string.episode_sn, it.season, it.number));
        else if (it.number > 0) parts.add(getString(R.string.episode_n, it.number));
        if (it.duration > it.position && it.position > 0) {
            long min = (it.duration - it.position + 59_999) / 60_000;
            String span = min >= 60 ? getString(R.string.sun_span_hm, min / 60, min % 60) : getString(R.string.sun_span_m, min);
            parts.add(getString(R.string.time_left, span));
        }
        return TextUtils.join("  ·  ", parts);
    }

    // ---------------------------------------------------------------- ambient mode

    private View buildAmbient() {
        ambient = new FrameLayout(this);
        ambient.setBackgroundColor(0x66000000);
        ambient.setVisibility(View.GONE);
        ambientBox = new LinearLayout(this);
        ambientBox.setOrientation(LinearLayout.VERTICAL);
        ambientBox.setGravity(Gravity.CENTER_HORIZONTAL);
        ambClock = text(110, "sans-serif-thin", Color.WHITE);
        ambClock.setIncludeFontPadding(false);
        ambientBox.addView(ambClock, new LinearLayout.LayoutParams(-2, -2));
        ambDate = text(12, "sans-serif-medium", 0xB3FFFFFF);
        ambDate.setLetterSpacing(0.3f);
        ambDate.setPadding(dp(12), 0, 0, 0); // balance the trailing letter spacing
        ambientBox.addView(ambDate, llp(-2, -2, 0, dp(6), 0, 0));
        LinearLayout wx = new LinearLayout(this);
        wx.setGravity(Gravity.CENTER_VERTICAL);
        ambIcon = new WeatherIconView(this);
        wx.addView(ambIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        ambWeather = text(15, "sans-serif-light", 0xE6FFFFFF);
        wx.addView(ambWeather, llp(-2, -2, dp(10), 0, 0, 0));
        ambientBox.addView(wx, llp(-2, -2, 0, dp(22), 0, 0));
        ambSun = new SunArcView(this);
        ambientBox.addView(ambSun, llp(dp(460), dp(118), 0, dp(40), 0, 0));
        for (TextView t : new TextView[]{ambClock, ambDate, ambWeather}) t.setShadowLayer(dp(14), 0, dp(1), 0x99000000);
        ambient.addView(ambientBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return ambient;
    }

    private void checkIdle() {
        handler.removeCallbacks(idleCheck);
        long after = getResources().getInteger(R.integer.ambient_minutes) * 60_000L;
        long idle = SystemClock.elapsedRealtime() - lastInput;
        if (ambientOn) drift();
        else if (after > 0 && idle >= after && menuPopup == null && movingPkg == null) enterAmbient();
        handler.postDelayed(idleCheck, ambientOn || after <= 0 ? 60_000 : Math.max(5_000, after - idle));
    }

    private void enterAmbient() {
        ambientOn = true;
        ambientBox.setTranslationX(0);
        ambientBox.setTranslationY(0);
        ambient.setAlpha(0f);
        ambient.setVisibility(View.VISIBLE);
        ambSun.update(true);
        // Once it covers the screen, stop drawing the home UI underneath: the GPU only blends the photo.
        ambient.animate().alpha(1f).setDuration(1500).withEndAction(() -> {
            if (!ambientOn) return;
            scroll.setVisibility(View.INVISIBLE);
            glow.setVisibility(View.INVISIBLE);
        }).start();
    }

    private void exitAmbient() {
        if (!ambientOn) return;
        ambientOn = false;
        scroll.setVisibility(View.VISIBLE);
        glow.setVisibility(View.VISIBLE);
        ambient.animate().cancel();
        ambient.animate().alpha(0f).setDuration(250).withEndAction(() -> ambient.setVisibility(View.GONE)).start();
        lastInput = SystemClock.elapsedRealtime();
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, 5000);
        if (scroll.getScrollY() == 0) playReturn();
    }

    /** Burn-in guard: the clock block wanders a little every minute. */
    private void drift() {
        java.util.Random r = new java.util.Random();
        ambientBox.animate().translationX((r.nextFloat() * 2 - 1) * dp(60)).translationY((r.nextFloat() * 2 - 1) * dp(30))
                .setDuration(4000).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
    }

    /** Scrolls a focused card fully into view including the side padding, not flush to the screen edge. */
    static final class PaddedHScroll extends HorizontalScrollView {
        private final int margin;
        PaddedHScroll(Context c, int margin) { super(c); this.margin = margin; }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            android.graphics.Rect wide = new android.graphics.Rect(r);
            wide.inset(-margin, 0);
            return super.computeScrollDeltaToGetChildRectOnScreen(wide);
        }
    }

    /** Fills its box and crops from near the top, where a poster's faces and title usually are. */
    static final class TopCropImageView extends ImageView {
        TopCropImageView(Context c) { super(c); setScaleType(ScaleType.MATRIX); }
        @Override protected boolean setFrame(int l, int t, int r, int b) {
            boolean changed = super.setFrame(l, t, r, b);
            fit();
            return changed;
        }
        @Override public void setImageBitmap(Bitmap bm) { super.setImageBitmap(bm); fit(); }
        private void fit() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
            float s = Math.max((float) getWidth() / d.getIntrinsicWidth(), (float) getHeight() / d.getIntrinsicHeight());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(s, s);
            m.postTranslate((getWidth() - d.getIntrinsicWidth() * s) / 2f, -(d.getIntrinsicHeight() * s - getHeight()) * 0.18f);
            setImageMatrix(m);
        }
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
