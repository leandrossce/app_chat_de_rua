package com.capongasoftware.chat_de_rua;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/** Exercises the real first-launch picker, live UI updates, and restart persistence. */
final class LanguageChecks {
    private final Instrumentation tests;
    private final Context target;
    private int passed;

    LanguageChecks(Instrumentation tests, Context target) {
        this.tests = tests;
        this.target = target;
    }

    int run() throws Exception {
        SharedPreferences language = target.getSharedPreferences("app_language", 0);
        String previous = language.getString("language_tag", null);
        SharedPreferences bluetooth = target.getSharedPreferences("bluetooth_state", 0);
        long previousSuspension = bluetooth.getLong("automatic_start_suspended_until", 0);
        language.edit().clear().commit();
        bluetooth.edit().putLong("automatic_start_suspended_until",
                System.currentTimeMillis() + 3600000).commit();
        Activity activity = null;
        try {
            activity = launch();
            final Activity first = activity;
            ui(() -> {
                AlertDialog picker = (AlertDialog) field(first, "languageDialog");
                check(picker != null && picker.isShowing(), "first launch asks for language");
                check(AppLanguage.saved(first) == null, "suggestion is not an implicit choice");
                check(!(Boolean) field(first, "bluetoothReceiverRegistered"),
                        "Bluetooth startup waits for language selection");
                picker.getListView().performItemClick(null, 3, 3);
                check("继续".contentEquals(picker.getButton(AlertDialog.BUTTON_POSITIVE).getText()),
                        "selection previews the confirmation in Chinese");
                check(AppLanguage.saved(first) == null, "preview does not save an unconfirmed choice");
                picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            Object[] worker = {null};
            int[] count = {0};
            CharSequence[] cachedStatus = {null};
            ui(() -> {
                check("zh-CN".equals(AppLanguage.saved(first)), "first choice saved");
                // Stop background status updates so the following UI assertions are deterministic.
                set(first, "activityVisible", false);
                call(first, "cancelAutomaticConversationCountdown", new Class<?>[0]);
                checkText(first, R.id.createConversationButton, "创建聊天室");
                checkText(first, R.id.languageButton, "语言");
                check("输入消息".contentEquals(((EditText) first.findViewById(R.id.messageInput)).getHint()),
                        "XML input hint follows initial choice");
                call(first, "appendSentBubble", new Class<?>[]{String.class}, "You");
                call(first, "appendSentBubble", new Class<?>[]{String.class}, "Olá 世界 Hallo");
                ((EditText) first.findViewById(R.id.messageInput)).setText("Draft 测试");
                ((EditText) first.findViewById(R.id.passphraseInput)).setText("PrivatePassword123!");
                ((EditText) first.findViewById(R.id.nicknameInput)).setText("Ana");
                call(first, "retainChatWithoutConnection", new Class<?>[]{CharSequence.class},
                        LocalizedText.text(first, R.string.status_disconnected));
                LinearLayout messages = first.findViewById(R.id.messageList);
                LinkedHashMap<?, ?> selections = (LinkedHashMap<?, ?>) field(first, "messageSelections");
                ((CheckBox) selections.values().iterator().next()).setChecked(true);
                worker[0] = field(first, "sendQueue");
                count[0] = messages.getChildCount();
                cachedStatus[0] = LocalizedText.text(first, R.string.status_connection_closed);
            });
            String[] open = {"Abrir Sala", "Open Room", "Raum öffnen", "创建聊天室", "Abrir sala"};
            String[] sender = {"Você", "You", "Du", "你", "Tú"};
            for (int localeIndex = 0; localeIndex < AppLanguage.TAGS.length; localeIndex++) {
                final int i = localeIndex;
                ui(() -> {
                    first.findViewById(R.id.languageButton).performClick();
                    choose(first, i);
                });
                ui(() -> {
                    LinearLayout messages = first.findViewById(R.id.messageList);
                    LinkedHashMap<?, ?> selections = (LinkedHashMap<?, ?>) field(first, "messageSelections");
                    checkText(first, R.id.createConversationButton, open[i]);
                    check(AppLanguage.TAGS[i].equals(AppLanguage.saved(first)), "choice persisted: " + i);
                    check(AppLanguage.TAGS[i].equals(first.getResources().getConfiguration()
                            .getLocales().get(0).toLanguageTag()), "exact locale applied: " + i);
                    check(messages.getChildCount() == count[0] && field(first, "sendQueue") == worker[0],
                            "chat views and worker survive language change: " + i);
                    LinearLayout bubble = (LinearLayout) messages.getChildAt(1);
                    LinearLayout header = (LinearLayout) bubble.getChildAt(0);
                    check(sender[i].contentEquals(((TextView) header.getChildAt(0)).getText()),
                            "existing sender label translated: " + i);
                    check("You".contentEquals(((TextView) bubble.getChildAt(1)).getText()),
                            "message matching an app label remains intact: " + i);
                    check("Draft 测试".contentEquals(((EditText) first.findViewById(R.id.messageInput)).getText()),
                            "draft preserved: " + i);
                    check("PrivatePassword123!".contentEquals(((EditText) first.findViewById(R.id.passphraseInput)).getText()),
                            "password preserved: " + i);
                    check("Ana".contentEquals(((EditText) first.findViewById(R.id.nicknameInput)).getText()),
                            "nickname preserved: " + i);
                    check(((CheckBox) selections.values().iterator().next()).isChecked(),
                            "message selection preserved: " + i);
                    check(first.getResources().getQuantityString(R.plurals.selected_message_count, 1, 1)
                            .contentEquals(((TextView) first.findViewById(R.id.selectedMessageCount)).getText()),
                            "existing plural translated: " + i);
                    check(first.getString(R.string.status_disconnected).contentEquals(
                            ((TextView) first.findViewById(R.id.chatConnectionStatusView)).getText()),
                            "existing connection status translated: " + i);
                    check((first.getString(R.string.bluetooth_room_name) + " - Ana").equals(
                            call(first, "buildDiscoverableBluetoothName", new Class<?>[]{String.class}, "Ana")),
                            "advertised room name follows locale: " + i);
                    CharSequence nested = LocalizedText.text(first, R.string.status_connected,
                            LocalizedText.text(first, R.string.nearby_device_fallback));
                    call(first, "updateConnectionUi", new Class<?>[]{CharSequence.class}, nested);
                    call(first, "retainChatWithoutConnection", new Class<?>[]{CharSequence.class}, cachedStatus[0]);
                    check(first.getString(R.string.status_connection_closed).contentEquals(
                            ((TextView) first.findViewById(R.id.statusView)).getText()),
                            "late connection callback uses current language: " + i);
                    call(first, "retainChatWithoutConnection", new Class<?>[]{CharSequence.class},
                            LocalizedText.text(first, R.string.status_disconnected));
                });
            }
            ui(() -> {
                // Canceling a later choice must not overwrite the saved preference.
                first.findViewById(R.id.languageButton).performClick();
                AlertDialog pickerAgain = (AlertDialog) field(first, "languageDialog");
                pickerAgain.getListView().performItemClick(null, 1, 1);
                pickerAgain.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            ui(() -> {
                check("es-ES".equals(AppLanguage.saved(first)), "cancel keeps previous language");
                // A device locale change must not replace the explicit app language or its chat.
                first.onConfigurationChanged(new Configuration(first.getResources().getConfiguration()));
                checkText(first, R.id.createConversationButton, "Abrir sala");
                for (String name : new String[]{"Sala", "Room - Ana", "Raum", "聊天室 - 小明", "Conversar"}) {
                    check((Boolean) call(first, "isConversationBluetoothName", new Class<?>[]{String.class}, name),
                            "room discovery recognizes all languages: " + name);
                }
            });
            ui(first::finish);
            tests.waitForIdleSync();
            activity = launch();
            final Activity restarted = activity;
            ui(() -> {
                check(field(restarted, "languageDialog") == null, "restart does not ask again");
                checkText(restarted, R.id.createConversationButton, "Abrir sala");
                check("es-ES".equals(AppLanguage.saved(restarted)), "restart restores Spanish");
            });
            return passed;
        } finally {
            if (activity != null) {
                final Activity last = activity;
                ui(last::finish);
            }
            if (previous == null) language.edit().clear().commit();
            else language.edit().putString("language_tag", previous).commit();
            bluetooth.edit().putLong("automatic_start_suspended_until", previousSuspension).commit();
        }
    }

    private Activity launch() {
        Activity activity = tests.startActivitySync(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tests.waitForIdleSync();
        return activity;
    }

    private void choose(Activity activity, int index) throws Exception {
        AlertDialog picker = (AlertDialog) field(activity, "languageDialog");
        picker.getListView().performItemClick(null, index, index);
        picker.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    private void checkText(Activity activity, int id, String expected) {
        check(expected.contentEquals(((TextView) activity.findViewById(id)).getText()), expected);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private interface Checked { void run() throws Exception; }
    private void ui(Checked action) throws Exception {
        Throwable[] error = {null};
        tests.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable failure) { error[0] = failure; }
        });
        if (error[0] != null) throw new AssertionError(error[0]);
        tests.waitForIdleSync();
    }

    private static Object field(Activity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static void set(Activity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private static Object call(Activity activity, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, args);
    }
}
