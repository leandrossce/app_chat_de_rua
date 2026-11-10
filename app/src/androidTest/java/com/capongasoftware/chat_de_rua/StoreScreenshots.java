package com.capongasoftware.chat_de_rua;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Renders real app views with fictional content; present only in the test APK. */
final class StoreScreenshots {
    static void capture(Instrumentation tests, Context context) throws Exception {
        File output = new File(context.getFilesDir(), "play-store");
        if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Output directory");
        SharedPreferences preferences = context.getSharedPreferences("bluetooth_state", 0);
        long suspended = preferences.getLong("automatic_start_suspended_until", 0);
        preferences.edit().putLong("automatic_start_suspended_until",
                System.currentTimeMillis() + 3600000).commit();
        Activity activity = tests.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tests.waitForIdleSync();
        StickerStore store = new StickerStore(context);
        List<StickerStore.Entry> added = new ArrayList<>();
        try {
            ui(tests, () -> {
                field(activity, "activityVisible", false);
                invoke(activity, "cancelAutomaticConversationCountdown", new Class<?>[0]);
                invoke(activity, "updateConnectionUi", new Class<?>[]{CharSequence.class},
                        activity.getString(R.string.status_waiting));
                ((EditText) activity.findViewById(R.id.nicknameInput)).setText("Praça Central");
                activity.getWindow().getDecorView().clearFocus();
            });
            snapshot(tests, activity.getWindow().getDecorView(), output, "01-inicio.png");
            ui(tests, () -> {
                ((Switch) activity.findViewById(R.id.useDefaultPasswordSwitch)).setChecked(false);
                ((EditText) activity.findViewById(R.id.passphraseInput)).setText("PracaAoEntardecer2026");
                activity.getWindow().getDecorView().clearFocus();
            });
            snapshot(tests, activity.getWindow().getDecorView(), output, "02-senha-da-sala.png");
            ui(tests, () -> {
                ((Switch) activity.findViewById(R.id.useDefaultPasswordSwitch)).setChecked(true);
                invoke(activity, "appendSentBubble", new Class<?>[]{String.class},
                        "Vamos nos encontrar na praça às 17h?");
            });
            Method encrypt = MainActivity.class.getDeclaredMethod("encryptContent", byte.class,
                    byte[].class, char[].class);
            encrypt.setAccessible(true);
            char[] password = (char[]) invoke(activity, "getActivePassword", new Class<?>[0]);
            byte[] response;
            try {
                response = (byte[]) encrypt.invoke(null, (byte) 1,
                        "Combinado! Vou levar o violão.".getBytes(java.nio.charset.StandardCharsets.UTF_8), password);
            } finally { Arrays.fill(password, '\0'); }
            ui(tests, () -> invoke(activity, "appendReceivedBubble", new Class<?>[]{byte[].class}, response));
            drain(tests, activity, "decryptQueue");
            ui(tests, () -> {
                invoke(activity, "appendSentBubble", new Class<?>[]{String.class},
                        "Até lá! A conversa funciona pelo Bluetooth, mesmo sem internet.");
                invoke(activity, "retainChatWithoutConnection", new Class<?>[]{CharSequence.class},
                        activity.getString(R.string.status_disconnected));
                invoke(activity, "showChatScreen", new Class<?>[0]);
            });
            snapshot(tests, activity.getWindow().getDecorView(), output, "03-conversa.png");
            String[] labels = {"Oi!", "Até já", "Combinado"};
            for (int i = 0; i < labels.length; i++) {
                Bitmap image = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(image);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(new int[]{0xff08665c, 0xff103d40, 0xff176b5d}[i]);
                canvas.drawRoundRect(10, 70, 374, 314, 48, 48, paint);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                paint.setTextSize(i == 2 ? 49 : 76);
                canvas.drawText(labels[i], 192, 219, paint);
                File source = new File(output, "sticker-demo-" + i + ".png");
                save(image, source);
                image.recycle();
                added.add(store.save(labels[i], StickerCodec.fromImage(context, Uri.fromFile(source), p -> { })));
                source.delete();
            }
            StickerController controller = (StickerController) field(activity, "stickerController");
            ui(tests, controller::openLibrary);
            drain(tests, activity, "mediaQueue");
            Dialog dialog = (Dialog) field(controller, "current");
            snapshot(tests, activity.getWindow().getDecorView(), dialog, output, "04-figurinhas.png");
            ui(tests, dialog::dismiss);
            ui(tests, () -> {
                invoke(activity, "showHomeScreen", new Class<?>[0]);
                activity.findViewById(R.id.languageButton).performClick();
            });
            tests.waitForIdleSync();
            android.app.AlertDialog languageDialog =
                    (android.app.AlertDialog) field(activity, "languageDialog");
            ui(tests, () -> {
                if (languageDialog == null || !languageDialog.isShowing()
                        || languageDialog.getListView().getAdapter().getCount() != 5) {
                    throw new AssertionError("Expected the real five-language picker");
                }
                for (int i = 0; i < AppLanguage.NAMES.length; i++) {
                    if (!AppLanguage.NAMES[i].equals(
                            languageDialog.getListView().getAdapter().getItem(i).toString())) {
                        throw new AssertionError("Language choice missing: " + AppLanguage.NAMES[i]);
                    }
                }
            });
            snapshot(tests, activity.getWindow().getDecorView(), languageDialog,
                    output, "05-selecao-de-idioma.png");
            ui(tests, () -> languageDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick());
            ui(tests, () -> {
                Bitmap icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                Drawable drawable = activity.getDrawable(R.drawable.ic_app);
                drawable.setBounds(0, 0, 512, 512);
                drawable.draw(new Canvas(icon));
                save(icon, new File(output, "icone-512.png"));
                icon.recycle();
            });
        } finally {
            for (StickerStore.Entry entry : added) store.delete(entry);
            tests.runOnMainSync(activity::finish);
            tests.waitForIdleSync();
            preferences.edit().putLong("automatic_start_suspended_until", suspended).commit();
        }
    }

