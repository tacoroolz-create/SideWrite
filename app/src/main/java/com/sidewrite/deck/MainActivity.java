package com.sidewrite.deck;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SideWrite — a portrait phone used as a landscape writing deck.
 *
 * The activity stays in portrait orientation; the view hierarchy is rotated
 * 90 degrees, so text, caret and selection render sideways without the OS
 * ever rotating the screen. Hardware keyboard does the input.
 *
 * Documents: ActionBar-free UI with a slim toolbar (New / Open / Save) that
 * is rotated along with everything else, plus desktop-style keyboard
 * shortcuts: Ctrl+S (save), Ctrl+Shift+S (save as), Ctrl+O (open), Ctrl+N
 * (new). Files are read/written through the Storage Access Framework, so
 * they live in normal shared storage (e.g. Documents/) and can be pulled
 * onto a computer later. An internal autosave guards against crashes.
 *
 * Grip direction: ROTATION = 90 means the phone's TOP edge sits on your LEFT
 * while reading. If you prefer the other grip, set ROTATION to -90f.
 *
 * Note: system Toasts/Alerts render in portrait orientation (they are drawn
 * by the OS, not by us); in-app prompts are rotated correctly.
 */
public class MainActivity extends Activity {

    private static final String FILE = "draft.txt";   // internal autosave slot
    private static final String PREFS = "state";
    private static final String PREF_URI = "currentUri";
    private static final String PREF_NAME = "currentName";
    private static final float ROTATION = 90f;
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE_AS = 2;

    // E Ink friendly paper palette. Swap the commented values for LCD/OLED.
    private static final int COLOR_BG = 0xFFF2F0EA;        // 0xFF22252B
    private static final int COLOR_TEXT = 0xFF1A1A1A;      // 0xFFE8E6E3
    private static final int COLOR_FAINT = 0xFF8A877F;     // 0xFF6B7280
    private static final int COLOR_SELECTION = 0x3390A4C4; // 0x664A90D9

    private FrameLayout deck;
    private EditText editor;
    private TextView status, docTitle;
    private View confirmOverlay;

    private Uri currentUri;
    private String currentName = "Untitled";
    private boolean dirty;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        // The rotated container: laid out as the landscape rectangle
        // (window height x window width), then spun into the portrait frame.
        deck = new FrameLayout(this);

