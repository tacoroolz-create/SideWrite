package com.sidewrite.deck;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SideWrite — a portrait phone used as a landscape writing deck.
 *
 * The activity stays in portrait orientation; only the view hierarchy is
 * rotated 90 degrees, so the text, caret and selection all render sideways
 * without the OS ever rotating the screen. Hardware-keyboard input lands in
 * the EditText as usual; the (nonexistent) soft keyboard is suppressed.
 *
 * Grip direction: ROTATION = 90 means the phone's TOP edge sits on your LEFT
 * while reading. If you prefer the other grip, set ROTATION to -90f.
 */
public class MainActivity extends Activity {

    private static final String FILE = "draft.txt";
    private static final float ROTATION = 90f;

    // E Ink friendly paper palette. If this ever runs on a normal LCD/OLED
    // phone, swap in the commented dark-theme values instead.
    private static final int COLOR_BG = 0xFFF2F0EA;        // 0xFF22252B
    private static final int COLOR_TEXT = 0xFF1A1A1A;      // 0xFFE8E6E3
    private static final int COLOR_FAINT = 0xFF8A877F;     // 0xFF6B7280
    private static final int COLOR_SELECTION = 0x3390A4C4; // 0x664A90D9

    private FrameLayout deck;
    private EditText editor;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveRun = this::save;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen awake while the deck is open — it's a typewriter.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        // "deck" is the container that gets rotated; it is sized to the
        // *landscape* rectangle (window height x window width) and then spun
        // into place, so everything inside it is sideways with it.
        deck = new FrameLayout(this);
        deck.setBackgroundColor(COLOR_BG);

        editor = new EditText(this);
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setTextColor(COLOR_TEXT);
        editor.setHighlightColor(COLOR_SELECTION);
        editor.setHintTextColor(COLOR_FAINT);
        editor.setHint("Plug in a keyboard and write.");
        editor.setTextSize(19f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setLineSpacing(0f, 1.55f);
        editor.setPadding(dp(40), dp(72), dp(40), dp(72));
        editor.setShowSoftInputOnFocus(false);
        editor.setSaveEnabled(false); // we persist to a file ourselves
        editor.setSingleLine(false);

        status = new TextView(this);
        status.setTextColor(COLOR_FAINT);
        status.setTextSize(11f);
        status.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.TOP | Gravity.END;
        statusLp.topMargin = dp(14);
        statusLp.rightMargin = dp(24);

        deck.addView(editor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        deck.addView(status, statusLp);
        root.addView(deck, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        editor.setText(loadDraft());
        editor.requestFocus();
        editor.setSelection(editor.getText().length());

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String t = s.toString().trim();
                int words = t.isEmpty() ? 0 : t.split("\\s+").length;
                status.setText(words + " words \u00b7 " + s.length() + " chars");
                handler.removeCallbacks(saveRun);
                handler.postDelayed(saveRun, 800);
            }
        });

        // Rotate as soon as we know the real window size (before first draw).
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        applyRotation(root);
                    }
                });
    }

    private void applyRotation(View root) {
        int w = root.getWidth();  // portrait window width
        int h = root.getHeight(); // portrait window height
        if (w == 0 || h == 0) {
            root.post(() -> applyRotation(root));
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) deck.getLayoutParams();
        lp.width = h;  // the deck is laid out wider than the window on purpose:
        lp.height = w; // rotation folds it exactly into the portrait frame.
        deck.setPivotX(0f);
        deck.setPivotY(0f);
        deck.setRotation(ROTATION);
        if (ROTATION == 90f) {
            deck.setTranslationX(w);
        } else {
            deck.setTranslationY(h);
        }
        root.requestLayout();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saveRun);
        save();
        super.onPause();
    }

    private void save() {
        try (FileOutputStream out = openFileOutput(FILE, MODE_PRIVATE)) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String loadDraft() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(openFileInput(FILE), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line, sep = "";
            while ((line = r.readLine()) != null) {
                sb.append(sep).append(line);
                sep = "\n";
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}