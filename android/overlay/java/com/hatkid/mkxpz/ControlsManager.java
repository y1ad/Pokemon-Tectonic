package com.hatkid.mkxpz;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import org.libsdl.app.SDLActivity;
import com.hatkid.mkxpz.gamepad.GamepadButton;
import com.hatkid.mkxpz.gamepad.GamepadDPad;

/**
 * Boton de ajustes (engranaje, esquina superior izquierda) + editor de controles tactiles.
 *
 * - Saca cada boton/cruceta del layout fijo del motor a una capa libre y guarda en
 *   SharedPreferences: posicion (fraccion de pantalla), tamano y visibilidad.
 * - Editor: arrastrar para mover, +/- para el tamano, ocultar/quitar.
 * - Permite anadir botones extra con la tecla que quieras, cambiar opacidad y tamano global.
 */
public class ControlsManager
{
    private static final String PREFS = "controls_layout";
    private static final int DEFAULT_OPACITY = 30;

    private interface IntCb { void on(int v); }

    private static class Ctl
    {
        String id;
        String label;
        View view;
        boolean custom;
        int keycode;
        int defX, defY, defW, defH;
    }

    // Teclas disponibles para botones extra
    private static final List<String> KEY_NAMES = new ArrayList<>();
    private static final List<Integer> KEY_CODES = new ArrayList<>();

    private static void addKey(String name, int code)
    {
        KEY_NAMES.add(name);
        KEY_CODES.add(code);
    }

    static {
        addKey("Enter", KeyEvent.KEYCODE_ENTER);
        addKey("Esc", KeyEvent.KEYCODE_ESCAPE);
        addKey("Espacio", KeyEvent.KEYCODE_SPACE);
        addKey("Tab", KeyEvent.KEYCODE_TAB);
        addKey("Retroceso", KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < 26; i++)
            addKey(String.valueOf((char) ('A' + i)), KeyEvent.KEYCODE_A + i);
        for (int i = 0; i < 10; i++)
            addKey(String.valueOf(i), KeyEvent.KEYCODE_0 + i);
        for (int i = 1; i <= 12; i++)
            addKey("F" + i, KeyEvent.KEYCODE_F1 + i - 1);
    }

    private final Activity mActivity;
    private final ViewGroup mHost;
    private final SharedPreferences mPrefs;
    private final List<Ctl> mCtls = new ArrayList<>();

    private RelativeLayout mRoot;
    private TextView mGear;
    private LinearLayout mEditBar;
    private TextView mBtnRemove;

    private boolean mReady = false;
    private boolean mEditing = false;
    private Ctl mSelected = null;
    private int mOpacity = DEFAULT_OPACITY;
    private float mGlobalScale = 1f;

    public ControlsManager(Activity activity, ViewGroup host)
    {
        mActivity = activity;
        mHost = host;
        mPrefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public boolean isEditing()
    {
        return mEditing;
    }

    // ---------------------------------------------------------------- init

    public void init()
    {
        mRoot = mHost.findViewById(R.id.gamepad_layout);
        if (mRoot == null)
            return;

        mOpacity = mPrefs.getInt("opacity", DEFAULT_OPACITY);
        mGlobalScale = mPrefs.getFloat("global_scale", 1f);

        createGear();
        createEditBar();

        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
        {
            @Override
            public void onGlobalLayout()
            {
                View dpad = mRoot.findViewById(R.id.dpad);
                if (mReady || mRoot.getWidth() <= 0 || dpad == null || dpad.getWidth() <= 0)
                    return;

                mReady = true;
                mRoot.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                mRoot.post(() -> setup());
            }
        });
    }

