package com.maichimod.prototype.home;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import com.maichimod.prototype.test.R;

/** Runs in the test APK process, which intentionally has no target APK classpath. */
public class TestInformationWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { update(context, ids, "2"); }
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if ("com.maichimod.prototype.test.CHANGE_WIDGET".equals(intent.getAction())) {
            int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, getClass()));
            update(context, ids, intent.getStringExtra("number"));
        }
    }
    private void update(Context context, int[] ids, String number) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.test_information_widget);
        views.setTextViewText(R.id.tv_pkg_num, number == null ? "" : number);
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views);
    }
}
