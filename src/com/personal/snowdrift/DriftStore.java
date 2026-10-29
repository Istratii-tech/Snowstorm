package com.personal.snowdrift;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сугробы каждого приложения: профиль высот (в dp) по COLS колонкам вдоль ширины экрана.
 * Массивы живут всё время работы службы — SnowView меняет их напрямую.
 */
final class DriftStore {
    static final int COLS = 96;
    private static final int VERSION = 1;

    private final File file;
    private final HashMap<String, float[]> map = new HashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    DriftStore(Context c) {
        file = new File(c.getFilesDir(), "drifts.bin");
    }

    float[] get(String pkg) {
        float[] a = map.get(pkg);
        if (a == null) {
            a = new float[COLS];
            map.put(pkg, a);
        }
        return a;
    }

    void clearAll() {
        for (float[] a : map.values()) Arrays.fill(a, 0f);
        saveAsync();
    }

    void load() {
        if (!file.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != VERSION) return;
            int count = in.readInt();
            for (int k = 0; k < count; k++) {
                String pkg = in.readUTF();
                int n = in.readInt();
                float[] a = new float[COLS];
                for (int i = 0; i < n; i++) {
                    float v = in.readFloat();
                    if (i < COLS) a[i] = v;
                }
                map.put(pkg, a);
            }
        } catch (Exception ignored) {
            // повреждённый файл — начинаем без сугробов
        }
    }

    /** Снимок на главном потоке, запись — в фоне. Пустые сугробы не сохраняем. */
    void saveAsync() {
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<float[]> data = new ArrayList<>();
        for (Map.Entry<String, float[]> e : map.entrySet()) {
            float[] a = e.getValue();
            boolean any = false;
            for (float v : a) if (v > 0.05f) { any = true; break; }
            if (!any) continue;
            names.add(e.getKey());
            data.add(a.clone());
        }
        io.execute(() -> write(names, data));
    }

    private void write(ArrayList<String> names, ArrayList<float[]> data) {
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(VERSION);
            out.writeInt(names.size());
            for (int k = 0; k < names.size(); k++) {
                out.writeUTF(names.get(k));
                float[] a = data.get(k);
                out.writeInt(a.length);
                for (float v : a) out.writeFloat(v);
            }
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(file);
    }

    /**
     * Форма сугробов конкретного приложения: доля от максимальной высоты для каждой колонки
     * (0.35..1). Зависит только от имени пакета, поэтому у каждого приложения свои «холмики».
     */
    static float[] shapeFor(String pkg) {
        java.util.Random r = new java.util.Random(pkg == null ? 0 : pkg.hashCode() * 2654435761L);
        float f1 = 0.8f + r.nextFloat() * 1.2f, f2 = 2.2f + r.nextFloat() * 2f, f3 = 5f + r.nextFloat() * 3f;
        float p1 = r.nextFloat(), p2 = r.nextFloat(), p3 = r.nextFloat();
        float[] s = new float[COLS];
        double tau = Math.PI * 2;
        for (int i = 0; i < COLS; i++) {
            float x = i / (float) (COLS - 1);
            double v = 0.55 * Math.sin(tau * (f1 * x + p1))
                    + 0.30 * Math.sin(tau * (f2 * x + p2))
                    + 0.15 * Math.sin(tau * (f3 * x + p3));
            s[i] = (float) (0.35 + 0.65 * (0.5 + 0.5 * v));
        }
        return s;
    }
}