    private void setup()
    {
        int[] rootLoc = new int[2];
        mRoot.getLocationInWindow(rootLoc);

        addBuiltin("dpad", "Cruceta", R.id.dpad, rootLoc);
        addBuiltin("L", "Q", R.id.button_L, rootLoc);
        addBuiltin("R", "W", R.id.button_R, rootLoc);
        addBuiltin("X", "A", R.id.button_X, rootLoc);
        addBuiltin("Y", "S", R.id.button_Y, rootLoc);
        addBuiltin("Z", "D", R.id.button_Z, rootLoc);
        addBuiltin("A", "Z", R.id.button_A, rootLoc);
        addBuiltin("B", "X", R.id.button_B, rootLoc);
        addBuiltin("C", "C", R.id.button_C, rootLoc);
        addBuiltin("CTRL", "CTRL", R.id.button_CTRL, rootLoc);
        addBuiltin("ALT", "ALT", R.id.button_ALT, rootLoc);
        addBuiltin("SHIFT", "SHIFT", R.id.button_SHIFT, rootLoc);

        // Sacar todos los controles a la capa raiz (libres de sus contenedores)
        for (Ctl c : mCtls) {
            ViewGroup parent = (ViewGroup) c.view.getParent();
            if (parent != null)
                parent.removeView(c.view);

            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(c.defW, c.defH);
            lp.leftMargin = c.defX;
            lp.topMargin = c.defY;
            mRoot.addView(c.view, lp);
        }

        // Los contenedores originales quedan vacios
        int[] containers = {
            R.id.buttons_misc_left_layout, R.id.buttons_misc_right_layout,
            R.id.buttons_action_layout, R.id.buttons_mod_layout
        };
        for (int id : containers) {
            View v = mRoot.findViewById(id);
            if (v != null)
                v.setVisibility(View.GONE);
        }

        View dpad = byId("dpad").view;
        if (dpad instanceof GamepadDPad)
            ((GamepadDPad) dpad).isDiagonal = mPrefs.getBoolean("diag", false);

        loadCustoms();
        applyAll();

        mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (b - t) != (ob - ot))
                mRoot.post(this::applyAll);
        });
    }

    private void addBuiltin(String id, String label, int viewId, int[] rootLoc)
    {
        View v = mRoot.findViewById(viewId);
        if (v == null)
            return;

        int[] loc = new int[2];
        v.getLocationInWindow(loc);

        Ctl c = new Ctl();
        c.id = id;
        c.label = label;
        c.view = v;
        c.defX = loc[0] - rootLoc[0];
        c.defY = loc[1] - rootLoc[1];
        c.defW = v.getWidth();
        c.defH = v.getHeight();
        mCtls.add(c);
    }

    private Ctl byId(String id)
    {
        for (Ctl c : mCtls)
            if (c.id.equals(id))
                return c;
        return null;
    }

    private Ctl byView(View v)
    {
        for (Ctl c : mCtls)
            if (c.view == v)
                return c;
        return null;
    }

    // ------------------------------------------------------------ apply

    private static int clamp(int v, int lo, int hi)
    {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(int v)
    {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }

    private void applyAll()
    {
        int rw = mRoot.getWidth();
        int rh = mRoot.getHeight();
        if (rw <= 0 || rh <= 0)
            return;

        for (Ctl c : mCtls)
            applyElement(c, rw, rh);

        applyOpacity(mEditing ? 100 : mOpacity);
    }

    private void applyElement(Ctl c, int rw, int rh)
    {
        float s = mPrefs.getFloat("s_" + c.id, 1f) * mGlobalScale;
        int w = Math.max(8, Math.round(c.defW * s));
        int h = Math.max(8, Math.round(c.defH * s));

        float fx = mPrefs.getFloat("x_" + c.id, -1f);
        float fy = mPrefs.getFloat("y_" + c.id, -1f);
        int x = fx >= 0 ? Math.round(fx * rw) : c.defX;
        int y = fy >= 0 ? Math.round(fy * rh) : c.defY;
        x = clamp(x, 0, Math.max(0, rw - w));
        y = clamp(y, 0, Math.max(0, rh - h));

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) c.view.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        c.view.setLayoutParams(lp);

        c.view.setVisibility(mPrefs.getBoolean("v_" + c.id, true) ? View.VISIBLE : View.GONE);
    }

    private void applyOpacity(int pct)
    {
        int a = Math.round(Math.min(pct, 100) * 2.55f);
        for (Ctl c : mCtls) {
            if (c.view instanceof GamepadButton)
                ((GamepadButton) c.view).setImageAlpha(a);
        }
    }

    private void savePos(Ctl c)
    {
        int rw = mRoot.getWidth();
        int rh = mRoot.getHeight();
        if (rw <= 0 || rh <= 0)
            return;

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) c.view.getLayoutParams();
        mPrefs.edit()
            .putFloat("x_" + c.id, lp.leftMargin / (float) rw)
            .putFloat("y_" + c.id, lp.topMargin / (float) rh)
            .apply();
    }

    // ------------------------------------------------------ gear + bar

    private ViewGroup.LayoutParams hostParams(int w, int h, boolean top, boolean start, boolean centerH, int margin)
    {
        if (mHost instanceof RelativeLayout) {
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(w, h);
            if (top)
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            if (start)
                lp.addRule(RelativeLayout.ALIGN_PARENT_START);
            if (centerH)
                lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            lp.setMargins(margin, margin, margin, margin);
            return lp;
        }

        if (mHost instanceof FrameLayout) {
            int g = (top ? Gravity.TOP : Gravity.BOTTOM) | (centerH ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, g);
            lp.setMargins(margin, margin, margin, margin);
            return lp;
        }

        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(w, h);
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }

    private void createGear()
    {
        mGear = new TextView(mActivity);
        mGear.setText("\u2699\uFE0E");
        mGear.setTextColor(Color.WHITE);
        mGear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mGear.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        mGear.setBackground(bg);
        mGear.setAlpha(0.7f);
        mGear.setOnClickListener(v -> showMainMenu());

        mHost.addView(mGear, hostParams(dp(44), dp(44), true, true, false, dp(8)));
    }

    private TextView barButton(String text, View.OnClickListener l)
    {
        TextView t = new TextView(mActivity);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF3C3C3C);
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
        t.setOnClickListener(l);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void createEditBar()
    {
        mEditBar = new LinearLayout(mActivity);
        mEditBar.setOrientation(LinearLayout.HORIZONTAL);
        mEditBar.setGravity(Gravity.CENTER);
        mEditBar.setPadding(dp(6), dp(6), dp(6), dp(6));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD111111);
        bg.setCornerRadius(dp(14));
        mEditBar.setBackground(bg);

        mEditBar.addView(barButton("\u2714 Listo", v -> exitEdit()));
        mEditBar.addView(barButton("Tama\u00f1o \u2212", v -> resizeSelected(0.9f)));
        mEditBar.addView(barButton("Tama\u00f1o +", v -> resizeSelected(1.1f)));
        mBtnRemove = barButton("Ocultar", v -> removeSelected());
        mEditBar.addView(mBtnRemove);
        mEditBar.setVisibility(View.GONE);

        mHost.addView(mEditBar, hostParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true, false, true, dp(8)));
    }

    // -------------------------------------------------------- editing

    @SuppressLint("ClickableViewAccessibility")
    private final View.OnTouchListener mDragListener = new View.OnTouchListener()
    {
        private float startRawX, startRawY;
        private int startL, startT;

        @Override
        public boolean onTouch(View v, MotionEvent e)
        {
            Ctl c = byView(v);
            if (c == null)
                return false;

            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();

            switch (e.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    select(c);
                    startRawX = e.getRawX();
                    startRawY = e.getRawY();
                    startL = lp.leftMargin;
                    startT = lp.topMargin;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    int rw = mRoot.getWidth();
                    int rh = mRoot.getHeight();
                    lp.leftMargin = clamp(startL + Math.round(e.getRawX() - startRawX), 0, Math.max(0, rw - lp.width));
                    lp.topMargin = clamp(startT + Math.round(e.getRawY() - startRawY), 0, Math.max(0, rh - lp.height));
                    v.setLayoutParams(lp);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePos(c);
                    return true;
            }

            return true;
        }
    };

    private void enterEdit()
    {
        mEditing = true;
        mGear.setVisibility(View.GONE);
        mEditBar.setVisibility(View.VISIBLE);

        for (Ctl c : mCtls)
            c.view.setOnTouchListener(mDragListener);

        select(null);
        applyOpacity(100);
        Toast.makeText(mActivity, "Arrastra para mover. Toca un bot\u00f3n para seleccionarlo.", Toast.LENGTH_LONG).show();
    }

    public void exitEdit()
    {
        if (!mEditing)
            return;

        mEditing = false;
        select(null);

        for (Ctl c : mCtls)
            c.view.setOnTouchListener(null);

        mEditBar.setVisibility(View.GONE);
        mGear.setVisibility(View.VISIBLE);
        applyOpacity(mOpacity);
    }

    private void select(Ctl c)
    {
        if (mSelected != null && mSelected.view instanceof GamepadButton)
            ((GamepadButton) mSelected.view).clearColorFilter();

        mSelected = c;

        if (c != null && c.view instanceof GamepadButton)
            ((GamepadButton) c.view).setColorFilter(0xAA33B5E5, PorterDuff.Mode.SRC_ATOP);

        mBtnRemove.setText(c != null && c.custom ? "Quitar" : "Ocultar");
    }

    private void resizeSelected(float factor)
    {
        Ctl c = mSelected;
        if (c == null) {
            Toast.makeText(mActivity, "Toca un bot\u00f3n primero", Toast.LENGTH_SHORT).show();
            return;
        }

        int rw = mRoot.getWidth();
        int rh = mRoot.getHeight();
        float ns = Math.max(0.4f, Math.min(3f, mPrefs.getFloat("s_" + c.id, 1f) * factor));
        mPrefs.edit().putFloat("s_" + c.id, ns).apply();

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) c.view.getLayoutParams();
        int cx = lp.leftMargin + lp.width / 2;
        int cy = lp.topMargin + lp.height / 2;
        int nw = Math.max(8, Math.round(c.defW * ns * mGlobalScale));
        int nh = Math.max(8, Math.round(c.defH * ns * mGlobalScale));

        lp.width = nw;
        lp.height = nh;
        lp.leftMargin = clamp(cx - nw / 2, 0, Math.max(0, rw - nw));
        lp.topMargin = clamp(cy - nh / 2, 0, Math.max(0, rh - nh));
        c.view.setLayoutParams(lp);
        savePos(c);
    }

    private void removeSelected()
    {
        Ctl c = mSelected;
        if (c == null) {
            Toast.makeText(mActivity, "Toca un bot\u00f3n primero", Toast.LENGTH_SHORT).show();
            return;
        }

        if (c.custom) {
            removeCustom(c);
        } else {
            setVisible(c, false);
            select(null);
        }
    }

    private void setVisible(Ctl c, boolean visible)
    {
        mPrefs.edit().putBoolean("v_" + c.id, visible).apply();
        c.view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // -------------------------------------------------- custom buttons

    @SuppressLint("ClickableViewAccessibility")
    private void createCustomView(Ctl c)
    {
        Ctl ref = byId("A");
        c.defW = ref != null ? ref.defW : dp(90);
        c.defH = ref != null ? ref.defH : dp(90);
        c.defX = mRoot.getWidth() / 2 - c.defW / 2;
        c.defY = mRoot.getHeight() / 2 - c.defH / 2;

        GamepadButton b = new GamepadButton(mActivity);
        b.setBackgroundResource(R.drawable.gamepad_button_circle);
        b.setForegroundText(c.label);
        b.setKey(c.keycode);
        b.setOnKeyDownListener(key -> SDLActivity.onNativeKeyDown(key));
        b.setOnKeyUpListener(key -> SDLActivity.onNativeKeyUp(key));
        c.view = b;

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(c.defW, c.defH);
        lp.leftMargin = c.defX;
        lp.topMargin = c.defY;
        mRoot.addView(b, lp);

        if (mEditing)
            b.setOnTouchListener(mDragListener);
    }

    private void loadCustoms()
    {
        String s = mPrefs.getString("customs", "");
        if (s == null || s.isEmpty())
            return;

        for (String part : s.split(";")) {
            String[] f = part.split("\\|");
            if (f.length < 3)
                continue;

            try {
                Ctl c = new Ctl();
                c.id = f[0];
                c.keycode = Integer.parseInt(f[1]);
                c.label = f[2];
                c.custom = true;
                createCustomView(c);
                mCtls.add(c);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void persistCustoms()
    {
        StringBuilder sb = new StringBuilder();
        for (Ctl c : mCtls) {
            if (!c.custom)
                continue;
            if (sb.length() > 0)
                sb.append(';');
            sb.append(c.id).append('|').append(c.keycode).append('|').append(c.label);
        }
        mPrefs.edit().putString("customs", sb.toString()).apply();
    }

    private void addCustom(String label, int keycode)
    {
        int n = mPrefs.getInt("custom_next", 1);
        mPrefs.edit().putInt("custom_next", n + 1).apply();

        Ctl c = new Ctl();
        c.id = "c" + n;
        c.label = label;
        c.keycode = keycode;
        c.custom = true;
        createCustomView(c);
        mCtls.add(c);
        persistCustoms();
        applyAll();

        enterEdit();
        select(c);
    }

    private void removeCustom(Ctl c)
    {
        if (mSelected == c)
            select(null);

        mRoot.removeView(c.view);
        mCtls.remove(c);
        mPrefs.edit()
            .remove("x_" + c.id).remove("y_" + c.id)
            .remove("s_" + c.id).remove("v_" + c.id)
            .apply();
        persistCustoms();
    }

    // ------------------------------------------------------- dialogs

    private void showMainMenu()
    {
        final boolean diag = mPrefs.getBoolean("diag", false);
        final String[] items = {
            "Editar controles (mover y tama\u00f1o)",
            "Mostrar / ocultar botones",
            "A\u00f1adir bot\u00f3n extra",
            "Eliminar bot\u00f3n extra",
            "Opacidad de los controles",
            "Tama\u00f1o de todos los controles",
            "Cruceta en diagonal: " + (diag ? "S\u00ed" : "No"),
            "Restablecer controles"
        };

        new AlertDialog.Builder(mActivity)
            .setTitle("Ajustes")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: enterEdit(); break;
                    case 1: showVisibilityDialog(); break;
                    case 2: showAddButtonDialog(); break;
                    case 3: showRemoveCustomDialog(); break;
                    case 4: showOpacityDialog(); break;
                    case 5: showScaleDialog(); break;
                    case 6: toggleDiagonal(); break;
                    case 7: confirmReset(); break;
                }
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private void showVisibilityDialog()
    {
        final String[] names = new String[mCtls.size()];
        final boolean[] checked = new boolean[mCtls.size()];

        for (int i = 0; i < mCtls.size(); i++) {
            Ctl c = mCtls.get(i);
            names[i] = c.id.equals("dpad") ? "Cruceta" : (c.custom ? "Extra: " + c.label : "Bot\u00f3n " + c.label);
            checked[i] = mPrefs.getBoolean("v_" + c.id, true);
        }

        new AlertDialog.Builder(mActivity)
            .setTitle("Botones visibles")
            .setMultiChoiceItems(names, checked, (d, which, isChecked) -> setVisible(mCtls.get(which), isChecked))
            .setPositiveButton("Listo", null)
            .show();
    }

    private void showAddButtonDialog()
    {
        final String[] names = KEY_NAMES.toArray(new String[0]);

        new AlertDialog.Builder(mActivity)
            .setTitle("A\u00f1adir bot\u00f3n: elige la tecla")
            .setItems(names, (d, which) -> addCustom(KEY_NAMES.get(which), KEY_CODES.get(which)))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showRemoveCustomDialog()
    {
        final List<Ctl> customs = new ArrayList<>();
        for (Ctl c : mCtls)
            if (c.custom)
                customs.add(c);

        if (customs.isEmpty()) {
            Toast.makeText(mActivity, "No hay botones extra", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[customs.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = "Quitar: " + customs.get(i).label;

        new AlertDialog.Builder(mActivity)
            .setTitle("Eliminar bot\u00f3n extra")
            .setItems(names, (d, which) -> removeCustom(customs.get(which)))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void showSeekDialog(String title, int min, int max, int cur, String unit, IntCb live, IntCb commit)
    {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));

        final TextView label = new TextView(mActivity);
        label.setText(cur + unit);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);

        final SeekBar bar = new SeekBar(mActivity);
        bar.setMax(max - min);
        bar.setProgress(cur - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser)
            {
                int v = p + min;
                label.setText(v + unit);
                live.on(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });

        box.addView(label);
        box.addView(bar);

        new AlertDialog.Builder(mActivity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Aceptar", (d, w) -> commit.on(bar.getProgress() + min))
            .setNegativeButton("Cancelar", (d, w) -> live.on(cur))
            .setOnCancelListener(d -> live.on(cur))
            .show();
    }

    private void showOpacityDialog()
    {
        final int cur = mOpacity;
        showSeekDialog("Opacidad de los controles", 10, 100, cur, "%",
            v -> {
                mOpacity = v;
                applyOpacity(mOpacity);
            },
            v -> {
                mOpacity = v;
                mPrefs.edit().putInt("opacity", v).apply();
            });
    }

    private void showScaleDialog()
    {
        final int cur = Math.round(mGlobalScale * 100f);
        showSeekDialog("Tama\u00f1o de todos los controles", 50, 200, cur, "%",
            v -> {
                mGlobalScale = v / 100f;
                applyAll();
            },
            v -> {
                mGlobalScale = v / 100f;
                mPrefs.edit().putFloat("global_scale", mGlobalScale).apply();
                applyAll();
            });
    }

    private void toggleDiagonal()
    {
        boolean now = !mPrefs.getBoolean("diag", false);
        mPrefs.edit().putBoolean("diag", now).apply();

        Ctl dpad = byId("dpad");
        if (dpad != null && dpad.view instanceof GamepadDPad)
            ((GamepadDPad) dpad.view).isDiagonal = now;

        Toast.makeText(mActivity, "Cruceta en diagonal: " + (now ? "S\u00ed" : "No"), Toast.LENGTH_SHORT).show();
    }

    private void confirmReset()
    {
        new AlertDialog.Builder(mActivity)
            .setTitle("Restablecer controles")
            .setMessage("Se restaurar\u00e1n posiciones, tama\u00f1os, opacidad y se borrar\u00e1n los botones extra.")
            .setPositiveButton("Restablecer", (d, w) -> resetAll())
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void resetAll()
    {
        List<Ctl> customs = new ArrayList<>();
        for (Ctl c : mCtls)
            if (c.custom)
                customs.add(c);
        for (Ctl c : customs) {
            mRoot.removeView(c.view);
            mCtls.remove(c);
        }

        mPrefs.edit().clear().apply();
        mOpacity = DEFAULT_OPACITY;
        mGlobalScale = 1f;

        Ctl dpad = byId("dpad");
        if (dpad != null && dpad.view instanceof GamepadDPad)
            ((GamepadDPad) dpad.view).isDiagonal = false;

        select(null);
        applyAll();
    }
}
