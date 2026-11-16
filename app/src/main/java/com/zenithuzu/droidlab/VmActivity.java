package com.zenithuzu.droidlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VmActivity extends Activity {
    static final String ID = "rom";
    static final String TITLE = "title";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile GuestMachine guest;
    private volatile boolean stopping;
    private boolean holdsLease;
    private boolean imageAttached;
    private VmDisplay display;
    private TextView status;
    private volatile String finalLog = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!BUSY.compareAndSet(false, true)) {
            new AlertDialog.Builder(this).setMessage("Ya hay una máquina virtual abierta.")
                    .setPositiveButton("Cerrar", (dialog, value) -> finish()).setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        holdsLease = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String id = getIntent().getStringExtra(ID);
        imageAttached = id != null;
        if (imageAttached && !id.matches("[0-9a-f-]{36}")) {
            holdsLease = false;
            BUSY.set(false);
            finish();
            return;
        }
        Path files = getFilesDir().toPath();
        Path rom = imageAttached ? files.resolve("roms").resolve(id) : null;
        Path profile = imageAttached ? rom.resolve("data") : files.resolve("lineage-test");
        buildLayout();
        new Thread(() -> launch(profile, rom), "droidlab-guest").start();
    }

    private void buildLayout() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xff101815);
        if (Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                page.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra(TITLE));
        title.setMaxLines(2);
        title.setTextSize(20);
        title.setPadding(dp(14), dp(10), dp(14), 0);
        page.addView(title);
        status = new TextView(this);
        status.setText("Preparando máquina virtual…");
        status.setTextSize(14);
        status.setPadding(dp(14), dp(6), dp(14), dp(10));
        page.addView(status);
        display = new VmDisplay(this);
        page.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        command(buttons, "↑", "Arriba", () -> display.key(0xff52));
        command(buttons, "↓", "Abajo", () -> display.key(0xff54));
        command(buttons, "Enter", "Confirmar", () -> display.key(0xff0d));
        command(buttons, "Esc", "Volver en el invitado", () -> display.key(0xff1b));
        command(buttons, "⌨", "Teclado del invitado", display::keyboard);
        command(buttons, "Registro", "Ver el registro del invitado", this::showLog);
        command(buttons, "Salir", "Cerrar la máquina virtual", this::stopGuest);
        page.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(page);
    }

    private void launch(Path profile, Path rom) {
        try {
            VirtualBase.prepare(getApplicationContext(), profile, imageAttached, this::stage);
            if (stopping) return;
            Path disk = null;
            if (rom != null) {
                if (!Files.isRegularFile(rom.resolve("system.img"))) throw new IOException("Falta system.img.");
                message("Preparando el disco de sistema…");
                disk = GuestDisk.prepare(rom.resolve("system.img"), profile);
            }
            if (stopping) return;
            message("Arrancando Android virtual…");
            try (GuestMachine machine = GuestMachine.start(getApplicationContext(), profile, disk)) {
                guest = machine;
                if (stopping) return;
                try {
                    display.showGuest(machine.connectDisplay(), () -> message(imageAttached
                            ? "Android virtual · espera la pantalla de inicio."
                            : "LineageOS virtual · espera la pantalla de inicio."));
                } finally {
                    finalLog = machine.guestLog() + "\n" + machine.diagnostic();
                }
                if (!stopping) message("La máquina se cerró.");
            }
        } catch (IOException error) {
            if (!stopping) message("La máquina se detuvo.\n" + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            guest = null;
            BUSY.set(false);
        }
    }

    private void stage(String stage, long bytes, long total) {
        message(stage + " · " + Formatter.formatFileSize(this, bytes) + " de "
                + Formatter.formatFileSize(this, total));
    }

    private void message(String value) {
        runOnUiThread(() -> {
            if (!isDestroyed()) status.setText(value);
        });
    }

    private void command(LinearLayout row, String title, String description, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void showLog() {
        String lines = finalLog;
        GuestMachine current = guest;
        if (current != null) {
            try {
                lines = current.guestLog() + "\n" + current.diagnostic();
            } catch (IOException error) {
                lines = error.getMessage();
            }
        }
        TextView text = new TextView(this);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        text.setText(lines == null || lines.trim().isEmpty() ? "Todavía no hay mensajes." : lines);
        text.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Registro de la máquina").setView(scroll)
                .setPositiveButton("Cerrar", null).show();
    }

    private void stopGuest() {
        new AlertDialog.Builder(this).setTitle("Cerrar máquina virtual")
                .setMessage("Si Android está abierto, termina de guardar tus cambios antes de salir.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar", (dialog, value) -> finish())
                .show();
    }

    @Override
    public void onBackPressed() {
        stopGuest();
    }

    @Override
    protected void onDestroy() {
        if (holdsLease) {
            stopping = true;
            GuestMachine running = guest;
            if (running != null) new Thread(running::close, "droidlab-guest-stop").start();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
