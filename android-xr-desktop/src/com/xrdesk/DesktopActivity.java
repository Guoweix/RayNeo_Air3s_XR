package com.xrdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The desktop shell. Runs full screen on the glasses display (display 2) and lists
 * every launchable app. Tapping an icon launches that app onto the same display.
 */
public class DesktopActivity extends Activity {

    static class AppEntry {
        String label;
        String pkg;
        Drawable icon;
    }

    private final List<AppEntry> apps = new ArrayList<>();
    private GridView grid;
    private AppAdapter adapter;
    private TextView title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0E1116"));

        title = new TextView(this);
        title.setText("XR 桌面   加载中…");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        title.setPadding(40, 28, 40, 12);
        root.addView(title);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(230);
        grid.setHorizontalSpacing(28);
        grid.setVerticalSpacing(28);
        grid.setPadding(40, 16, 40, 40);
        grid.setVerticalScrollBarEnabled(true);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(apps.get(position));
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        loadApps();
    }

    private void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN, null);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

                final List<AppEntry> loaded = new ArrayList<>();
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    AppEntry e = new AppEntry();
                    e.label = ri.loadLabel(pm).toString();
                    e.pkg = ri.activityInfo.packageName;
                    e.icon = ri.loadIcon(pm);
                    loaded.add(e);
                }
                Collections.sort(loaded, new Comparator<AppEntry>() {
                    public int compare(AppEntry a, AppEntry b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                runOnUiThread(new Runnable() {
                    public void run() {
                        apps.clear();
                        apps.addAll(loaded);
                        adapter.notifyDataSetChanged();
                        title.setText("XR 桌面   应用 " + apps.size() + " 个");
                        Log.i(XrInputService.TAG, "desktop loaded " + apps.size() + " apps");
                    }
                });
            }
        }, "desk-apps").start();
    }

    private void launch(AppEntry e) {
        Intent li = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (li == null) {
            Toast.makeText(this, "无法启动 " + e.label, Toast.LENGTH_SHORT).show();
            return;
        }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions opts = ActivityOptions.makeBasic();
        opts.setLaunchDisplayId(getDisplay().getDisplayId());
        try {
            startActivity(li, opts.toBundle());
            Log.i(XrInputService.TAG, "desktop launch " + e.pkg
                    + " on display " + getDisplay().getDisplayId());
        } catch (Exception ex) {
            Log.e(XrInputService.TAG, "desktop launch failed", ex);
            Toast.makeText(this, "启动失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() {
            return apps.size();
        }

        public Object getItem(int i) {
            return apps.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View convertView, ViewGroup parent) {
            LinearLayout cell = new LinearLayout(DesktopActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(16, 16, 16, 16);
            cell.setBackgroundColor(Color.parseColor("#1A1F26"));

            ImageView iv = new ImageView(DesktopActivity.this);
            iv.setImageDrawable(apps.get(i).icon);
            cell.addView(iv, new LinearLayout.LayoutParams(150, 150));

            TextView tv = new TextView(DesktopActivity.this);
            tv.setText(apps.get(i).label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16f);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(2);
            tv.setPadding(0, 12, 0, 0);
            cell.addView(tv);

            return cell;
        }
    }
}
