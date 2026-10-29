package com.personal.snowdrift;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Полноэкранный прозрачный слой: падающий снег, сугроб активного приложения,
 * «поднятый» сугроб на экране недавних и снежные комья при встряхивании.
 * Сугробы — профили высот в dp из DriftStore, слой меняет их напрямую.
 */
public class SnowView extends View {
    private static final int MAX = 900;
    private static final int MAXP = 1600;
    private static final int COLS = DriftStore.COLS;
    private static final float[] SPREAD = {0.12f, 0.22f, 0.32f, 0.22f, 0.12f};
    /** Сугроб «утоплен» под нижний край на столько dp: первая линия выползает снизу, а не рисуется сразу. */
    private static final float SINK = 3f;
    /** За сколько dp видимой высоты сугроб проявляется из прозрачного. */
    private static final float FADE_IN = 8f;

    /** Сколько видов снежинок в ходу, сколько меняем за раз и как часто. */
    private static final long FRAME_60 = 16_666_667L, FRAME_30 = 33_333_333L;
    private static final int KINDS_LIVE = 10, KINDS_SWAP = 5;
    private static final float KINDS_EVERY_SEC = 20f;
    /**
     * Все картинки снежинок лежат в одном атласе (одна текстура на GPU — снежинки рисуются
     * одной пачкой). Ячейка 0 — круглая снежинка, остальные — сгенерированные виды.
     */
    private static final int ATLAS_COLS = 6, ATLAS_ROWS = 4, CELLS = ATLAS_COLS * ATLAS_ROWS;
    private static final int CELL = FlakeGen.SIZE + 4; // +2px прозрачного поля с каждой стороны


    /** Один отрисовываемый сугроб. */
    private static final class Slot {
        final float[] h;
        final float[] shape;
        float alpha, targetAlpha = 1f;

        Slot(float[] h, float[] shape) { this.h = h; this.shape = shape; }
    }

    private final float d;
    private final Random rnd = new Random();

    // снежинки (структура массивов, чтобы не плодить объекты)
    private final float[] fx = new float[MAX], fy = new float[MAX], fz = new float[MAX], fr = new float[MAX];
    private final float[] fv = new float[MAX], fph = new float[MAX], fps = new float[MAX];
    private final float[] frot = new float[MAX], frs = new float[MAX];
    private final byte[] fcell = new byte[MAX]; // ячейка атласа; 0 — простая круглая
    private int n, target = 120;

    // комья стряхнутого снега
    private final float[] px = new float[MAXP], py = new float[MAXP], pvx = new float[MAXP], pvy = new float[MAXP];
    private final float[] pr = new float[MAXP], plife = new float[MAXP], pmax = new float[MAXP];
    private int np;

    private float speed = 1f, driftMax = 62f, wind, windDir = 1f, t;
    private boolean storm, suppressed;
    private int w, hgt;
    private float baseY;

    private Slot active, fading;
    private boolean changed;

    private boolean running;
    private long lastNanos, nextDue, frameInterval = FRAME_60;

