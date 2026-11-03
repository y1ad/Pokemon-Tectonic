package com.hatkid.mkxpz;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extrae el juego incluido en assets/game.zip a almacenamiento interno (solo la primera vez
 * o cuando cambia la versión del juego) y después lanza MainActivity.
 */
public class LauncherActivity extends Activity
{
    private static final String TAG = "TectonicLauncher";

    public static final String EXTRA_NEEDS_COMPILE = "com.hatkid.mkxpz.NEEDS_COMPILE";

    private static final String ASSET_NAME = "game.zip";
    private static final String VERSION_ENTRY = ".game_version";
    private static final String STATE_FILE = ".mkxp_state";
    private static final String COMPILED_MARKER = "Data/PluginScripts.rxdata";

    private static volatile boolean sWorking = false;

    private TextView mStatus;
    private TextView mDetail;
    private ProgressBar mProgress;
    private Button mRetry;
    private volatile boolean mDestroyed = false;

    /** Directorio donde vive el juego extraído (usado también por MainActivity). */
    public static File getGameDir(Context context)
    {
        return new File(context.getFilesDir(), "game");
    }

    private static boolean isSpanish()
    {
        return Locale.getDefault().getLanguage().equals("es");
    }

    private static String tr(String en, String es)
    {
        return isSpanish() ? es : en;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        startWork();
    }

    @Override
    protected void onDestroy()
    {
        mDestroyed = true;
        super.onDestroy();
    }

