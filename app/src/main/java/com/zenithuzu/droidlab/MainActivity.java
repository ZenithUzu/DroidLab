package com.zenithuzu.droidlab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String TERMUX = "com.termux";
    private static final String PERMISSION = TERMUX + ".permission.RUN_COMMAND";
    private static final String TERMUX_FILES = "/data/data/com.termux/files";
    private static final String VNC_ADDRESS = "127.0.0.1:5900";
    private static final int BACKGROUND = Color.rgb(16, 24, 21);
    private static final int PANEL = Color.rgb(26, 39, 33);
    private static final int MINT = Color.rgb(164, 246, 206);
    private static final int TEXT = Color.rgb(237, 247, 240);
    private static final int MUTED = Color.rgb(168, 189, 179);
    private static final int REQUEST_TERMUX = 1;

    private EditText profile;
    private TextView feedback;
    private String pendingAction;
    private String pendingProfile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            pendingAction = state.getString("pendingAction");
            pendingProfile = state.getString("pendingProfile");
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = column();
        page.setPadding(dp(24), dp(28), dp(24), dp(24));
        scroll.addView(page);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                var edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(edges.left, edges.top, edges.right, Math.max(edges.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }

        TextView title = label("DroidLab", 44, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-0.045f);
        add(page, title, 0);
        add(page, label("Laboratorio de ROMs Android", 17, MUTED), 4);

        LinearLayout machine = card();
        add(page, machine, 28);
        heading(machine, "Máquina virtual");
        add(machine, label("Usa el nombre de un perfil preparado en Termux.", 15, MUTED), 8);
        add(machine, label("Perfil", 14, TEXT), 18);
        profile = new EditText(this);
        profile.setSingleLine(true);
        profile.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        profile.setContentDescription("Nombre del perfil");
        profile.setTextColor(TEXT);
        profile.setBackground(surface(BACKGROUND, 12));
        profile.setPadding(dp(14), dp(12), dp(14), dp(12));
        profile.setText(getPreferences(MODE_PRIVATE).getString("profile", "alpine"));
        add(machine, profile, 6);
        add(machine, button("Arrancar máquina", true, () -> runInTermux("start")), 14);
        add(machine, button("Consultar estado", false, () -> runInTermux("status")), 8);
        add(machine, button("Solicitar apagado", false, () -> runInTermux("stop")), 8);
        feedback = label("", 14, MINT);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        add(machine, feedback, 12);

        LinearLayout display = card();
        add(page, display, 14);
        heading(display, "Pantalla del invitado");
        add(display, label("Conecta un visor VNC local al arrancar la máquina.", 15, MUTED), 8);
        add(display, label(VNC_ADDRESS, 18, MINT), 10);
        add(display, button("Copiar dirección VNC", false, this::copyAddress), 10);

        LinearLayout setup = card();
        add(page, setup, 14);
        heading(setup, "Preparar el motor");
        add(setup, label("El prototipo usa QEMU en Termux. Miku UI es el objetivo de la primera prueba con Android.", 15, MUTED), 8);
        add(setup, button("Abrir guía de instalación", false, () -> open(
                "https://github.com/ZenithUzu/DroidLab#preparar-termux")), 10);
        setContentView(scroll);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingAction", pendingAction);
        state.putString("pendingProfile", pendingProfile);
    }

    private void runInTermux(String action) {
        String name = profile.getText().toString().trim();
        if (!name.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            profile.setError("Usa entre 1 y 32 letras minúsculas, números o guiones.");
            return;
        }
        try {
            getPackageManager().getPackageInfo(TERMUX, 0);
        } catch (PackageManager.NameNotFoundException e) {
            showFeedback("Instala Termux y sigue la guía de instalación.");
            return;
        }
        if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            if (getPreferences(MODE_PRIVATE).getBoolean("permissionAsked", false)
                    && !shouldShowRequestPermissionRationale(PERMISSION)) {
                showFeedback("Habilita el permiso de Termux en los ajustes de DroidLab y vuelve a intentarlo.");
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            pendingAction = action;
            pendingProfile = name;
            getPreferences(MODE_PRIVATE).edit().putBoolean("permissionAsked", true).apply();
            requestPermissions(new String[] {PERMISSION}, REQUEST_TERMUX);
            return;
        }
        dispatch(action, name);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_TERMUX && pendingAction != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                dispatch(pendingAction, pendingProfile);
            } else {
                showFeedback("DroidLab necesita permiso para iniciar QEMU en Termux.");
            }
            pendingAction = null;
            pendingProfile = null;
        }
    }

    private void dispatch(String action, String name) {
        Intent command = new Intent(TERMUX + ".RUN_COMMAND");
        command.setClassName(TERMUX, TERMUX + ".app.RunCommandService");
        command.putExtra(TERMUX + ".RUN_COMMAND_PATH", TERMUX_FILES + "/usr/bin/python3");
        command.putExtra(TERMUX + ".RUN_COMMAND_ARGUMENTS", new String[] {
                TERMUX_FILES + "/home/.droidlab/droidlab.py", action, name
        });
        command.putExtra(TERMUX + ".RUN_COMMAND_WORKDIR", TERMUX_FILES + "/home");
        command.putExtra(TERMUX + ".RUN_COMMAND_BACKGROUND", false);
        // Termux opens this terminal session through its foreground service.
        command.putExtra(TERMUX + ".RUN_COMMAND_SESSION_ACTION", "0");
        try {
            if (startService(command) == null) {
                showFeedback("Abre Termux y comprueba que el motor esté instalado.");
                return;
            }
            getPreferences(MODE_PRIVATE).edit().putString("profile", name).apply();
            showFeedback("Solicitud enviada. Consulta el resultado en Termux.");
        } catch (IllegalStateException | SecurityException e) {
            showFeedback("Termux rechazó la solicitud. Revisa el permiso y la configuración del motor.");
        }
    }

    private void showFeedback(String message) {
        feedback.setText(message);
        feedback.setVisibility(View.VISIBLE);
    }

    private void copyAddress() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Dirección VNC", VNC_ADDRESS));
        if (Build.VERSION.SDK_INT < 33) {
            android.widget.Toast.makeText(this, "Dirección copiada", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Instala un navegador para abrir la guía.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(surface(PANEL, 22));
        return panel;
    }

    private void heading(LinearLayout parent, String text) {
        TextView heading = label(text, 21, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        add(parent, heading, 0);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button control = new Button(this);
        control.setText(title);
        control.setAllCaps(false);
        control.setTextSize(15);
        control.setTextColor(primary ? BACKGROUND : TEXT);
        control.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        control.setMinHeight(dp(48));
        control.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x338AAB97),
                surface(primary ? MINT : BACKGROUND, 12), null));
        control.setOnClickListener(view -> action.run());
        return control;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(margin);
        parent.addView(view, layout);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
