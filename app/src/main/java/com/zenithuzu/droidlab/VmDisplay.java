package com.zenithuzu.droidlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.LocalSocket;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class VmDisplay extends View {
    private static final int MAX_EDGE = 4096;
    private static final int MAX_PIXELS = 8 * 1024 * 1024;
    private final Object frame = new Object();
    private final RectF viewport = new RectF();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private volatile Connection connection;
    private long lastPointerMove;

    VmDisplay(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setFocusableInTouchMode(true);
        setContentDescription("Pantalla de Android virtual");
    }

    void showGuest(LocalSocket socket, Runnable firstFrame) throws IOException {
        try (LocalSocket stream = socket) {
            Connection current = new Connection(stream);
            current.handshake();
            connection = current;
            boolean first = true;
            while (!Thread.currentThread().isInterrupted()) {
                if (current.refresh()) {
                    postInvalidate();
                    if (first) {
                        firstFrame.run();
                        first = false;
                    }
                }
            }
        } finally {
            connection = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        synchronized (frame) {
            if (bitmap == null) return;
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(),
                    getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            float x = (getWidth() - width) / 2;
            float y = (getHeight() - height) / 2;
            viewport.set(x, y, x + width, y + height);
            canvas.drawBitmap(bitmap, null, viewport, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        Connection current = connection;
        if (current == null || viewport.isEmpty()) return false;
        int eventType = event.getActionMasked();
        if (eventType != MotionEvent.ACTION_DOWN && eventType != MotionEvent.ACTION_UP
                && eventType != MotionEvent.ACTION_MOVE && eventType != MotionEvent.ACTION_CANCEL) return false;
        if (eventType == MotionEvent.ACTION_MOVE) {
            if (event.getEventTime() - lastPointerMove < 24) return true;
            lastPointerMove = event.getEventTime();
        }
        int x;
        int y;
        synchronized (frame) {
            if (bitmap == null) return false;
            x = Math.max(0, Math.min(bitmap.getWidth() - 1,
                    (int) ((event.getX() - viewport.left) * bitmap.getWidth() / viewport.width())));
            y = Math.max(0, Math.min(bitmap.getHeight() - 1,
                    (int) ((event.getY() - viewport.top) * bitmap.getHeight() / viewport.height())));
        }
        current.pointer(x, y, eventType == MotionEvent.ACTION_DOWN || eventType == MotionEvent.ACTION_MOVE);
        if (eventType == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    void keyboard() {
        requestFocus();
        InputMethodManager inputMethod = getContext().getSystemService(InputMethodManager.class);
        if (inputMethod != null) inputMethod.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                text.codePoints().forEach(code -> {
                    if (code == '\n') key(0xff0d);
                    else if (code >= 32 && code <= 126) key(code);
                });
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int index = 0; index < Math.min(32, beforeLength); index++) key(0xff08);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                int keysym = 0;
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) keysym = 0xff08;
                else if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) keysym = 0xff0d;
                if (keysym == 0) return super.sendKeyEvent(event);
                if (event.getAction() == KeyEvent.ACTION_DOWN) key(keysym);
                return true;
            }
        };
    }

    void key(int keysym) {
        Connection current = connection;
        if (current != null) current.key(keysym);
    }

    private void resize(int width, int height) throws IOException {
        if (width < 1 || width > MAX_EDGE || height < 1 || height > MAX_EDGE
                || width * height > MAX_PIXELS) throw new IOException("Resolución virtual no admitida.");
        synchronized (frame) {
            if (bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height) return;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private final class Connection {
        private final LocalSocket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private int width;
        private int height;
        private boolean incremental;
        private byte[] bytes = new byte[0];
        private int[] row = new int[0];

        Connection(LocalSocket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void handshake() throws IOException {
            byte[] version = new byte[12];
            input.readFully(version);
            String server = new String(version, StandardCharsets.US_ASCII);
            if (!server.equals("RFB 003.008\n") && !server.equals("RFB 003.007\n")) {
                throw new IOException("Versión de pantalla virtual no admitida.");
            }
            output.write(version);
            output.flush();
            int count = input.readUnsignedByte();
            boolean local = false;
            for (int index = 0; index < count; index++) local |= input.readUnsignedByte() == 1;
            if (!local) throw new IOException("QEMU no ofreció pantalla local.");
            output.writeByte(1);
            output.flush();
            if (server.equals("RFB 003.008\n") && input.readInt() != 0) {
                throw new IOException("QEMU rechazó la pantalla local.");
            }
            output.writeByte(1);
            output.flush();
            dimensions(input.readUnsignedShort(), input.readUnsignedShort());
            byte[] format = new byte[16];
            input.readFully(format);
            discard(input.readInt());
            output.write(new byte[] {0, 0, 0, 0, 32, 24, 0, 1, 0, (byte) 255,
                    0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
            output.writeByte(2);
            output.writeByte(0);
            output.writeShort(2);
            output.writeInt(0);
            output.writeInt(-223);
            output.flush();
        }

        boolean refresh() throws IOException {
            boolean full = !incremental;
            synchronized (output) {
                output.writeByte(3);
                output.writeByte(full ? 0 : 1);
                output.writeInt(0);
                output.writeShort(width);
                output.writeShort(height);
                output.flush();
            }
            while (true) {
                switch (input.readUnsignedByte()) {
                    case 0:
                        incremental = true;
                        input.readUnsignedByte();
                        int rectangles = input.readUnsignedShort();
                        for (int index = 0; index < rectangles; index++) readRectangle();
                        return rectangles > 0;
                    case 2:
                        break;
                    case 3:
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                        discard(input.readInt());
                        break;
                    default:
                        throw new IOException("Respuesta de pantalla desconocida.");
                }
            }
        }

        private void readRectangle() throws IOException {
            int x = input.readUnsignedShort(), y = input.readUnsignedShort();
            int w = input.readUnsignedShort(), h = input.readUnsignedShort();
            int format = input.readInt();
            if (format == -223) {
                dimensions(w, h);
                incremental = false;
                return;
            }
            if (format != 0 || w == 0 || h == 0 || x + w > width || y + h > height) {
                throw new IOException("Actualización de pantalla inválida.");
            }
            if (row.length < w) {
                row = new int[w];
                bytes = new byte[w * 4];
            }
            for (int line = 0; line < h; line++) {
                input.readFully(bytes, 0, w * 4);
                for (int pixel = 0; pixel < w; pixel++) {
                    int i = pixel * 4;
                    row[pixel] = 0xff000000 | (bytes[i + 2] & 255) << 16
                            | (bytes[i + 1] & 255) << 8 | (bytes[i] & 255);
                }
                synchronized (frame) {
                    bitmap.setPixels(row, 0, w, x, y + line, w, 1);
                }
            }
        }

        private void dimensions(int newWidth, int newHeight) throws IOException {
            resize(newWidth, newHeight);
            width = newWidth;
            height = newHeight;
        }

        private void discard(int size) throws IOException {
            if (size < 0 || size > 65536) throw new IOException("Respuesta de pantalla demasiado grande.");
            byte[] ignored = new byte[size];
            input.readFully(ignored);
        }

        void pointer(int x, int y, boolean down) {
            synchronized (output) {
                try {
                    output.writeByte(5);
                    output.writeByte(down ? 1 : 0);
                    output.writeShort(x);
                    output.writeShort(y);
                    output.flush();
                } catch (IOException error) {
                    disconnect();
                }
            }
        }

        void key(int keysym) {
            synchronized (output) {
                try {
                    for (int pressed = 1; pressed >= 0; pressed--) {
                        output.writeByte(4);
                        output.writeByte(pressed);
                        output.writeShort(0);
                        output.writeInt(keysym);
                    }
                    output.flush();
                } catch (IOException error) {
                    disconnect();
                }
            }
        }

        private void disconnect() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The receive loop reports a disconnected guest.
            }
        }
    }
}
