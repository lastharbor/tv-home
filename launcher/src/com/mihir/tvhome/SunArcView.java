package com.mihir.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * A glass tile in the header: the next sun event in large type on the left (sunset by day,
 * sunrise by night) with a countdown, and on the right the arc the sun travels today with the sun
 * at its real place on it. By night the arc turns moonlit and a crescent crosses it from sunset to
 * sunrise. Sun times and the arc's shape come from the NOAA solar equations, computed on the device.
 */
final class SunArcView extends View {
    private static final int SAMPLES = 64;
    private final double[] dayElev = new double[SAMPLES + 1];
    private double lat = Double.NaN, lon, maxElev = 1;
    private long dayStart, rise, set, prevSet, nextRise; // UTC millis, 0 = none

    private final RectF tile = new RectF();
    private final Path dayArc = new Path(), nightArc = new Path(), fill = new Path(), moon = new Path(), cut = new Path();
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), edge = stroke(0x24FFFFFF, 1f),
            horizon = stroke(0x33FFFFFF, 1f), lit = stroke(0xFFFFFFFF, 2.6f), dots = stroke(0x66FFFFFF, 2.2f),
            under = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG),
            caption = text(9, 0xA6FFFFFF, "sans-serif-medium", 0.2f), big = text(26, 0xFFFFFFFF, "sans-serif-light", 0f),
            sub = text(10, 0xB3FFFFFF, "sans-serif", 0.04f);
    private boolean shadersBuilt, shadersForDay;
    private float shown;        // marker position along the arc, 0..1
    private float reveal = 1f;
    private ValueAnimator anim, revealAnim; // separate, so a minute tick can't cancel the fade-in

    SunArcView(Context c) {
        super(c);
        glass.setColor(0x47000000);
        dots.setPathEffect(new DashPathEffect(new float[]{0.1f, dp(7)}, 0)); // round caps turn this into dots
        setVisibility(INVISIBLE);
    }

    private static long now() { return System.currentTimeMillis(); }

    void setLocation(double lat, double lon) {
        boolean first = Double.isNaN(this.lat);
        this.lat = lat;
        this.lon = lon;
        dayStart = 0;
        setVisibility(VISIBLE);
        update(first);
    }

    /** Every minute, and with fromStart on return home: the tile fades in and the marker travels to now. */
    void update(boolean fromStart) {
        if (Double.isNaN(lat)) return;
        long now = now();
        long midnight = localMidnight(now);
        if (midnight != dayStart) recompute(midnight);
        float target = progress(now);
        float from = fromStart ? 0f : shown;
        if (fromStart) {
            if (revealAnim != null) revealAnim.cancel();
            revealAnim = ValueAnimator.ofFloat(0f, 1f);
            revealAnim.setDuration(700);
            revealAnim.addUpdateListener(a -> { reveal = (float) a.getAnimatedValue(); invalidate(); });
            revealAnim.start();
        }
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(fromStart ? 1800 : 600);
        anim.setStartDelay(fromStart ? 150 : 0);
        anim.setInterpolator(new DecelerateInterpolator(fromStart ? 2.4f : 1.5f));
        anim.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private boolean isDay(long now) { return rise > 0 && set > rise && now >= rise && now < set; }

    /** Day: 0 at sunrise .. 1 at sunset. Night: 0 at the last sunset .. 1 at the next sunrise. */
    private float progress(long now) {
        if (isDay(now)) return (float) (now - rise) / (set - rise);
        long from = now < rise ? prevSet : set, to = now < rise ? rise : nextRise;
        if (from == 0 || to <= from) return 0.5f;
        return Math.max(0, Math.min(1, (float) (now - from) / (to - from)));
    }

    private void recompute(long midnight) {
        dayStart = midnight;
        long[] today = riseSet(midnight);
        rise = today[0];
        set = today[1];
        prevSet = riseSet(shiftDays(midnight, -1))[1];
        nextRise = riseSet(shiftDays(midnight, 1))[0];
        maxElev = 1;
        if (rise > 0 && set > rise) for (int i = 0; i <= SAMPLES; i++) {
            dayElev[i] = Math.max(0, elevation(lat, lon, rise + (set - rise) * i / SAMPLES));
            maxElev = Math.max(maxElev, dayElev[i]);
        }
        buildArcs();
    }

    /** Sunrise and sunset of the local day starting at midnight (0 if the sun doesn't cross today). */
    private long[] riseSet(long midnight) {
        long step = 5 * 60_000L, r = 0, s = 0;
        double prev = elevation(lat, lon, midnight) + 0.833; // refraction + the sun's radius, as almanacs do
        for (long t = midnight + step; t <= midnight + 86_400_000L; t += step) {
            double e = elevation(lat, lon, t) + 0.833;
            long cross = t - step + (long) (step * prev / (prev - e));
            if (prev < 0 && e >= 0 && r == 0) r = cross;
            if (prev >= 0 && e < 0) s = cross;
            prev = e;
        }
        return new long[]{r, s};
    }

    // ---------------------------------------------------------------- geometry

    private float arcLeft() { return dp(206); }
    private float arcRight() { return getWidth() - dp(26); }
    private float horizonY() { return getHeight() - dp(24); }
    private float arcHeight() { return dp(54); }
    private float xAt(float f) { return arcLeft() + f * (arcRight() - arcLeft()); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        tile.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f));
        shadersBuilt = false;
        buildArcs();
    }

    private void buildArcs() {
        dayArc.reset();
        nightArc.reset();
        if (getWidth() == 0) return;
        for (int i = 0; i <= SAMPLES; i++) {
            float f = (float) i / SAMPLES, x = xAt(f);
            // Day: the sun's real elevation curve. Night: a symbolic hump for the moon to travel.
            float yd = horizonY() - (float) (dayElev[i] / maxElev) * arcHeight();
            float yn = horizonY() - (float) Math.sin(Math.PI * f) * arcHeight() * 0.8f;
            if (i == 0) { dayArc.moveTo(x, yd); nightArc.moveTo(x, yn); } else { dayArc.lineTo(x, yd); nightArc.lineTo(x, yn); }
        }
    }

    private float yOn(boolean day, float f) {
        if (!day) return horizonY() - (float) Math.sin(Math.PI * f) * arcHeight() * 0.8f;
        float p = Math.max(0, Math.min(1, f)) * SAMPLES;
        int i = Math.min((int) p, SAMPLES - 1);
        double e = dayElev[i] + (dayElev[i + 1] - dayElev[i]) * (p - i);
        return horizonY() - (float) (e / maxElev) * arcHeight();
    }

    private void buildShaders(boolean day) {
        shadersBuilt = true;
        shadersForDay = day;
        float top = horizonY() - arcHeight();
        int a = day ? 0xFFFF9B54 : 0xFF7F9CD8, b = day ? 0xFFFFE3A1 : 0xFFDCE6FF;
        lit.setShader(new LinearGradient(arcLeft(), 0, arcRight(), 0, new int[]{a, b, a}, null, Shader.TileMode.CLAMP));
        int glowRgb = day ? 0xFFC06A : 0x9DB4E8;
        under.setShader(new LinearGradient(0, top, 0, horizonY(), glowRgb | 0x2E000000, glowRgb, Shader.TileMode.CLAMP));
        int haloRgb = day ? 0xFFC46A : 0xBFD0F5;
        halo.setShader(new RadialGradient(0, 0, dp(24), new int[]{haloRgb | 0xB3000000, haloRgb | 0x33000000, haloRgb},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        core.setColor(day ? 0xFFFFF5DC : 0xFFF2EEDC);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        if (Double.isNaN(lat)) return;
        long now = now();
        boolean day = isDay(now);
        if (!shadersBuilt || shadersForDay != day) buildShaders(day);
        c.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * reveal));

        float r = dp(22);
        c.drawRoundRect(tile, r, r, glass);
        c.drawRoundRect(tile, r, r, edge);

        // Left: the next event, big, with the countdown and the length of the day.
        Context ctx = getContext();
        long next = day ? set : (now < rise ? rise : nextRise);
        float tx = dp(22);
        c.drawText(ctx.getString(day ? R.string.sun_set : R.string.sun_rise).toUpperCase(Locale.getDefault()), tx, dp(30), caption);
        if (next > 0) {
            c.drawText(new SimpleDateFormat("H:mm", Locale.getDefault()).format(new Date(next)), tx - dp(1.5f), dp(65), big);
            c.drawText(ctx.getString(R.string.sun_in, span(next - now)), tx, dp(85), sub);
        } else {
            c.drawText(ctx.getString(maxElev > 1 ? R.string.sun_polar_day : R.string.sun_polar_night), tx, dp(65), sub);
        }
        if (rise > 0 && set > rise) {
            sub.setAlpha(0x73);
            c.drawText(ctx.getString(R.string.sun_daylight, span(set - rise)), tx, dp(101), sub);
            sub.setAlpha(0xB3);
        }

        // Right: horizon, a faint glow under the whole arc, the arc lit up to the marker and dotted after it.
        Path arc = day ? dayArc : nightArc;
        float hy = horizonY(), mx = xAt(shown), my = yOn(day, shown);
        c.drawLine(arcLeft() - dp(12), hy, arcRight() + dp(12), hy, horizon);
        c.save(); c.clipRect(mx, 0, getWidth(), hy + dp(2)); c.drawPath(arc, dots); c.restore();
        fill.set(arc);
        fill.lineTo(arcRight(), hy);
        fill.lineTo(arcLeft(), hy);
        fill.close();
        c.drawPath(fill, under);
        c.save(); c.clipRect(0, 0, mx, hy + dp(2)); c.drawPath(arc, lit); c.restore();

        // The marker: a glowing sun, or a crescent at night.
        c.save();
        c.translate(mx, my);
        c.drawCircle(0, 0, dp(24), halo);
        if (day) {
            c.drawCircle(0, 0, dp(7.5f), core);
        } else {
            float mr = dp(7.5f);
            moon.reset(); moon.addCircle(0, 0, mr, Path.Direction.CW);
            cut.reset(); cut.addCircle(mr * 0.6f, -mr * 0.4f, mr * 0.82f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            c.drawPath(moon, core);
        }
        c.restore();
        c.restore();
    }

    private String span(long ms) {
        long min = Math.max(0, (ms + 59_999) / 60_000);
        Context ctx = getContext();
        return min >= 60 ? ctx.getString(R.string.sun_span_hm, min / 60, min % 60) : ctx.getString(R.string.sun_span_m, min);
    }

    // ---------------------------------------------------------------- NOAA solar position

    /** Solar elevation in degrees at the given instant (geometric, without refraction). */
    static double elevation(double latDeg, double lonDeg, long utcMillis) {
        double jd = utcMillis / 86_400_000.0 + 2440587.5, t = (jd - 2451545.0) / 36525.0;
        double l0 = mod(280.46646 + t * (36000.76983 + t * 0.0003032), 360);
        double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double mr = Math.toRadians(m);
        double eqCenter = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + Math.sin(2 * mr) * (0.019993 - 0.000101 * t) + Math.sin(3 * mr) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double lambda = Math.toRadians(l0 + eqCenter - 0.00569 - 0.00478 * Math.sin(omega));
        double eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double eps = Math.toRadians(eps0 + 0.00256 * Math.cos(omega));
        double decl = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double y = Math.pow(Math.tan(eps / 2), 2), l0r = Math.toRadians(l0);
        double eqTime = 4 * Math.toDegrees(y * Math.sin(2 * l0r) - 2 * e * Math.sin(mr)
                + 4 * e * y * Math.sin(mr) * Math.cos(2 * l0r) - 0.5 * y * y * Math.sin(4 * l0r)
                - 1.25 * e * e * Math.sin(2 * mr)); // minutes
        double utcMinutes = mod(utcMillis / 60_000.0, 1440);
        double hourAngle = Math.toRadians(mod(utcMinutes + eqTime + 4 * lonDeg, 1440) / 4 - 180);
        double latR = Math.toRadians(latDeg);
        double cosZenith = Math.sin(latR) * Math.sin(decl) + Math.cos(latR) * Math.cos(decl) * Math.cos(hourAngle);
        return 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZenith))));
    }

    private static double mod(double a, double b) { return ((a % b) + b) % b; }

    private static long localMidnight(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long shiftDays(long midnight, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(midnight);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    private Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(width));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        return p;
    }

    private Paint text(float sp, int color, String family, float spacing) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        p.setTypeface(Typeface.create(family, Typeface.NORMAL));
        p.setLetterSpacing(spacing);
        return p;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