        // --- Editor -------------------------------------------------------
        editor = new EditText(this);
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setTextColor(COLOR_TEXT);
        editor.setHighlightColor(COLOR_SELECTION);
        editor.setHintTextColor(COLOR_FAINT);
        editor.setHint("Plug in a keyboard and write. Ctrl+S saves.");
        editor.setTextSize(19f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setLineSpacing(0f, 1.55f);
        editor.setPadding(dp(40), dp(76), dp(40), dp(72));
        editor.setShowSoftInputOnFocus(false);
        editor.setSaveEnabled(false);
        deck.addView(editor, new FrameLayout.LayoutParams(-1, -1));

        // --- Word count, bottom-right of the visible (rotated) page ------
        status = new TextView(this);
        status.setTextColor(COLOR_FAINT);
        status.setTextSize(11f);
        status.setTypeface(Typeface.MONOSPACE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2);
        statusLp.gravity = Gravity.BOTTOM | Gravity.END;
        statusLp.rightMargin = dp(24);
        statusLp.bottomMargin = dp(18);
        deck.addView(status, statusLp);

        // --- Toolbar: New / Open / Save + document title -------------------
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(COLOR_BG);

        TextView menuLabel = new TextView(this);
        menuLabel.setText(" \u2630 ");
        menuLabel.setTextColor(COLOR_FAINT);
        menuLabel.setTextSize(14f);
        menuLabel.setTypeface(Typeface.MONOSPACE);
        bar.addView(menuLabel);

        bar.addView(barButton("New", new Runnable() { public void run() { confirmNew(); } }));
        bar.addView(barButton("Open", new Runnable() { public void run() { doOpen(); } }));
        bar.addView(barButton("Save", new Runnable() { public void run() { doSave(false); } }));

        docTitle = new TextView(this);
        docTitle.setTextColor(COLOR_FAINT);
        docTitle.setTextSize(12f);
        docTitle.setTypeface(Typeface.MONOSPACE);
        docTitle.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.leftMargin = dp(12);
        bar.addView(docTitle, titleLp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2);
        barLp.gravity = Gravity.TOP;
        barLp.topMargin = dp(4);
        barLp.leftMargin = dp(16);
        barLp.rightMargin = dp(16);
        deck.addView(bar, barLp);

        // --- In-app "discard unsaved changes?" prompt (rotated correctly) --
        confirmOverlay = buildConfirmOverlay();
        deck.addView(confirmOverlay, new FrameLayout.LayoutParams(-1, -1));
        confirmOverlay.setVisibility(View.GONE);

        root.addView(deck, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        // State: reopen last saved document, else the internal autosave.
        restoreState();
        wireTextChanged();
        updateTitle();

        editor.requestFocus();
        editor.setSelection(editor.getText().length());

        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        applyRotation(root);
                    }
                });
    }

    // --------------------------------------------------------------------
    // Rotation
    // --------------------------------------------------------------------

    private void applyRotation(View root) {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            root.post(() -> applyRotation(root));
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) deck.getLayoutParams();
        lp.width = h;
        lp.height = w;
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

    // --------------------------------------------------------------------
    // Keyboard shortcuts
    // --------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_S:
                    doSave(event.isShiftPressed());
                    return true;
                case KeyEvent.KEYCODE_O:
                    doOpen();
                    return true;
                case KeyEvent.KEYCODE_N:
                    confirmNew();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // --------------------------------------------------------------------
    // File operations (Storage Access Framework)
    // --------------------------------------------------------------------

    private void doSave(boolean saveAs) {
        if (currentUri == null || saveAs) {
            launchSaveAs();
        } else if (writeCurrent()) {
            dirty = false;
            updateTitle();
            toast("Saved " + currentName);
        }
    }

    private void doOpen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // any text-ish file; user picks in the dialog
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!hasPicker(i)) {
            toast("Document picker unavailable on this device");
            return;
        }
        startActivityForResult(i, REQ_OPEN);
    }

    private void launchSaveAs() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,
                currentName.equals("Untitled") ? "Untitled.txt" : currentName);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!hasPicker(i)) {
            toast("Document picker unavailable on this device");
            return;
        }
        startActivityForResult(i, REQ_SAVE_AS);
    }

    private boolean hasPicker(Intent i) {
        return getPackageManager().resolveActivity(i, 0) != null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        if (requestCode == REQ_OPEN) {
            try {
                editor.setText(readUri(uri));
                currentUri = uri;
                currentName = queryDisplayName(uri);
                dirty = false;
                editor.requestFocus();
                editor.setSelection(editor.getText().length());
                updateTitle();
            } catch (Exception e) {
                toast("Could not open file");
            }
        } else if (requestCode == REQ_SAVE_AS) {
            currentUri = uri;
            currentName = queryDisplayName(uri);
            if (writeCurrent()) {
                dirty = false;
                updateTitle();
                toast("Saved " + currentName);
            }
        }
    }

    private String readUri(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private boolean writeCurrent() {
        if (currentUri == null) return false;
        try (OutputStream out = getContentResolver().openOutputStream(currentUri, "wt")) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            toast("Save failed \u2014 use Save As");
            return false;
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return "Untitled";
    }

    // --------------------------------------------------------------------
    // New document + discard confirmation (in-app, so it renders sideways)
    // --------------------------------------------------------------------

    private void confirmNew() {
        if (!dirty) {
            newDoc();
            return;
        }
        confirmOverlay.setVisibility(View.VISIBLE);
    }

    private View buildConfirmOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE6F2F0EA);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        TextView msg = new TextView(this);
        msg.setText("Discard unsaved changes\nand start a new document?");
        msg.setTextColor(COLOR_TEXT);
        msg.setTextSize(16f);
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0f, 1.4f);
        box.addView(msg, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(solidButton("Cancel", new Runnable() {
            public void run() { overlay.setVisibility(View.GONE); }
        }));
        buttons.addView(solidButton("Discard", new Runnable() {
            public void run() { overlay.setVisibility(View.GONE); newDoc(); }
        }));
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(-2, -2);
        btnsLp.topMargin = dp(24);
        box.addView(buttons, btnsLp);

        overlay.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return overlay;
    }

    private Button barButton(String label, final Runnable action) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextSize(13f);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(COLOR_TEXT);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Button solidButton(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14f);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(12);
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(lp);
        return b;
    }

    // --------------------------------------------------------------------
    // Text state, autosave, internal drafts
    // --------------------------------------------------------------------

    private void newDoc() {
        currentUri = null;
        currentName = "Untitled";
        dirty = false;
        editor.setText("");
        saveInternal();
        updateTitle();
        editor.requestFocus();
        toast("New document");
    }

    private void wireTextChanged() {
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!dirty) {
                    dirty = true;
                    updateTitle();
                }
                String t = s.toString().trim();
                int words = t.isEmpty() ? 0 : t.split("\\s+").length;
                status.setText(words + " words \u00b7 " + s.length() + " chars");
                handler.removeCallbacks(autosaveRun);
                handler.postDelayed(autosaveRun, 800);
            }
        });
    }

    private final Runnable autosaveRun = new Runnable() {
        @Override public void run() {
            saveInternal();
            if (currentUri != null && writeCurrent()) {
                dirty = false;
                updateTitle();
            }
        }
    };

    private void updateTitle() {
        docTitle.setText(currentName + (dirty ? " \u2733" : ""));
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autosaveRun);
        saveInternal();
        super.onPause();
    }

    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String su = prefs.getString(PREF_URI, null);
        String text = null;
        if (su != null) {
            try {
                text = readUri(Uri.parse(su));
                currentUri = Uri.parse(su);
                currentName = prefs.getString(PREF_NAME, "Untitled");
            } catch (Exception e) {
                text = null;
                currentUri = null;
            }
        }
        if (text == null) {
            text = loadInternal();
        }
        editor.setText(text);
    }

    private void saveInternal() {
        try (FileOutputStream out = openFileOutput(FILE, MODE_PRIVATE)) {
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private String loadInternal() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(openFileInput(FILE), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
