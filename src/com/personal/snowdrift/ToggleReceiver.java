package com.personal.snowdrift;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Кнопки «Остановить/Запустить» и «Выйти» в уведомлении и возврат смахнутого уведомления. */
public class ToggleReceiver extends BroadcastReceiver {
    static final String ACTION_TOGGLE = "com.personal.snowdrift.TOGGLE";
    static final String ACTION_REPOST = "com.personal.snowdrift.REPOST";
    static final String ACTION_EXIT = "com.personal.snowdrift.EXIT";

    @Override
    public void onReceive(Context c, Intent i) {
        if (ACTION_TOGGLE.equals(i.getAction())) {
            SharedPreferences p = Prefs.get(c);
            p.edit().putBoolean(Prefs.ENABLED, !Prefs.enabled(p)).apply();
        } else if (ACTION_REPOST.equals(i.getAction()) && SnowService.instance != null) {
            SnowService.instance.postNotification();
        } else if (ACTION_EXIT.equals(i.getAction())) {
            if (SnowService.instance != null) SnowService.instance.exitApp();
            else SnowService.killApp(c);
        }
    }
}
