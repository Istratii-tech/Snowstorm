package com.personal.snowdrift;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Random;

/**
 * Генератор уникальных снежинок. Как у настоящих кристаллов: шесть одинаковых лучей,
 * каждый зеркально симметричен; веточки отходят под ~60°. Случайно выбираются толщина,
 * число и длина веточек, «под-веточки», пластинки-сектора, центр и кончики лучей.
 */
final class FlakeGen {
    static final int SIZE = 72;

    private FlakeGen() {}

    static Bitmap make(Random r) {
        float R = SIZE / 2f - 4f;
        Path lines = new Path();  // штрихи одного луча (уже с зеркальной половиной)
        Path fills = new Path();  // залитые части одного луча

        float arm = R * (0.82f + r.nextFloat() * 0.18f);
        float width = 1.5f + r.nextFloat() * 2.2f;
        lines.moveTo(0, 0);
        lines.lineTo(0, -arm);

        // веточки: ближе к кончику — короче
        int branches = r.nextInt(5);
        float angle = (float) Math.toRadians(52 + r.nextFloat() * 16);
        boolean subBranches = r.nextFloat() < 0.4f;
        float lenBase = R * (0.22f + r.nextFloat() * 0.3f);
        for (int b = 0; b < branches; b++) {
            float pos = arm * (0.25f + 0.6f * (b + r.nextFloat() * 0.6f) / Math.max(1, branches));
            float taper = 1f - 0.55f * pos / arm;
            float len = lenBase * taper * (0.7f + r.nextFloat() * 0.5f);
            float ex = (float) Math.sin(angle) * len, ey = -pos - (float) Math.cos(angle) * len;
            float k = fitInside(ex, ey, R);
            ex *= k;
            ey *= k;
            mirrored(lines, 0, -pos, ex, ey);
            if (subBranches && len > R * 0.18f) {
                // маленькие отростки на веточке, параллельно лучу
                float mx = ex * 0.55f, my = -pos + (ey + pos) * 0.55f, sl = len * 0.35f;
                mirrored(lines, mx, my, mx, my - sl);
            }
        }

        // сектор-пластинка вдоль луча
        if (r.nextFloat() < 0.3f) {
            float p1 = arm * (0.3f + r.nextFloat() * 0.25f), p2 = arm * (0.65f + r.nextFloat() * 0.25f);
            float sw = R * (0.08f + r.nextFloat() * 0.1f);
            fills.moveTo(0, 0);
            fills.lineTo(sw, -p1);
            fills.lineTo(0, -p2);
            fills.lineTo(-sw, -p1);
            fills.close();
        }

        // кончик луча
        switch (r.nextInt(4)) {
            case 1: { // вилка
                float fl = R * 0.14f;
                mirrored(lines, 0, -arm * 0.88f, fl * 0.6f, -arm * 0.88f - fl);
                break;
            }
            case 2: // кружок
                fills.addCircle(0, -arm, width * (0.9f + r.nextFloat() * 0.6f), Path.Direction.CW);
                break;
            case 3: // маленький шестиугольник
                hexagon(lines, 0, -arm + R * 0.07f, R * 0.08f);
                break;
            default:
                break;
        }

        Path all = new Path(), allFill = new Path();
        Matrix m = new Matrix();
        float c = SIZE / 2f;
        for (int i = 0; i < 6; i++) {
            m.setRotate(i * 60f);
            m.postTranslate(c, c);
            all.addPath(lines, m);
            allFill.addPath(fills, m);
        }

        // центр: шестиугольник-пластинка, кольцо или ничего
        int center = r.nextInt(3);
        float cr = R * (0.12f + r.nextFloat() * 0.18f);
        Path centerPath = new Path();
        hexagon(centerPath, c, c, cr);

        int color = r.nextFloat() < 0.5f ? 0xFFFFFFFF : (r.nextBoolean() ? 0xFFEAF4FF : 0xFFDDEBFF);
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        // мягкое свечение под кристаллом
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(color);
        glow.setAlpha(70);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(width * 2.4f);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(SIZE * 0.04f, BlurMaskFilter.Blur.NORMAL));
        cv.drawPath(all, glow);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(width);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        cv.drawPath(all, stroke);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setAlpha(190);
        cv.drawPath(allFill, fill);

        if (center == 0) {
            fill.setAlpha(220);
            cv.drawPath(centerPath, fill);
        } else if (center == 1) {
            stroke.setStrokeWidth(Math.max(1.2f, width * 0.8f));
            cv.drawPath(centerPath, stroke);
        } else {
            fill.setAlpha(255);
            cv.drawCircle(c, c, width * 1.3f, fill);
        }
        return bmp;
    }

    /** Отрезок и его отражение относительно оси луча. */
    private static void mirrored(Path p, float x0, float y0, float x1, float y1) {
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        p.moveTo(-x0, y0);
        p.lineTo(-x1, y1);
    }

    private static void hexagon(Path p, float cx, float cy, float rad) {
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(30 + 60 * i);
            float x = cx + (float) Math.cos(a) * rad, y = cy + (float) Math.sin(a) * rad;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
    }

    private static float fitInside(float x, float y, float R) {
        float d = (float) Math.sqrt(x * x + y * y);
        return d > R ? R / d : 1f;
    }
}
