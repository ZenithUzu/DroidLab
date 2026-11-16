package com.zenithuzu.droidlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements RomLibrary.Observer {
    private static final int PICK_ROMS = 1;
    private static final int BG = 0xff101815;
    private static final int SURFACE = 0xff1b2923;
    private static final int TEXT = 0xffedf7f0;
    private static final int MUTED = 0xffacc0b5;
    private static final int MINT = 0xffa4f6ce;

    private RomLibrary library;
    private Button importButton;
    private ScrollView scroll;
    private TextView count;
    private TextView feedback;
    private LinearLayout importPanel;
    private TextView importPosition;
    private TextView importFile;
    private TextView importStage;
    private TextView importBytes;
    private ProgressBar progress;
    private LinearLayout cards;
    private boolean working;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        library = RomLibrary.get(this);

        LinearLayout root = column();
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(12), dp(18), dp(8));
        TextView title = label("DroidLab", 32, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        importButton = button("+ Importar", true, this::pickRoms);
        header.addView(importButton, new LinearLayout.LayoutParams(-2, -2));
        Button overflow = button("⋮", false, null);
        overflow.setContentDescription("Opciones de DroidLab");
        overflow.setOnClickListener(this::showOptions);
        LinearLayout.LayoutParams overflowLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        overflowLayout.leftMargin = dp(6);
        header.addView(overflow, overflowLayout);
        root.addView(header);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = column();
        content.setPadding(dp(22), dp(16), dp(22), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(label("Mis ROMs", 23, TEXT, true));
        count = label("", 14, MUTED, false);
        add(content, count, 4);
        feedback = label("", 14, MINT, false);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setTextIsSelectable(true);
        add(content, feedback, 8);
        add(content, buildImportPanel(), 16);
        cards = column();
        add(content, cards, 16);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        library.observe(this);
    }

    @Override
    protected void onStop() {
        library.unobserve(this);
        super.onStop();
    }

    private void pickRoms() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, PICK_ROMS);
        } catch (android.content.ActivityNotFoundException error) {
            feedback.setText("No se encontró un selector de archivos.");
            feedback.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ROMS || result != RESULT_OK || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData selection = data.getClipData();
        if (selection != null) {
            for (int index = 0; index < selection.getItemCount(); index++) {
                Uri uri = selection.getItemAt(index).getUri();
                if (uri != null && !uris.contains(uri)) {
                    uris.add(uri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) {
            library.importRoms(uris);
        }
    }

    @Override
    public void onLibraryChanged(List<RomLibrary.Rom> roms, boolean busy, String status) {
        working = busy;
        importButton.setEnabled(!busy);
        feedback.setText(status);
        feedback.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        count.setText(roms.size() == 1 ? "1 ROM en tu biblioteca" : roms.size() + " ROMs en tu biblioteca");
        cards.removeAllViews();
        if (roms.isEmpty()) {
            if (!busy) {
                showEmpty();
            }
            return;
        }

        int columns = getResources().getConfiguration().screenWidthDp >= 360 ? 2 : 1;
        for (int first = 0; first < roms.size(); first += columns) {
            LinearLayout row = new LinearLayout(this);
            for (int position = 0; position < columns; position++) {
                int index = first + position;
                View cell = index < roms.size() ? romCard(roms.get(index), index) : new View(this);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                layout.leftMargin = position == 0 ? 0 : dp(12);
                row.addView(cell, layout);
            }
            add(cards, row, first == 0 ? 0 : 12);
        }
    }

    private LinearLayout buildImportPanel() {
        importPanel = column();
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff234139, SURFACE});
        panel.setCornerRadius(dp(22));
        panel.setStroke(dp(1), 0xff3b6456);
        importPanel.setBackground(panel);
        importPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
        importPanel.setVisibility(View.GONE);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("Importación en curso", 17, TEXT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        importPosition = label("", 13, MINT, true);
        importPosition.setPadding(dp(8), 0, 0, 0);
        heading.addView(importPosition);
        importPanel.addView(heading);

        importFile = label("", 15, TEXT, false);
        importFile.setMaxLines(2);
        importFile.setEllipsize(TextUtils.TruncateAt.END);
        add(importPanel, importFile, 8);
        importStage = label("", 14, MINT, false);
        importStage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(importPanel, importStage, 10);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminateTintList(ColorStateList.valueOf(MINT));
        progress.setProgressTintList(ColorStateList.valueOf(MINT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff426154));
        progress.setContentDescription("Progreso de la etapa de importación");
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(12));
        bar.topMargin = dp(6);
        importPanel.addView(progress, bar);
        importBytes = label("", 13, MUTED, false);
        add(importPanel, importBytes, 5);
        return importPanel;
    }

    @Override
    public void onImportProgress(RomLibrary.ImportProgress update) {
        if (update == null) {
            importPanel.setVisibility(View.GONE);
            return;
        }
        feedback.setVisibility(View.GONE);
        if (importPanel.getVisibility() != View.VISIBLE) {
            importPanel.setVisibility(View.VISIBLE);
            scroll.smoothScrollTo(0, 0);
        }
        setIfChanged(importPosition, update.position + " de " + update.count);
        setIfChanged(importFile, update.file);
        setIfChanged(importStage, update.stage.label);
        boolean knownTotal = update.total > 0;
        progress.setIndeterminate(!knownTotal);
        if (knownTotal) {
            int percent = (int) Math.min(100L, update.bytes * 100 / update.total);
            progress.setProgress(percent);
            importBytes.setText(Formatter.formatFileSize(this, update.bytes) + " de "
                    + Formatter.formatFileSize(this, update.total) + " · " + percent + " %");
        } else if (update.bytes > 0) {
            importBytes.setText(Formatter.formatFileSize(this, update.bytes) + " procesados");
        }
        importBytes.setVisibility(knownTotal || update.bytes > 0 ? View.VISIBLE : View.GONE);
    }

    private void setIfChanged(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private void showEmpty() {
        LinearLayout empty = column();
        empty.setBackground(surface(SURFACE, 22));
        empty.setPadding(dp(20), dp(22), dp(20), dp(20));
        empty.addView(label("Importa tu primera GSI", 20, TEXT, true));
        add(empty, label("Selecciona una o varias imágenes .img, .img.xz o ZIP desde Archivos.",
                15, MUTED, false), 8);
        cards.addView(empty);
    }

    private View romCard(RomLibrary.Rom rom, int index) {
        LinearLayout card = column();
        card.setBackground(surface(SURFACE, 20));
        card.setClipToOutline(true);

        int[][] colors = {{0xff276c56, 0xff173c34}, {0xff215b69, 0xff173840},
                {0xff485e3f, 0xff293c2b}};
        GradientDrawable cover = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                colors[index % colors.length]);
        FrameLayout artwork = new FrameLayout(this);
        artwork.setBackground(cover);
        TextView initials = label("GSI", 32, TEXT, true);
        initials.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams artworkText = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.START);
        artworkText.setMargins(dp(14), 0, 0, dp(10));
        artwork.addView(initials, artworkText);
        Button menu = button("⋮", false, null);
        menu.setContentDescription("Opciones de " + rom.title);
        menu.setEnabled(!working);
        menu.setOnClickListener(view -> showRomMenu(rom, view));
        artwork.addView(menu, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.TOP));
        card.addView(artwork, new LinearLayout.LayoutParams(-1, dp(94)));

        LinearLayout details = column();
        details.setPadding(dp(12), dp(10), dp(12), dp(12));
        TextView name = label(rom.title, 17, TEXT, true);
        name.setMaxLines(2);
        name.setMinLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        add(details, label(Formatter.formatFileSize(this, rom.size), 13, MUTED, false), 2);
        add(details, label("Importada", 13, MINT, true), 5);
        card.addView(details);
        details.setFocusable(true);
        details.setOnClickListener(view -> showRomDetails(rom));
        return card;
    }

    private void showOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Bases virtuales").setOnMenuItemClickListener(item -> {
            showVirtualBases(null);
            return true;
        });
        menu.getMenu().add("Comprobar motor integrado").setOnMenuItemClickListener(item -> {
            probeNativeEngine();
            return true;
        });
        menu.getMenu().add("Motor experimental (Termux)").setOnMenuItemClickListener(item -> {
            startActivity(new Intent(this, LegacyActivity.class));
            return true;
        });
        menu.show();
    }

    private void showVirtualBases(RomLibrary.Rom pendingRom) {
        boolean installed = VirtualBase.installed(this);
        new AlertDialog.Builder(this).setTitle("Base virtual Android")
                .setMessage(installed ? VirtualBase.LABEL + " instalada"
                        : VirtualBase.LABEL + "\nDescarga desde GitHub: 1.00 GB.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(installed ? (pendingRom == null ? "Abrir LineageOS" : "Arrancar") : "Instalar", (dialog, value) -> {
                    if (installed) openMachine(pendingRom);
                    else installVirtualBase(pendingRom);
                }).show();
    }

    private void installVirtualBase(RomLibrary.Rom pendingRom) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Instalando base virtual")
                .setMessage("Preparando descarga…").setCancelable(false).show();
        new Thread(() -> {
            String problem = null;
            try {
                VirtualBase.install(getApplicationContext(), (stage, bytes, total) ->
                        runOnUiThread(() -> {
                            if (!isDestroyed()) dialog.setMessage(stage + " · "
                                    + Formatter.formatFileSize(this, bytes) + " de "
                                    + Formatter.formatFileSize(this, total));
                        }));
            } catch (java.io.IOException error) {
                problem = error.getMessage();
            }
            String failure = problem;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                dialog.dismiss();
                if (failure != null) {
                    new AlertDialog.Builder(this).setTitle("No se pudo instalar la base")
                            .setMessage(failure).setPositiveButton("Cerrar", null).show();
                } else if (pendingRom != null && !working) {
                    openMachine(pendingRom);
                } else {
                    showVirtualBases(null);
                }
            });
        }, "droidlab-base").start();
    }

    private void openMachine(RomLibrary.Rom rom) {
        if (!VirtualBase.installed(this)) {
            showVirtualBases(rom);
            return;
        }
        Intent intent = new Intent(this, VmActivity.class)
                .putExtra(VmActivity.TITLE, rom == null ? VirtualBase.LABEL : rom.title);
        if (rom != null) intent.putExtra(VmActivity.ID, rom.directory.getFileName().toString());
        startActivity(intent);
    }

    private void probeNativeEngine() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Motor integrado")
                .setMessage("Iniciando una máquina ARM64 de prueba…")
                .setPositiveButton("Cerrar", null)
                .show();
        new Thread(() -> {
            String message;
            try {
                message = NativeEngine.probe(getApplicationContext());
            } catch (java.io.IOException error) {
                message = error.getMessage();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                message = "Se interrumpió la comprobación.";
            }
            String result = message;
            runOnUiThread(() -> {
                if (!isDestroyed() && dialog.isShowing()) {
                    dialog.setMessage(result);
                }
            });
        }, "droidlab-engine-probe").start();
    }

    private void showRomMenu(RomLibrary.Rom rom, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Arrancar").setOnMenuItemClickListener(item -> {
            if (!working) openMachine(rom);
            return true;
        });
        menu.getMenu().add("Ver detalles").setOnMenuItemClickListener(item -> {
            showRomDetails(rom);
            return true;
        });
        menu.getMenu().add("Renombrar").setOnMenuItemClickListener(item -> {
            if (!working) rename(rom);
            return true;
        });
        menu.getMenu().add("Eliminar").setOnMenuItemClickListener(item -> {
            if (!working) delete(rom);
            return true;
        });
        menu.show();
    }

    private void showRomDetails(RomLibrary.Rom rom) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scrollDetails = new ScrollView(this);
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(20), dp(20), dp(14));
        scrollDetails.addView(panel);
        panel.addView(label("Detalles de la ROM", 21, TEXT, true));
        add(panel, label(rom.title, 17, TEXT, true), 10);
        add(panel, label(rom.format + " · " + Formatter.formatFileSize(this, rom.size),
                14, MUTED, false), 6);

        add(panel, label("Archivo de origen", 14, MUTED, true), 18);
        TextView source = label(rom.source, 14, TEXT, false);
        source.setTextIsSelectable(true);
        add(panel, source, 4);

        add(panel, button(VirtualBase.installed(this) ? "Arrancar" : "Instalar base virtual",
                true, () -> {
                    dialog.dismiss();
                    if (!working) openMachine(rom);
                }), 18);

        Button close = button("Cerrar", false, dialog::dismiss);
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(-2, -2);
        closeLayout.gravity = Gravity.END;
        closeLayout.topMargin = dp(18);
        panel.addView(close, closeLayout);

        dialog.setContentView(scrollDetails);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new InsetDrawable(surface(SURFACE, 24), dp(22), dp(12),
                    dp(22), dp(12)));
            window.setLayout(-1, -2);
        }
    }

    private void rename(RomLibrary.Rom rom) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(120)});
        input.setText(rom.title);
        input.selectAll();
        LinearLayout box = column();
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Renombrar ROM")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("Escribe un nombre.");
                return;
            }
            library.rename(rom, name);
            dialog.dismiss();
        });
    }

    private void delete(RomLibrary.Rom rom) {
        new AlertDialog.Builder(this)
                .setTitle("¿Eliminar " + rom.title + "?")
                .setMessage("Se borrarán la copia importada y los datos de este perfil en DroidLab. "
                        + "El archivo de origen seguirá en Archivos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, button) -> library.delete(rom))
                .show();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(primary ? 13 : 4), 0, dp(primary ? 13 : 4), 0);
        button.setTextColor(primary ? BG : TEXT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x4495bdab),
                surface(primary ? MINT : Color.TRANSPARENT, 14), surface(Color.WHITE, 14)));
        if (action != null) {
            button.setOnClickListener(view -> action.run());
        }
        return button;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View view, int topMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(topMargin);
        parent.addView(view, layout);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
