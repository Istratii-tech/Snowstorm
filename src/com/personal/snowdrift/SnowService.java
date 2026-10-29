package com.personal.snowdrift;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Служба спец. возможностей. Держит слой со снегом поверх всех окон, следит, какое
 * приложение сверху (у каждого свой сугроб), ловит встряхивание и держит уведомление.
 */
public class SnowService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {

    static final String CHANNEL = "snow";
    static final int NOTIF_ID = 1;
    private static final long SWEEP_HOLD_MS = 3000;
    static SnowService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable resolveRunnable = this::resolveForeground;
    private final Runnable saveTick = new Runnable() {
        @Override
        public void run() {
            saveIfChanged();
            handler.postDelayed(this, 20_000);
        }
    };

    private SharedPreferences prefs;
    private DriftStore store;
    private WindowManager wm;
    private SnowView view;
    private SensorManager sensors;
    private Sensor accel;
    private boolean sensorOn, screenOn = true;
    private final HashSet<String> launchers = new HashSet<>();

    private String fgPkg;       // верхнее окно приложения (может быть лаунчер)
    private String currentPkg;  // приложение, чей сугроб сейчас растёт
    private String lastEventPkg;
    private String sweptPkg;    // приложение, которое только что смахнули вверх
    private long sweptAt;

