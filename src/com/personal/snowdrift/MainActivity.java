package com.personal.snowdrift;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек: запуск/остановка, скорость, интенсивность, высота сугробов, «Шторм». */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int ACCENT = 0xFF7CC4FF;
    private static final int CARD = 0x1FFFFFFF;
    private static final int TEXT_DIM = 0xB3E6F0FF;

    private SharedPreferences prefs;
    private float dp;
    private TextView serviceText;
    private Button serviceBtn, restrictedBtn, toggleBtn;
    private Switch stormSwitch, ecoSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(0xFF10233D);
        getWindow().setNavigationBarColor(0xFF0A1424);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = px(20);
        col.setPadding(pad, px(28), pad, px(40));

        TextView title = text(getString(R.string.title), 30, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        TextView sub = text(getString(R.string.subtitle), 14, TEXT_DIM);
        sub.setPadding(0, px(6), 0, px(18));
        col.addView(sub);

        // --- служба спец. возможностей
        LinearLayout svc = card(col);
        serviceText = text("", 15, 0xFFFFFFFF);
        svc.addView(serviceText);
        serviceBtn = button(getString(R.string.open_accessibility), 0x337CC4FF);
        serviceBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        svc.addView(serviceBtn, lpTop(12));
        restrictedBtn = button(getString(R.string.restricted_hint), 0x22FFFFFF);
        restrictedBtn.setTextSize(13);
        restrictedBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        svc.addView(restrictedBtn, lpTop(8));

        // --- запуск/остановка
        toggleBtn = button("", ACCENT);
        toggleBtn.setTextSize(18);
        toggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        toggleBtn.setTextColor(0xFF0B1A2E);
        toggleBtn.setOnClickListener(v -> prefs.edit().putBoolean(Prefs.ENABLED, !Prefs.enabled(prefs)).apply());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, px(60));
        tlp.topMargin = px(14);
        col.addView(toggleBtn, tlp);

        // --- ползунки
        LinearLayout sl = card(col);
        slider(sl, getString(R.string.speed), Prefs.SPEED, Prefs.DEF_SPEED,
                v -> String.format(java.util.Locale.US, "×%.1f", Prefs.speedFactor(v)));
        slider(sl, getString(R.string.count), Prefs.INTENSITY, Prefs.DEF_INTENSITY,
                v -> String.valueOf(Prefs.flakeCount(v)));
        slider(sl, getString(R.string.drift_height), Prefs.DRIFT, Prefs.DEF_DRIFT,
                v -> Math.round(Prefs.driftMaxDp(v)) + " dp");

        // --- шторм и экономный режим
        stormSwitch = switchCard(col, R.string.storm_mode, R.string.storm_desc, Prefs.STORM);
        ecoSwitch = switchCard(col, R.string.eco_mode, R.string.eco_desc, Prefs.ECO);

        // --- как убрать
        LinearLayout how = card(col);
        TextView hh = text(getString(R.string.how_title), 16, 0xFFFFFFFF);
        hh.setTypeface(Typeface.DEFAULT_BOLD);
        how.addView(hh);
        TextView ht = text(getString(R.string.how_text), 14, TEXT_DIM);
        ht.setPadding(0, px(6), 0, 0);
        ht.setLineSpacing(0, 1.15f);
        how.addView(ht);

        Button melt = button(getString(R.string.melt_all), 0x22FFFFFF);
        melt.setOnClickListener(v -> {
            if (SnowService.instance != null) SnowService.instance.clearAllDrifts();
            else deleteFile("drifts.bin");
            Toast.makeText(this, R.string.melted, Toast.LENGTH_SHORT).show();
        });
        col.addView(melt, lpTop(14));

        Button exit = button(getString(R.string.exit_app), 0x33FF7C7C);
        exit.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_text)
                .setPositiveButton(R.string.exit_app, (d, w) -> {
                    if (SnowService.instance != null) SnowService.instance.exitApp();
                    else SnowService.killApp(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        col.addView(exit, lpTop(14));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF10233D, 0xFF0A1424}));
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (SnowService.instance != null) SnowService.instance.postNotification();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        refresh();
    }

    private void refresh() {
        boolean svc = SnowService.instance != null;
        serviceText.setText(svc ? R.string.service_on : R.string.service_off);
        serviceBtn.setVisibility(svc ? View.GONE : View.VISIBLE);
        restrictedBtn.setVisibility(svc ? View.GONE : View.VISIBLE);
        boolean on = Prefs.enabled(prefs);
        toggleBtn.setText(on ? R.string.stop_snow : R.string.start_snow);
        stormSwitch.setChecked(Prefs.storm(prefs));
        ecoSwitch.setChecked(Prefs.eco(prefs));
    }

    // ---------------------------------------------------------------- построение экрана

    private interface Fmt { String f(int v); }

    private void slider(LinearLayout parent, String label, String key, int def, Fmt fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 16, 0xFFFFFFFF);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = text("", 15, ACCENT);
        val.setGravity(Gravity.END);
        row.addView(val);
        parent.addView(row, lpTop(parent.getChildCount() == 0 ? 0 : 14));

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        int cur = prefs.getInt(key, def);
        sb.setProgress(cur);
        val.setText(fmt.f(cur));
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                val.setText(fmt.f(v));
                if (user) prefs.edit().putInt(key, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(sb, lpTop(4));
    }

    private Switch switchCard(LinearLayout parent, int title, int desc, String key) {
        LinearLayout card = card(parent);
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(17);
        sw.setTextColor(0xFFFFFFFF);
        sw.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{ACCENT, 0xFFCFD8E3}));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x887CC4FF, 0x55FFFFFF}));
        sw.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        card.addView(sw);
        TextView d = text(getString(desc), 13, TEXT_DIM);
        d.setPadding(0, px(4), 0, 0);
        card.addView(d);
        return sw;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(14), px(16), px(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(18));
        c.setBackground(bg);
        parent.addView(c, lpTop(14));
        return c;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(14));
        b.setBackground(bg);
        b.setPadding(px(14), px(12), px(14), px(12));
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(top);
        return lp;
    }

    private int px(float v) { return Math.round(v * dp); }
}