    private static void drain(Instrumentation tests, Activity activity, String queueName) throws Exception {
        ExecutorService queue = (ExecutorService) field(activity, queueName);
        queue.submit(() -> { }).get(30, TimeUnit.SECONDS);
        tests.waitForIdleSync();
        queue.submit(() -> { }).get(30, TimeUnit.SECONDS);
        tests.waitForIdleSync();
    }

    private static void snapshot(Instrumentation tests, View decor, File output, String name) throws Exception {
        snapshot(tests, decor, null, output, name);
    }

    private static void snapshot(Instrumentation tests, View decor, Dialog dialog, File output, String name)
            throws Exception {
        tests.waitForIdleSync();
        android.os.SystemClock.sleep(300);
        ui(tests, () -> {
            Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xfff4f7f7);
            decor.draw(canvas);
            if (dialog != null) {
                Paint shade = new Paint();
                shade.setColor(0x66000000);
                canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), shade);
                View dialogView = dialog.getWindow().getDecorView();
                int[] position = new int[2];
                dialogView.getLocationOnScreen(position);
                int[] origin = new int[2];
                decor.getLocationOnScreen(origin);
                canvas.save();
                canvas.translate(position[0] - origin[0], position[1] - origin[1]);
                dialogView.draw(canvas);
                canvas.restore();
            }
            save(bitmap, new File(output, name));
            bitmap.recycle();
        });
    }

    private static void save(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("PNG");
        }
    }

    private interface Action { void run() throws Exception; }
    private static void ui(Instrumentation tests, Action action) {
        tests.runOnMainSync(() -> {
            try { action.run(); } catch (Exception failure) { throw new RuntimeException(failure); }
        });
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, arguments);
    }
}
