package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Flat weather glyphs drawn in code, so they match the launcher's thin white style. */
final class WeatherIconView extends View {
    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

    private Kind kind = Kind.CLOUDY;
    private boolean day = true;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), tmp = new Path();

    WeatherIconView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(Kind kind, boolean day) {
        this.kind = kind;
        this.day = day;
        invalidate();
    }

    /** WMO weather interpretation codes, as returned by Open-Meteo. */
    static Kind kindOf(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if (code >= 51 && code <= 57) return Kind.DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return Kind.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.THUNDER;
        return Kind.CLOUDY;
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        line.setStrokeWidth(s * 0.075f);
        switch (kind) {
            case CLEAR:
                if (day) sun(c, cx, cy, s * 0.5f); else moon(c, cx, cy, s * 0.36f);
                break;
            case PARTLY:
                if (day) sun(c, cx - s * 0.16f, cy - s * 0.14f, s * 0.34f); else moon(c, cx - s * 0.14f, cy - s * 0.14f, s * 0.24f);
                cloud(c, cx + s * 0.08f, cy + s * 0.1f, s * 0.78f, 0xFFF2F5FA);
                break;
            case CLOUDY:
                cloud(c, cx + s * 0.12f, cy - s * 0.12f, s * 0.6f, 0x99C9D2DE);
                cloud(c, cx - s * 0.04f, cy + s * 0.08f, s * 0.84f, 0xFFF2F5FA);
                break;
            case FOG:
                cloud(c, cx, cy - s * 0.12f, s * 0.8f, 0xCCE3E8EF);
                line.setColor(0xCCE3E8EF);
                c.drawLine(cx - s * 0.36f, cy + s * 0.22f, cx + s * 0.3f, cy + s * 0.22f, line);
                c.drawLine(cx - s * 0.26f, cy + s * 0.38f, cx + s * 0.38f, cy + s * 0.38f, line);
                break;
            case DRIZZLE:
            case RAIN:
                cloud(c, cx, cy - s * 0.14f, s * 0.84f, 0xFFF2F5FA);
                line.setColor(0xFF8EC5FF);
                int drops = kind == Kind.RAIN ? 3 : 2;
                for (int i = 0; i < drops; i++) {
                    float x = cx - s * 0.2f + i * s * (drops == 3 ? 0.2f : 0.3f);
                    c.drawLine(x, cy + s * 0.2f, x - s * 0.07f, cy + s * (kind == Kind.RAIN ? 0.42f : 0.34f), line);
                }
                break;
            case SNOW:
                cloud(c, cx, cy - s * 0.14f, s * 0.84f, 0xFFF2F5FA);
                fill.setColor(0xFFFFFFFF);
                for (int i = 0; i < 3; i++) c.drawCircle(cx - s * 0.2f + i * s * 0.2f, cy + s * (i == 1 ? 0.38f : 0.28f), s * 0.05f, fill);
                break;
            case THUNDER:
                cloud(c, cx, cy - s * 0.14f, s * 0.84f, 0xFFDDE3EC);
                fill.setColor(0xFFFFD36B);
                path.reset();
                path.moveTo(cx + s * 0.02f, cy + s * 0.08f);
                path.lineTo(cx - s * 0.14f, cy + s * 0.3f);
                path.lineTo(cx - s * 0.01f, cy + s * 0.3f);
                path.lineTo(cx - s * 0.09f, cy + s * 0.48f);
                path.lineTo(cx + s * 0.14f, cy + s * 0.22f);
                path.lineTo(cx + s * 0.01f, cy + s * 0.22f);
                path.close();
                c.drawPath(path, fill);
                break;
        }
    }

    private void sun(Canvas c, float cx, float cy, float size) {
        fill.setColor(0xFFFFD36B);
        line.setColor(0xFFFFD36B);
        c.drawCircle(cx, cy, size * 0.42f, fill);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * size * 0.62f, cy + sin * size * 0.62f, cx + cos * size * 0.86f, cy + sin * size * 0.86f, line);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        tmp.reset();
        tmp.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        path.op(tmp, Path.Op.DIFFERENCE);
        fill.setColor(0xFFEDE6CC);
        c.drawPath(path, fill);
    }

    /** A cloud of width w centred on (cx, cy): three puffs over a rounded base. */
    private void cloud(Canvas c, float cx, float cy, float w, int color) {
        path.reset();
        path.addCircle(cx - w * 0.2f, cy + w * 0.02f, w * 0.18f, Path.Direction.CW);
        path.addCircle(cx + w * 0.02f, cy - w * 0.08f, w * 0.25f, Path.Direction.CW);
        path.addCircle(cx + w * 0.25f, cy + w * 0.04f, w * 0.16f, Path.Direction.CW);
        path.addRoundRect(new RectF(cx - w * 0.38f, cy, cx + w * 0.41f, cy + w * 0.2f), w * 0.1f, w * 0.1f, Path.Direction.CW);
        fill.setColor(color);
        c.drawPath(path, fill);
    }
}