    private int dp(float v)
    {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi()
    {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#12151C"));
        root.setPadding(dp(32), dp(16), dp(32), dp(16));

        TextView title = new TextView(this);
        title.setText("Pokémon Tectonic");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.parseColor("#C8CDD8"));
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setText(tr("Preparing game…", "Preparando el juego…"));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(16);
        root.addView(mStatus, sp);

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(1000);
        mProgress.setIndeterminate(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(16);
        root.addView(mProgress, pp);

        mDetail = new TextView(this);
        mDetail.setTextColor(Color.parseColor("#7C8394"));
        mDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mDetail.setGravity(Gravity.CENTER);
        mDetail.setSingleLine(true);
        LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT);
        dpm.topMargin = dp(8);
        root.addView(mDetail, dpm);

        mRetry = new Button(this);
        mRetry.setText(tr("Retry", "Reintentar"));
        mRetry.setVisibility(View.GONE);
        mRetry.setOnClickListener(v -> {
            mRetry.setVisibility(View.GONE);
            mProgress.setVisibility(View.VISIBLE);
            startWork();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(16);
        root.addView(mRetry, rp);

        setContentView(root);
    }

    private void startWork()
    {
        if (sWorking)
            return;
        sWorking = true;

        Thread t = new Thread(() -> {
            try {
                prepareAndLaunch();
            } finally {
                sWorking = false;
            }
        }, "game-extract");
        t.start();
    }

    private void prepareAndLaunch()
    {
        final File gameDir = getGameDir(this);

        try {
            extractIfNeeded(gameDir);
        } catch (final Throwable t) {
            Log.e(TAG, "Game preparation failed", t);
            if (!mDestroyed)
                runOnUiThread(() -> showError(t));
            return;
        }

        if (mDestroyed)
            return;

        final boolean needsCompile = !new File(gameDir, COMPILED_MARKER).exists();

        runOnUiThread(() -> {
            if (isFinishing() || mDestroyed)
                return;
            Intent intent = new Intent(LauncherActivity.this, MainActivity.class);
            intent.putExtra(EXTRA_NEEDS_COMPILE, needsCompile);
            startActivity(intent);
            finish();
        });
    }

    private void showError(Throwable t)
    {
        mProgress.setVisibility(View.GONE);
        mStatus.setText(tr("Could not prepare the game:", "No se pudo preparar el juego:")
            + "\n" + t.getMessage());
        mDetail.setText("");
        mRetry.setVisibility(View.VISIBLE);
    }

    private void postProgress(final long done, final long total, final String name)
    {
        runOnUiThread(() -> {
            if (total > 0) {
                mProgress.setIndeterminate(false);
                mProgress.setProgress((int) Math.min(1000, done * 1000 / total));
                mStatus.setText(String.format(Locale.US,
                    tr("Extracting game (first launch only)… %d%%",
                       "Extrayendo el juego (solo la primera vez)… %d%%"),
                    Math.min(100, done * 100 / total)));
            }
            mDetail.setText(name);
        });
    }

    private void extractIfNeeded(File gameDir) throws IOException
    {
        try (AssetFileDescriptor afd = getAssets().openFd(ASSET_NAME);
             InputStream raw = afd.createInputStream()) {

            final long total = afd.getLength();
            CountingInputStream counting = new CountingInputStream(new BufferedInputStream(raw, 1 << 20));
            ZipInputStream zin = new ZipInputStream(counting);

            ZipEntry entry = zin.getNextEntry();
            String version = String.valueOf(total);
            if (entry != null && VERSION_ENTRY.equals(entry.getName())) {
                version = new String(readFully(zin), StandardCharsets.UTF_8).trim();
                zin.closeEntry();
                entry = zin.getNextEntry();
            }

            String state = readText(new File(gameDir, STATE_FILE));
            if (state.equals("ok:" + version) && new File(gameDir, "Data").isDirectory()) {
                Log.i(TAG, "Game already extracted (version " + version + ")");
                return;
            }

            boolean resume = state.equals("partial:" + version);

            if (!gameDir.isDirectory() && !gameDir.mkdirs())
                throw new IOException("Cannot create " + gameDir);

            if (!resume) {
                long needed = total + total / 10;
                long free = gameDir.getUsableSpace();
                if (free < needed) {
                    throw new IOException(String.format(Locale.US,
                        tr("Not enough free storage: about %.1f GB needed, %.1f GB available.",
                           "No hay suficiente espacio libre: se necesitan ~%.1f GB y hay %.1f GB."),
                        needed / 1e9, free / 1e9));
                }
            }

            writeText(new File(gameDir, STATE_FILE), "partial:" + version);

            byte[] buf = new byte[256 * 1024];
            String base = gameDir.getCanonicalPath() + File.separator;
            long lastUi = 0;

            while (entry != null) {
                if (mDestroyed)
                    throw new IOException("cancelled");

                String name = entry.getName();
                File out = new File(gameDir, name);
                if (!out.getCanonicalPath().startsWith(base))
                    throw new IOException("Invalid zip entry: " + name);

                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null)
                        parent.mkdirs();

                    long size = entry.getSize();
                    boolean skip = resume && size >= 0 && out.isFile() && out.length() == size;
                    if (!skip) {
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) != -1)
                                fos.write(buf, 0, n);
                        }
                    }
                }
                zin.closeEntry();

                long now = System.currentTimeMillis();
                if (now - lastUi > 150) {
                    lastUi = now;
                    postProgress(counting.count, total, name);
                }
                entry = zin.getNextEntry();
            }

            writeText(new File(gameDir, STATE_FILE), "ok:" + version);
            Log.i(TAG, "Game extracted (version " + version + ")");
        }
    }

    private static byte[] readFully(InputStream in) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1)
            bos.write(b, 0, n);
        return bos.toByteArray();
    }

    private static String readText(File f)
    {
        if (!f.isFile())
            return "";
        try (InputStream in = new FileInputStream(f)) {
            return new String(readFully(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException
    {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class CountingInputStream extends FilterInputStream
    {
        long count = 0;

        CountingInputStream(InputStream in)
        {
            super(in);
        }

        @Override
        public int read() throws IOException
        {
            int b = super.read();
            if (b >= 0)
                count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            int n = super.read(b, off, len);
            if (n > 0)
                count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException
        {
            long s = super.skip(n);
            if (s > 0)
                count += s;
            return s;
        }
    }
}