    // встряхивание
    private int shakeHits;
    private long firstHit, lastHit, lastShake;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOn = false;
                saveIfChanged();
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOn = true;
            }
            applyRunState();
            scheduleResolve(300);
        }
    };

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = Prefs.get(this);
        store = new DriftStore(this);
        store.load();
        wm = getSystemService(WindowManager.class);
        sensors = getSystemService(SensorManager.class);
        accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        findLaunchers();

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        try {
            // Foreground-статус нужен, чтобы акселерометр работал, пока мы «в фоне».
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            nm.notify(NOTIF_ID, buildNotification());
        }

        view = new SnowView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setFitInsetsTypes(0);
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        lp.setTitle("Snow");
        wm.addView(view, lp);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);

        prefs.registerOnSharedPreferenceChangeListener(this);
        applyPrefs();
        handler.postDelayed(saveTick, 20_000);
        scheduleResolve(0);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (instance != this) return;
        instance = null;
        handler.removeCallbacksAndMessages(null);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        setSensor(false);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (view != null) {
            view.setRunning(false);
            try { wm.removeView(view); } catch (Exception ignored) {}
            view = null;
        }
        store.saveAsync();
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // Сменили язык приложения/системы — переписываем уведомление и имя канала.
        if (instance != this) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW));
        postNotification();
    }

    // ---------------------------------------------------------------- настройки

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        applyPrefs();
    }

    private void applyPrefs() {
        view.setEco(Prefs.eco(prefs));
        view.setParams(Prefs.speedFactor(Prefs.speed(prefs)), Prefs.flakeCount(Prefs.intensity(prefs)),
                Prefs.storm(prefs), Prefs.driftMaxDp(Prefs.drift(prefs)));
        applyRunState();
        postNotification();
    }

    private void applyRunState() {
        if (view == null) return;
        boolean on = Prefs.enabled(prefs);
        view.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        view.setRunning(on && screenOn);
        setSensor(on && screenOn);
    }

    void clearAllDrifts() {
        if (view != null) view.crumbleActive();
        store.clearAll();
    }

    // ---------------------------------------------------------------- уведомление

    void postNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        boolean on = Prefs.enabled(prefs);
        int imm = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent toggle = PendingIntent.getBroadcast(this, 1,
                new Intent(this, ToggleReceiver.class).setAction(ToggleReceiver.ACTION_TOGGLE), imm);
        PendingIntent repost = PendingIntent.getBroadcast(this, 2,
                new Intent(this, ToggleReceiver.class).setAction(ToggleReceiver.ACTION_REPOST), imm);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), imm);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_snow);

        int text;
        if (!on) text = R.string.notif_text_off;
        else if (Prefs.storm(prefs)) text = R.string.notif_text_storm;
        else text = R.string.notif_text_on;

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_snow)
                .setContentTitle(getString(on ? R.string.notif_title_on : R.string.notif_title_off))
                .setContentText(getString(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setDeleteIntent(repost)
                .addAction(new Notification.Action.Builder(icon, getString(on ? R.string.notif_stop : R.string.notif_start), toggle).build())
                .addAction(new Notification.Action.Builder(icon, getString(R.string.notif_settings), open).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- какое приложение сверху

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int type = e.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null
                && e.getClassName() != null && isActivity(e.getPackageName().toString(), e.getClassName().toString())) {
            lastEventPkg = e.getPackageName().toString();
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null
                && launchers.contains(e.getPackageName().toString())) {
            // Лаунчер становится видимым в самом начале свайпа вверх, а список окон
            // обновится только после отпускания пальца — реагируем сразу.
            handler.removeCallbacks(resolveRunnable);
            setForeground(e.getPackageName().toString());
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleResolve(120);
        }
    }

    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    private void scheduleResolve(long delay) {
        handler.removeCallbacks(resolveRunnable);
        handler.postDelayed(resolveRunnable, delay);
    }

    /** Верхнее (по слою) обычное окно приложения, не картинка-в-картинке. */
    private void resolveForeground() {
        if (view == null || !screenOn) return;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            setForeground(null);
            return;
        }
        String pkg = null;
        boolean shade = false;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            AccessibilityWindowInfo best = null;
            android.graphics.Rect r = new android.graphics.Rect();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    // Открытая шторка уведомлений — системное окно почти во весь экран.
                    w.getBoundsInScreen(r);
                    if (r.height() > view.getHeight() * 0.6f) shade = true;
                }
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
                if (best == null || w.getLayer() > best.getLayer()) best = w;
            }
            if (best != null) {
                AccessibilityNodeInfo root = best.getRoot();
                if (root != null && root.getPackageName() != null) pkg = root.getPackageName().toString();
            }
        } catch (Exception ignored) {}
        view.setSuppressed(shade);
        if (pkg == null) pkg = lastEventPkg;
        if (pkg != null) setForeground(pkg);
    }

    private void setForeground(String pkg) {
        // Пока палец ещё ведёт окно вверх, список окон всё ещё показывает смахнутое приложение —
        // не возвращаем ему сугроб, иначе внизу на миг снова появляется полоска снега.
        long since = SystemClock.uptimeMillis() - sweptAt;
        if (pkg != null && pkg.equals(sweptPkg) && since < SWEEP_HOLD_MS) {
            scheduleResolve(SWEEP_HOLD_MS - since + 50);
            return;
        }
        if (Objects.equals(pkg, fgPkg)) return;
        fgPkg = pkg;
        if (pkg != null && launchers.contains(pkg)) {
            // Смахнули приложение вверх / открыли «Недавние»: сугроб разлетается, как при встряхивании.
            if (currentPkg != null) {
                sweptPkg = currentPkg;
                sweptAt = SystemClock.uptimeMillis();
            }
            view.crumbleActive();
            currentPkg = null;
            view.setActive(null, null);
        } else {
            currentPkg = pkg;
            view.setActive(pkg == null ? null : store.get(pkg), pkg);
        }
        saveIfChanged();
    }

    private void findLaunchers() {
        launchers.clear();
        PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
            launchers.add(def.activityInfo.packageName);
            return;
        }
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            String p = ri.activityInfo.packageName;
            if (!"com.android.settings".equals(p)) launchers.add(p);
        }
    }

    private void saveIfChanged() {
        if (view != null && view.consumeChanged()) store.saveAsync();
    }

    // ---------------------------------------------------------------- встряхивание

    private void setSensor(boolean on) {
        if (accel == null || on == sensorOn) return;
        sensorOn = on;
        if (on) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        else sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double a = Math.abs(Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH);
        if (a < 11) return;
        long now = SystemClock.uptimeMillis();
        if (now - firstHit > 1200) {
            firstHit = now;
            shakeHits = 0;
        }
        if (now - lastHit > 90) {
            shakeHits++;
            lastHit = now;
        }
        if (shakeHits >= 4 && now - lastShake > 1500) {
            lastShake = now;
            shakeHits = 0;
            onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int acc) {}

    private void onShake() {
        if (view == null) return;
        view.crumbleActive();
        saveIfChanged();
    }
}
