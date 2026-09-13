package com.xrdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "desktop" that lives on the virtual screen. Big touch-friendly buttons whose
 * click counters let us confirm that injected taps really land.
 */
public class TestDesktopActivity extends Activity {
    private int countA, countB, countC;
    private TextView status;
    private Button buttonA, buttonB, buttonC;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#10141A"));
        root.setPadding(60, 140, 60, 60);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(46f);
        status.setGravity(Gravity.CENTER);
        status.setText("XR Desk 虚拟屏 1080x1920\n点击下方按钮验证注入");
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f));

        buttonA = bigButton("按钮 A", "#E53935");
        buttonB = bigButton("按钮 B", "#2E7D32");
        buttonC = bigButton("按钮 C", "#1565C0");
        root.addView(buttonA);
        root.addView(buttonB);
        root.addView(buttonC);

        setContentView(root);
    }

    private Button bigButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(56f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, 26, 0, 26);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onHit((Button) v);
            }
        });
        return b;
    }

    private void onHit(Button v) {
        if (v == buttonA) countA++;
        else if (v == buttonB) countB++;
        else countC++;
        status.setText("点击次数    A=" + countA + "    B=" + countB + "    C=" + countC);
        android.util.Log.i("XRDesk", "HIT " + v.getText()
                + "  A=" + countA + " B=" + countB + " C=" + countC);
    }
}