    private final Bitmap atlas = Bitmap.createBitmap(ATLAS_COLS * CELL, ATLAS_ROWS * CELL, Bitmap.Config.ARGB_8888);
    private final Canvas atlasCanvas = new Canvas(atlas);
    private final android.graphics.Rect[] cellSrc = new android.graphics.Rect[CELLS];
    private final int[] cellRefs = new int[CELLS];          // сколько летящих снежинок носят этот вид
    private final ArrayList<Integer> live = new ArrayList<>(); // ячейки, которые выдаются новым снежинкам
    private final Paint clearPaint = new Paint();
    private final ExecutorService gen = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private float nextKindsAt = KINDS_EVERY_SEC;
    private boolean kindsPending;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint driftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint partPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fill = new Path(), edge = new Path();
    private final RectF rect = new RectF();
    private final int[] spCol = new int[16];
    private final float[] spOff = new float[16], spPh = new float[16];

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!running) return;
            Choreographer ch = Choreographer.getInstance();
            // Дисплей S24 сам меняет частоту (до 120 Гц), поэтому кадры идут по своему графику:
            // ровно 60 (или 30) в секунду, а между ними поток спит, а не просыпается на каждый vsync.
            if (lastNanos != 0 && now < nextDue - 4_000_000L) {
                ch.postFrameCallback(this);
                return;
            }
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            nextDue = Math.max(nextDue + frameInterval, now + frameInterval / 2);
            step(dt);
            invalidate();
            long waitMs = (nextDue - now) / 1_000_000L - 6;
            if (waitMs > 0) ch.postFrameCallbackDelayed(this, waitMs);
            else ch.postFrameCallback(this);
        }
    };

    public SnowView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < CELLS; i++) {
            int x = (i % ATLAS_COLS) * CELL + 2, y = (i / ATLAS_COLS) * CELL + 2;
            cellSrc[i] = new android.graphics.Rect(x, y, x + FlakeGen.SIZE, y + FlakeGen.SIZE);
        }
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        putCell(0, makeDot());
        for (int i = 1; i <= KINDS_LIVE; i++) {
            putCell(i, FlakeGen.make(rnd));
            live.add(i);
        }

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.6f * d);
        edgePaint.setColor(0xFFFFFFFF);
        shadePaint.setStyle(Paint.Style.STROKE);
        shadePaint.setStrokeWidth(7f * d);
        shadePaint.setColor(0xFF9DBCE0);
        sparkPaint.setColor(0xFFFFFFFF);
        sparkPaint.setStrokeWidth(1.1f * d);
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
        partPaint.setColor(0xFFF7FAFF);

        for (int i = 0; i < spCol.length; i++) {
            spCol[i] = 2 + rnd.nextInt(COLS - 4);
            spOff[i] = 1.5f + rnd.nextFloat() * 9f;
            spPh[i] = rnd.nextFloat() * 6.283f;
        }
    }

    // ---------------------------------------------------------------- управление

    void setParams(float speedFactor, int count, boolean storm, float driftMaxDp) {
        if (storm && !this.storm) windDir = rnd.nextBoolean() ? 1f : -1f;
        this.storm = storm;
        this.speed = speedFactor;
        this.driftMax = driftMaxDp;
        target = storm ? Math.min(MAX, Math.max(350, Math.round(count * 2.5f))) : Math.min(MAX, count);
        if (w > 0) {
            for (int i = n; i < target; i++) {
                spawn(i, false);
                fy[i] -= rnd.nextFloat() * hgt * 0.6f; // заходят постепенно, а не стеной
            }
            if (target > n) n = target;
            makeShader();
        }
    }

    /** Экономный режим: 30 к/с вместо 60. */
    void setEco(boolean eco) {
        frameInterval = eco ? FRAME_30 : FRAME_60;
    }

    void setRunning(boolean r) {
        if (r == running) return;
        running = r;
        Choreographer ch = Choreographer.getInstance();
        if (r) {
            lastNanos = 0;
            nextDue = 0;
            ch.postFrameCallback(frame);
        } else {
            ch.removeFrameCallback(frame);
        }
    }

    /** Новое активное приложение (h == null — сугроба нет: лаунчер, экран блокировки). */
    void setActive(float[] h, String pkg) {
        if (active != null && active.h == h) return;
        fadeOut(active);
        active = h == null ? null : new Slot(h, DriftStore.shapeFor(pkg));
    }

    boolean crumbleActive() {
        return active != null && crumble(active);
    }

    /** Шторка уведомлений открыта: сугроб приложения прячется и не растёт. */
    void setSuppressed(boolean s) {
        suppressed = s;
    }

    boolean consumeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    private void fadeOut(Slot s) {
        if (s == null) return;
        s.targetAlpha = 0f;
        fading = s;
    }

    // ---------------------------------------------------------------- размеры

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        if (ow > 0) {
            for (int i = 0; i < n; i++) { fx[i] *= nw / (float) ow; fy[i] *= nh / (float) oh; }
        }
        w = nw;
        hgt = nh;
        updateBase();
        if (n == 0) {
            n = target;
            for (int i = 0; i < n; i++) spawn(i, true);
        }
        makeShader();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        updateBase();
        return super.onApplyWindowInsets(in);
    }

    /** Низ сугроба — над панелью навигации, т.е. по нижнему краю окна приложения. */
    private void updateBase() {
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets wi = getRootWindowInsets();
            if (wi != null) nav = wi.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            if (nav == 0) {
                WindowManager wm = getContext().getSystemService(WindowManager.class);
                if (wm != null) {
                    nav = wm.getCurrentWindowMetrics().getWindowInsets()
                            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
                }
            }
        } else if (hgt > getWidth()) {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) nav = getResources().getDimensionPixelSize(id);
        }
        baseY = hgt - nav;
    }

    private void makeShader() {
        if (hgt == 0) return;
        driftPaint.setShader(new LinearGradient(0, baseY - driftMax * d, 0, baseY,
                0xFFFFFFFF, 0xFFD3E2F3, Shader.TileMode.CLAMP));
    }

    // ---------------------------------------------------------------- физика

    private void spawn(int i, boolean anywhere) {
        float z = (float) Math.pow(rnd.nextFloat(), 1.4);
        fz[i] = z;
        boolean star = z > 0.45f && rnd.nextFloat() < 0.28f;
        cellRefs[fcell[i]]--;
        int cell = star ? live.get(rnd.nextInt(live.size())) : 0;
        fcell[i] = (byte) cell;
        cellRefs[cell]++;
        float r = star ? 3.5f + 4.5f * z : 0.9f + 2.6f * (float) Math.pow(z, 1.3);
        if (storm) r *= 0.85f;
        fr[i] = r * d;
        fv[i] = 0.8f + rnd.nextFloat() * 0.4f;
        fph[i] = rnd.nextFloat() * 6.283f;
        fps[i] = 0.6f + rnd.nextFloat() * 1.6f;
        frot[i] = rnd.nextFloat() * 360f;
        frs[i] = (rnd.nextFloat() - 0.5f) * 90f;
        fx[i] = rnd.nextFloat() * w;
        fy[i] = anywhere ? rnd.nextFloat() * hgt : -fr[i] - rnd.nextFloat() * hgt * 0.12f;
    }

    private void removeFlake(int i) {
        cellRefs[fcell[i]]--;
        int last = --n;
        fx[i] = fx[last]; fy[i] = fy[last]; fz[i] = fz[last]; fr[i] = fr[last]; fv[i] = fv[last];
        fph[i] = fph[last]; fps[i] = fps[last]; frot[i] = frot[last]; frs[i] = frs[last]; fcell[i] = fcell[last];
    }

    private void step(float dt) {
        if (w == 0) return;
        t += dt;
        if (!kindsPending && t >= nextKindsAt) renewKinds();
        float fall = 62f * d * speed * (storm ? 1.9f : 1f);
        float wTarget = storm
                ? windDir * fall * (1.15f + 0.45f * (float) Math.sin(t * 0.6f) + 0.25f * (float) Math.sin(t * 1.7f + 1.3f))
                : fall * (0.16f * (float) Math.sin(t * 0.11f) + 0.07f * (float) Math.sin(t * 0.37f + 2f));
        wind += (wTarget - wind) * Math.min(1f, dt * 1.5f);

        if (active != null) active.targetAlpha = suppressed ? 0f : 1f;
        boolean land = active != null && !suppressed && active.alpha > 0.6f;
        float colW = w / (float) (COLS - 1);
        float swayBase = (storm ? 6f : 14f) * d;

        for (int i = n - 1; i >= 0; i--) {
            float z = fz[i];
            fph[i] += fps[i] * dt;
            fx[i] += (wind * (0.35f + 0.65f * z) + (float) Math.cos(fph[i]) * swayBase * (0.4f + z)) * dt;
            fy[i] += fall * (0.45f + 0.8f * z) * fv[i] * dt;
            frot[i] += frs[i] * dt;
            float m = fr[i] + 4f * d;
            if (fx[i] < -m) fx[i] += w + 2 * m;
            else if (fx[i] > w + m) fx[i] -= w + 2 * m;

            boolean gone = false;
            if (land && fx[i] >= 0 && fx[i] < w) {
                float fc = fx[i] / colW;
                if (fy[i] >= baseY - Math.max(0f, heightAt(active.h, fc) - SINK) * d - fr[i] * 0.3f) {
                    deposit(fc, fr[i] / d);
                    gone = true;
                }
            }
            if (!gone && fy[i] - fr[i] > hgt) gone = true;
            if (gone) {
                if (i >= target) removeFlake(i);
                else spawn(i, false);
            }
        }
        if (land) relax(active.h, colW / d);

        animate(active, dt);
        if (fading != null) {
            animate(fading, dt);
            if (fading.alpha < 0.01f) fading = null;
        }

        float g = 1300f * d;
        for (int i = np - 1; i >= 0; i--) {
            pvy[i] += g * dt;
            pvx[i] *= 1f - 0.6f * dt;
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            plife[i] -= dt;
            if (plife[i] <= 0 || py[i] > hgt + 20 * d) {
                int l = --np;
                px[i] = px[l]; py[i] = py[l]; pvx[i] = pvx[l]; pvy[i] = pvy[l];
                pr[i] = pr[l]; plife[i] = plife[l]; pmax[i] = pmax[l];
            }
        }
    }

    private static void animate(Slot s, float dt) {
        if (s == null) return;
        s.alpha += (s.targetAlpha - s.alpha) * Math.min(1f, dt * 5f);
    }

    /**
     * Смена видов: в фоне рисуем KINDS_SWAP новых, кладём их в свободные ячейки атласа,
     * а столько же самых старых перестают выдаваться новым снежинкам. Уже летящие доживают
     * до земли — виды меняются постепенно. Ячейка освобождается, когда упадёт последняя
     * снежинка её вида; если свободных ячеек пока нет (очень медленный снег) — пробуем позже.
     */
    private void renewKinds() {
        kindsPending = true;
        final long seed = rnd.nextLong();
        gen.execute(() -> {
            Random r = new Random(seed);
            final Bitmap[] fresh = new Bitmap[KINDS_SWAP];
            for (int i = 0; i < fresh.length; i++) fresh[i] = FlakeGen.make(r);
            main.post(() -> {
                kindsPending = false;
                ArrayList<Integer> free = new ArrayList<>();
                for (int c = 1; c < CELLS && free.size() < KINDS_SWAP; c++) {
                    if (cellRefs[c] <= 0 && !live.contains(c)) free.add(c);
                }
                if (free.size() < KINDS_SWAP) {
                    nextKindsAt = t + 5f;
                    return;
                }
                for (int i = 0; i < KINDS_SWAP; i++) live.remove(0);
                for (int i = 0; i < KINDS_SWAP; i++) {
                    putCell(free.get(i), fresh[i]);
                    live.add(free.get(i));
                }
                nextKindsAt = t + KINDS_EVERY_SEC;
            });
        });
    }

    private static float heightAt(float[] h, float fc) {
        int i = (int) fc;
        if (i < 0) return h[0];
        if (i >= COLS - 1) return h[COLS - 1];
        float f = fc - i;
        return h[i] * (1 - f) + h[i + 1] * f;
    }

    /** Снежинка легла: добавляем снег в несколько соседних колонок, не выше «потолка» этой колонки. */
    private void deposit(float fc, float rDp) {
        float[] h = active.h, shape = active.shape;
        int c = Math.round(fc);
        float amt = (0.45f + rDp * rDp * 0.09f) * 1.6f;
        for (int k = 0; k < SPREAD.length; k++) {
            int idx = c + k - 2;
            if (idx < 0 || idx >= COLS) continue;
            float cap = driftMax * shape[idx];
            if (h[idx] >= cap) continue;
            float add = amt * SPREAD[k] * (0.4f + 0.6f * shape[idx]) * (0.3f + 0.7f * (1f - h[idx] / cap));
            h[idx] = Math.min(cap, h[idx] + add);
        }
        changed = true;
    }

    /** Осыпание: слишком крутые склоны расползаются, получаются округлые холмики. */
    private static void relax(float[] h, float colWdp) {
        float ms = colWdp * 0.6f;
        for (int i = 0; i < COLS - 1; i++) {
            float diff = h[i] - h[i + 1];
            if (diff > ms) {
                float tr = (diff - ms) * 0.25f;
                h[i] -= tr;
                h[i + 1] += tr;
            } else if (diff < -ms) {
                float tr = (-diff - ms) * 0.25f;
                h[i + 1] -= tr;
                h[i] += tr;
            }
        }
    }

    private boolean crumble(Slot s) {
        float[] h = s.h;
        float colW = w / (float) (COLS - 1);
        float pivX = w / 2f;
        boolean any = false;
        for (int c = 0; c < COLS; c++) {
            float vis = h[c] - SINK;
            if (vis < 0.8f) continue;
            any = true;
            int k = Math.min(5, 1 + (int) (vis / 7f));
            for (int j = 0; j < k && np < MAXP; j++) {
                float x = c * colW + (rnd.nextFloat() - 0.5f) * colW;
                float y = baseY - rnd.nextFloat() * vis * d;
                int i = np++;
                px[i] = x;
                py[i] = y;
                pvx[i] = (rnd.nextFloat() - 0.5f) * 320f * d + (px[i] - pivX) * 0.25f;
                pvy[i] = -(120f + rnd.nextFloat() * 420f) * d * (0.6f + 0.4f * Math.min(1f, h[c] / driftMax));
                pr[i] = (1.2f + rnd.nextFloat() * 3.2f) * d;
                pmax[i] = plife[i] = 1.1f + rnd.nextFloat() * 0.9f;
            }
        }
        Arrays.fill(h, 0f);
        changed = true;
        return any;
    }

    // ---------------------------------------------------------------- отрисовка

    @Override
    protected void onDraw(Canvas c) {
        if (w == 0) return;
        drawSlot(c, fading, false);
        drawSlot(c, active, true);

        for (int i = 0; i < n; i++) {
            float r = fr[i], x = fx[i], y = fy[i];
            bmpPaint.setAlpha((int) (255 * (0.5f + 0.5f * fz[i])));
            rect.set(x - r, y - r, x + r, y + r);
            if (fcell[i] == 0) {
                c.drawBitmap(atlas, cellSrc[0], rect, bmpPaint);
            } else {
                c.save();
                c.rotate(frot[i], x, y);
                c.drawBitmap(atlas, cellSrc[fcell[i]], rect, bmpPaint);
                c.restore();
            }
        }

        for (int i = 0; i < np; i++) {
            partPaint.setAlpha((int) (255 * Math.min(1f, plife[i] / pmax[i] * 1.6f)));
            c.drawCircle(px[i], py[i], pr[i], partPaint);
        }
    }

    private void drawSlot(Canvas c, Slot s, boolean sparkle) {
        if (s == null || s.alpha < 0.01f) return;
        float[] h = s.h;
        float max = 0;
        for (float v : h) if (v > max) max = v;
        if (max <= SINK + 0.2f) return;
        float grow = Math.min(1f, (max - SINK) / FADE_IN);
        float alpha = s.alpha * grow * grow;

        float colW = w / (float) (COLS - 1);
        edge.rewind();
        float px0 = 0, py0 = baseY - (h[0] - SINK) * d;
        edge.moveTo(px0, py0);
        for (int i = 1; i < COLS; i++) {
            float x = i * colW, y = baseY - (h[i] - SINK) * d;
            edge.quadTo(px0, py0, (px0 + x) / 2f, (py0 + y) / 2f);
            px0 = x;
            py0 = y;
        }
        edge.lineTo(px0, py0);
        fill.set(edge);
        fill.lineTo(w, baseY + 1);
        fill.lineTo(0, baseY + 1);
        fill.close();

        c.save();
        c.clipRect(0, 0, w, baseY); // утопленная часть не видна
        driftPaint.setAlpha((int) (250 * alpha));
        c.drawPath(fill, driftPaint);

        c.save();
        c.clipPath(fill);
        c.translate(0, 4.5f * d);
        shadePaint.setAlpha((int) (60 * alpha));
        c.drawPath(edge, shadePaint);
        c.restore();

        edgePaint.setAlpha((int) (235 * alpha));
        c.drawPath(edge, edgePaint);

        if (sparkle) {
            float r = 2.6f * d;
            for (int i = 0; i < spCol.length; i++) {
                float hc = h[spCol[i]] - SINK;
                if (hc < 6f) continue;
                float a = (float) Math.sin(t * 1.7f + spPh[i]);
                if (a <= 0) continue;
                a = a * a * a * a * a * a;
                float x = spCol[i] * colW;
                float y = baseY - (hc - Math.min(spOff[i], hc - 2f)) * d;
                sparkPaint.setAlpha((int) (255 * a * alpha));
                c.drawLine(x - r, y, x + r, y, sparkPaint);
                c.drawLine(x, y - r, x, y + r, sparkPaint);
            }
        }
        c.restore();
    }

    // ---------------------------------------------------------------- картинки снежинок

    private void putCell(int cell, Bitmap b) {
        android.graphics.Rect r = cellSrc[cell];
        atlasCanvas.drawRect(r.left - 2, r.top - 2, r.right + 2, r.bottom + 2, clearPaint);
        atlasCanvas.drawBitmap(b, null, r, null);
        b.recycle();
    }

    private static Bitmap makeDot() {
        int s = FlakeGen.SIZE;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{0xFFFFFFFF, 0xF0FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return b;
    }
}
