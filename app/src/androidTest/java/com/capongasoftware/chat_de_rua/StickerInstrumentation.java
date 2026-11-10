package com.capongasoftware.chat_de_rua;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Platform-only instrumentation: no downloaded test libraries or changes to production behavior. */
public final class StickerInstrumentation extends Instrumentation {
    private int passed;
    private Context target;
    private boolean roomChecksOnly;
    private boolean selectionChecksOnly;
    private boolean compatibilityChecksOnly;
    private boolean storeScreenshotsOnly;
    private boolean languageChecksOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        roomChecksOnly = arguments != null && "room".equals(arguments.getString("suite"));
        selectionChecksOnly = arguments != null && "selection".equals(arguments.getString("suite"));
        compatibilityChecksOnly = arguments != null && "compatibility".equals(arguments.getString("suite"));
        storeScreenshotsOnly = arguments != null && "screenshots".equals(arguments.getString("suite"));
        languageChecksOnly = arguments != null && "language".equals(arguments.getString("suite"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String previousLanguage = null;
        try {
            target = getTargetContext();
            previousLanguage = AppLanguage.saved(target);
            if (!languageChecksOnly) AppLanguage.save(target, "pt-BR");
            if (storeScreenshotsOnly) {
                StoreScreenshots.capture(this, target);
                result.putString("stream", "\nOK: capturas salvas em files/play-store\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (languageChecksOnly) passed = new LanguageChecks(this, target).run();
            else if (compatibilityChecksOnly) runCompatibilityChecks();
            else if (selectionChecksOnly) runSelectionChecks();
            else if (roomChecksOnly) runRoomChecks(); else runChecks();
            result.putString("stream", "\nOK: " + passed + " verificações de " +
                    (languageChecksOnly ? "idiomas" : compatibilityChecksOnly ? "compatibilidade Android" :
                            selectionChecksOnly ? "seleção e exclusão de mensagens" :
                            roomChecksOnly ? "abertura de sala" : "figurinhas") + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", "\nFALHOU após " + passed + " verificações\n" + trace);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (!languageChecksOnly && target != null) {
                if (previousLanguage == null) target.getSharedPreferences("app_language", 0)
                        .edit().remove("language_tag").commit();
                else AppLanguage.save(target, previousLanguage);
            }
        }
    }

    private void runCompatibilityChecks() throws Exception {
        check(target.getApplicationInfo().targetSdkVersion == 36, "pacote segmenta API 36");
        check(target.getApplicationInfo().minSdkVersion == 24, "pacote exige API 24");
        android.content.SharedPreferences preferences = target.getSharedPreferences("bluetooth_state", 0);
        long previousSuspension = preferences.getLong("automatic_start_suspended_until", 0);
        preferences.edit().putLong("automatic_start_suspended_until",
                System.currentTimeMillis() + 3600000).commit();
        Activity activity = startActivitySync(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        try {
            selectionUi(() -> {
                setRoomField(activity, "activityVisible", false);
                invokeRoomMethod(activity, "cancelAutomaticConversationCountdown");
            });
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runOnMainSync(() -> checkSafeContent(activity.getWindow()));
            }
            // Use real system Back dispatch: API 36 no longer calls onBackPressed().
            for (int i = 0; i < 2; i++) {
                selectionUi(() -> {
                    setRoomField(activity, "chatRetainedWithoutConnection", true);
                    invokeRoomMethod(activity, "showChatScreen");
                });
                waitForIdleSync();
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                long deadline = android.os.SystemClock.uptimeMillis() + 3000;
                while (activity.findViewById(R.id.chatScreen).getVisibility() == View.VISIBLE
                        && android.os.SystemClock.uptimeMillis() < deadline) {
                    android.os.SystemClock.sleep(50);
                    waitForIdleSync();
                }
                runOnMainSync(() -> check(
                        activity.findViewById(R.id.homeScreen).getVisibility() == View.VISIBLE
                                && activity.findViewById(R.id.chatScreen).getVisibility() == View.GONE
                                && !activity.isFinishing(),
                        "Voltar do sistema retorna da conversa sem fechar o app"));
            }
            selectionUi(() -> invokeRoomMethod(activity, "showAudioRecordingDialog"));
            waitForIdleSync();
            Dialog dialog = (Dialog) getRoomField(activity, "audioRecordingDialog");
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runOnMainSync(() -> checkSafeContent(dialog.getWindow()));
            }
            runOnMainSync(dialog::dismiss);
        } finally {
            runOnMainSync(activity::finish);
            waitForIdleSync();
            preferences.edit().putLong("automatic_start_suspended_until", previousSuspension).commit();
        }
    }

    private void checkSafeContent(android.view.Window window) {
        View decor = window.getDecorView();
        android.view.ViewGroup content = window.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        android.graphics.Insets bars = decor.getRootWindowInsets().getInsets(
                android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
        int[] location = new int[2];
        int[] decorLocation = new int[2];
        root.getLocationInWindow(location);
        decor.getLocationInWindow(decorLocation);
        check(location[0] + root.getPaddingLeft() >= decorLocation[0] + bars.left
                        && location[1] + root.getPaddingTop() >= decorLocation[1] + bars.top
                        && location[0] + root.getWidth() - root.getPaddingRight()
                                <= decorLocation[0] + decor.getWidth() - bars.right
                        && location[1] + root.getHeight() - root.getPaddingBottom()
                                <= decorLocation[1] + decor.getHeight() - bars.bottom,
                "conteúdo fora das barras do sistema e do recorte da tela");
    }

    private void runRoomChecks() throws Exception {
        Activity activity = startActivitySync(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        try {
            runOnMainSync(() -> {
                try {
                    setRoomField(activity, "activityVisible", false);
                    invokeRoomMethod(activity, "cancelAutomaticConversationCountdown");
                    Button cancel = activity.findViewById(R.id.cancelAutomaticConversationButton);
                    TextView status = activity.findViewById(R.id.statusView);

                    setRoomField(activity, "automaticConversationProcess", false);
                    setRoomField(activity, "pendingDiscoverableBluetoothName", "Sala - Teste");
                    invokeRoomMethod(activity, "updateAutomaticConversationControls");
                    check(cancel.getVisibility() == View.VISIBLE &&
                            "Cancelar abertura".contentEquals(cancel.getText()),
                            "cancelamento disponível na preparação manual");

                    setRoomField(activity, "waitingForDiscoverableName", true);
                    invokeRoomMethod(activity, "updateAutomaticConversationControls");
                    check(cancel.getVisibility() == View.VISIBLE, "cancelamento durante identificação da sala");
                    setRoomField(activity, "waitingForDiscoverableName", false);
                    setRoomField(activity, "discoverabilityRequestLaunched", true);
                    invokeRoomMethod(activity, "updateAutomaticConversationControls");
                    check(cancel.getVisibility() == View.VISIBLE, "cancelamento durante pedido de visibilidade");

                    ((MainActivity) activity).onActivityResult(106, 300, null);
                    check(Boolean.TRUE.equals(getRoomField(activity, "hostingConversation")) &&
                            status.getText().toString().equals(activity.getString(R.string.status_conversation_visible, 300)),
                            "abertura manual exibe disponibilidade de 300 segundos");
                    check(cancel.getVisibility() == View.VISIBLE &&
                            "Cancelar abertura".contentEquals(cancel.getText()),
                            "cancelamento manual disponível enquanto aguarda conexão");
                    cancel.performClick();
                    check(Boolean.FALSE.equals(getRoomField(activity, "hostingConversation")) &&
                            cancel.getVisibility() == View.GONE &&
                            activity.getString(R.string.status_conversation_process_cancelled).contentEquals(status.getText()),
                            "cancelamento manual encerra a abertura e atualiza o status");
                    check(getRoomField(activity, "automaticConversationDecisionDialog") == null,
                            "cancelamento manual preserva o fluxo de cancelamento existente");

                    setRoomField(activity, "pendingDiscoverableBluetoothName", "Sala - Teste");
                    ((MainActivity) activity).onActivityResult(106, 300, null);
                    ((Runnable) getRoomField(activity, "discoverabilityTimeoutRunnable")).run();
                    check(Boolean.FALSE.equals(getRoomField(activity, "hostingConversation")) &&
                            cancel.getVisibility() == View.GONE &&
                            activity.getString(R.string.status_visibility_ended).contentEquals(status.getText()),
                            "fim do prazo encerra a disponibilidade manual");

                    setRoomField(activity, "pendingDiscoverableBluetoothName", "Sala - Teste");
                    setRoomField(activity, "discoverabilityRequestLaunched", true);
                    ((MainActivity) activity).onActivityResult(106, Activity.RESULT_CANCELED, null);
                    check(Boolean.FALSE.equals(getRoomField(activity, "hostingConversation")) &&
                            cancel.getVisibility() == View.GONE,
                            "recusa da visibilidade encerra a abertura manual");

                    setRoomField(activity, "automaticConversationProcess", true);
                    setRoomField(activity, "hostingConversation", true);
                    invokeRoomMethod(activity, "updateAutomaticConversationControls");
                    check(cancel.getVisibility() == View.VISIBLE &&
                            activity.getString(R.string.cancel_automatic_conversation).contentEquals(cancel.getText()),
                            "abertura automática conserva o nome do cancelamento");
                    cancel.performClick();
                    Dialog suggestion = (Dialog) getRoomField(activity, "automaticConversationDecisionDialog");
                    check(suggestion != null && suggestion.isShowing(),
                            "cancelamento automático conserva a sugestão de busca");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }

    private void runSelectionChecks() throws Exception {
        Activity activity = startActivitySync(new Intent(target, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        Sticker still = image("transparent.png");
        Sticker animated = image("animated.gif");
        StickerStore store = new StickerStore(target);
        StickerStore.Entry retained = store.save("Preservada ao excluir mensagens", still);
        byte[][] content = {"Texto recebido".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                asset("audio.m4a"), asset("audio.mp3"), still.bytes, animated.bytes, png()};
        byte[] types = {1, 3, 4, 5, 5, 2};
        byte[][] packets = new byte[types.length][];
        Method encrypt = MainActivity.class.getDeclaredMethod(
                "encryptContent", byte.class, byte[].class, char[].class);
        encrypt.setAccessible(true);
        char[] password = (char[]) invokeSelectionMethod(activity, "getActivePassword", new Class<?>[0]);
        try {
            for (int i = 0; i < types.length; i++) {
                packets[i] = (byte[]) encrypt.invoke(null, types[i], content[i], password);
            }
            selectionUi(() -> {
                setRoomField(activity, "activityVisible", false);
                invokeRoomMethod(activity, "cancelAutomaticConversationCountdown");
                invokeSelectionMethod(activity, "appendSentBubble", new Class<?>[]{String.class}, "Texto enviado");
                for (byte[] packet : packets) {
                    invokeSelectionMethod(activity, "appendReceivedBubble", new Class<?>[]{byte[].class}, packet.clone());
                }
                invokeSelectionMethod(activity, "retainChatWithoutConnection",
                        new Class<?>[]{CharSequence.class}, "Conversa encerrada");
                invokeRoomMethod(activity, "showChatScreen");
            });
            drainDecryption(activity);
            android.widget.LinearLayout list = activity.findViewById(R.id.messageList);
            View bar = activity.findViewById(R.id.messageSelectionBar);
            Button clear = activity.findViewById(R.id.clearChatButton);
            java.util.Map<?, ?> selections = (java.util.Map<?, ?>)
                    getRoomField(activity, "messageSelections");
            java.util.List<?> bubbles = new java.util.ArrayList<>(selections.keySet());
            android.widget.CheckBox first = (android.widget.CheckBox) selections.get(bubbles.get(1));
            selectionUi(() -> {
                check(selections.size() == 7 && list.getChildCount() == 8,
                        "seis tipos recebidos e mensagem enviada selecionáveis");
                check(((java.util.List<?>) getRoomField(activity, "pendingMessages")).isEmpty(),
                        "texto, áudio, MP3, duas figurinhas e imagem descriptografados");
                check(bar.getVisibility() == View.GONE && clear.isEnabled(),
                        "barra oculta sem seleção e Limpar disponível");
                android.widget.LinearLayout textBubble = (android.widget.LinearLayout) bubbles.get(1);
                check(((TextView) textBubble.getChildAt(1)).isTextSelectable(),
                        "texto recebido continua permitindo copiar");
                first.performClick();
                check(bar.getVisibility() == View.VISIBLE && textBubble.isSelected(),
                        "uma mensagem selecionada com destaque");
                check("1 selecionada".contentEquals(((TextView) activity.findViewById(
                        R.id.selectedMessageCount)).getText()), "contador singular");
            });
            selectionUi(() -> captureSelection(activity));
            selectionUi(() -> {
                activity.findViewById(R.id.cancelMessageSelectionButton).performClick();
                check(!first.isChecked() && bar.getVisibility() == View.GONE && list.getChildCount() == 8,
                        "cancelar seleção mantém todas as mensagens");
                first.performClick();
                activity.findViewById(R.id.deleteSelectedMessagesButton).performClick();
            });
            clickSelectionDialog(activity, "deleteSelectedMessagesDialog", false);
            selectionUi(() -> {
                check(list.getChildCount() == 8 && first.isChecked(),
                        "cancelar confirmação não exclui mensagens");
                activity.findViewById(R.id.deleteSelectedMessagesButton).performClick();
            });
            clickSelectionDialog(activity, "deleteSelectedMessagesDialog", true);
            selectionUi(() -> check(list.getChildCount() == 7 && selections.size() == 6 &&
                    bar.getVisibility() == View.GONE, "exclusão de uma mensagem recebida"));
            java.util.List<?> playbacks = (java.util.List<?>) getRoomField(activity, "audioPlaybacks");
            java.util.List<?> files = new java.util.ArrayList<>((java.util.List<?>)
                    getRoomField(activity, "temporaryAudioFiles"));
            Object audio = playbacks.get(0);
            Field playField = audio.getClass().getDeclaredField("playButton");
            playField.setAccessible(true);
            Field playerField = audio.getClass().getDeclaredField("player");
            playerField.setAccessible(true);
            selectionUi(() -> {
                check(playbacks.size() == 2 && files.size() == 2, "players de áudio e MP3 disponíveis");
                ((Button) playField.get(audio)).performClick();
                check(playerField.get(audio) != null, "áudio recebido reproduz antes da exclusão");
                for (int index = 2; index < bubbles.size(); index++) {
                    ((android.widget.CheckBox) selections.get(bubbles.get(index))).setChecked(true);
                }
                check("5 selecionadas".contentEquals(((TextView) activity.findViewById(
                        R.id.selectedMessageCount)).getText()) && clear.isEnabled(),
                        "seleção múltipla mantém Limpar disponível");
                activity.findViewById(R.id.deleteSelectedMessagesButton).performClick();
                // A message arriving while confirmation is open must survive the deletion.
                invokeSelectionMethod(activity, "appendReceivedBubble", new Class<?>[]{byte[].class},
                        (Object) packets[0].clone());
            });
            clickSelectionDialog(activity, "deleteSelectedMessagesDialog", true);
            drainDecryption(activity);
            selectionUi(() -> {
                check(list.getChildCount() == 3 && selections.size() == 2,
                        "exclusão múltipla preserva mensagem enviada e nova mensagem recebida");
                check(playbacks.isEmpty() && playerField.get(audio) == null,
                        "exclusão interrompe reprodução e libera os players");
                for (Object file : files) check(!((File) file).exists(), "arquivo temporário excluído");
                check(((java.util.List<?>) getRoomField(activity, "temporaryAudioFiles")).isEmpty(),
                        "registros de arquivos temporários removidos");
                clear.performClick();
            });
            clickSelectionDialog(activity, "clearChatDialog", false);
            selectionUi(() -> {
                check(list.getChildCount() == 3, "cancelar Limpar mantém conteúdo");
                for (Object box : selections.values()) ((android.widget.CheckBox) box).setChecked(true);
                clear.performClick();
            });
            clickSelectionDialog(activity, "clearChatDialog", true);
            selectionUi(() -> check(list.getChildCount() == 1 && selections.isEmpty() &&
                    bar.getVisibility() == View.GONE && !clear.isEnabled(),
                    "Limpar remove tudo e reinicia a seleção"));

            byte[] latePacket = (byte[]) encrypt.invoke(null, (byte) 3, content[1], password);
            // Remove synchronously while the worker cannot deliver its main-thread callback.
            selectionUi(() -> {
                invokeSelectionMethod(activity, "appendReceivedBubble", new Class<?>[]{byte[].class}, latePacket);
                Object bubble = selections.keySet().iterator().next();
                invokeSelectionMethod(activity, "removeMessage",
                        new Class<?>[]{android.widget.LinearLayout.class}, bubble);
            });
            drainDecryption(activity);
            selectionUi(() -> {
                check(list.getChildCount() == 1 && activity.findViewById(R.id.emptyStateView).getVisibility() == View.VISIBLE,
                        "exclusão durante descriptografia não restaura a mensagem e exibe estado vazio");
                check(((java.util.List<?>) getRoomField(activity, "pendingMessages")).isEmpty() &&
                        ((java.util.List<?>) getRoomField(activity, "temporaryAudioFiles")).isEmpty(),
                        "callback descartado não retém pacote nem áudio");
            });
            runSentSelectionChecks(activity, still, animated, packets[0], content[1], content[2]);
            check(store.load(retained).width == still.width, "excluir mensagens e Limpar preservam acervo");
        } finally {
            Arrays.fill(password, '\0');
            store.delete(retained);
            runOnMainSync(activity::finish);
            waitForIdleSync();
        }
    }

    @SuppressWarnings("unchecked")
    private void runSentSelectionChecks(Activity activity, Sticker still, Sticker animated,
                                         byte[] receivedPacket, byte[] audio, byte[] mp3) throws Exception {
        File[] sentFiles = new File[2];
        selectionUi(() -> {
            invokeSelectionMethod(activity, "appendSentBubble", new Class<?>[]{String.class}, "Texto enviado");
            java.util.List<File> tracked = (java.util.List<File>) getRoomField(activity, "temporaryAudioFiles");
            byte[][] bytes = {audio, mp3};
            for (int i = 0; i < bytes.length; i++) {
                sentFiles[i] = (File) invokeSelectionMethod(activity, "writeTemporaryAudioFile",
                        new Class<?>[]{byte[].class, String.class, String.class}, bytes[i],
                        "chat_audio_sent_test_", i == 0 ? ".m4a" : ".mp3");
                tracked.add(sentFiles[i]);
                invokeSelectionMethod(activity, "appendSentAudioBubble", new Class<?>[]{File.class}, sentFiles[i]);
            }
            invokeSelectionMethod(activity, "appendSentStickerBubble", new Class<?>[]{Sticker.class}, still);
            invokeSelectionMethod(activity, "appendSentStickerBubble", new Class<?>[]{Sticker.class}, animated);
            invokeSelectionMethod(activity, "appendSentImageBubble", new Class<?>[]{Bitmap.class}, still.decodeFrame(0));
            invokeSelectionMethod(activity, "appendReceivedBubble", new Class<?>[]{byte[].class}, receivedPacket.clone());
            invokeSelectionMethod(activity, "retainChatWithoutConnection", new Class<?>[]{CharSequence.class}, "Conversa encerrada");
            invokeRoomMethod(activity, "showChatScreen");
        });
        drainDecryption(activity);
        java.util.Map<?, ?> selections = (java.util.Map<?, ?>) getRoomField(activity, "messageSelections");
        java.util.List<?> bubbles = new java.util.ArrayList<>(selections.keySet());
        View sentText = (View) bubbles.get(0);
        android.widget.CheckBox first = (android.widget.CheckBox) selections.get(sentText);
        android.widget.LinearLayout list = activity.findViewById(R.id.messageList);
        selectionUi(() -> {
            check(selections.size() == 7 && list.getChildCount() == 8,
                    "texto, áudio, MP3, imagem e duas figurinhas enviadas selecionáveis");
            check(activity.getString(R.string.select_sent_message).contentEquals(first.getContentDescription()),
                    "seleção enviada identificada para acessibilidade");
            first.performClick();
            activity.findViewById(R.id.cancelMessageSelectionButton).performClick();
            android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) sentText.getBackground();
            check(!first.isChecked() && !sentText.isSelected() &&
                    background.getColor().getDefaultColor() == activity.getColor(R.color.message_mine),
                    "cancelar seleção restaura a cor da mensagem enviada");
            first.performClick();
            activity.findViewById(R.id.deleteSelectedMessagesButton).performClick();
        });
        clickSelectionDialog(activity, "deleteSelectedMessagesDialog", true);
        java.util.List<?> playbacks = (java.util.List<?>) getRoomField(activity, "audioPlaybacks");
        Object mp3Playback = playbacks.get(1);
        Field play = mp3Playback.getClass().getDeclaredField("playButton");
        play.setAccessible(true);
        Field player = mp3Playback.getClass().getDeclaredField("player");
        player.setAccessible(true);
        selectionUi(() -> {
            check(sentText.getParent() == null && selections.size() == 6 && list.getChildCount() == 7,
                    "exclusão individual de mensagem enviada preserva as demais");
            ((Button) play.get(mp3Playback)).performClick();
            check(player.get(mp3Playback) != null, "MP3 enviado reproduz antes da exclusão");
            for (Object box : selections.values()) ((android.widget.CheckBox) box).setChecked(true);
            check("6 selecionadas".contentEquals(((TextView) activity.findViewById(R.id.selectedMessageCount)).getText()),
                    "seleção conjunta de mensagens enviadas e recebidas");
            activity.findViewById(R.id.deleteSelectedMessagesButton).performClick();
            invokeSelectionMethod(activity, "appendSentBubble", new Class<?>[]{String.class}, "Nova mensagem enviada");
        });
        clickSelectionDialog(activity, "deleteSelectedMessagesDialog", true);
        selectionUi(() -> {
            check(list.getChildCount() == 2 && selections.size() == 1 &&
                    !((View) selections.keySet().iterator().next()).isSelected(),
                    "exclusão conjunta preserva nova mensagem enviada não selecionada");
            check(playbacks.isEmpty() && player.get(mp3Playback) == null &&
                    !sentFiles[0].exists() && !sentFiles[1].exists(),
                    "excluir áudios enviados libera players e arquivos temporários");
            check(((Button) activity.findViewById(R.id.clearChatButton)).isEnabled(),
                    "Limpar permanece disponível com mensagens enviadas");
            activity.findViewById(R.id.clearChatButton).performClick();
        });
        clickSelectionDialog(activity, "clearChatDialog", true);
        selectionUi(() -> check(list.getChildCount() == 1 && selections.isEmpty(),
                "Limpar remove mensagens enviadas e reinicia a seleção"));
    }

    private void selectionUi(Action action) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        if (failure.get() != null) throw new Exception("Verificação da interface", failure.get());
    }

    private void clickSelectionDialog(Activity activity, String field, boolean confirm) throws Exception {
        selectionUi(() -> ((android.app.AlertDialog) getRoomField(activity, field)).getButton(confirm
                ? android.app.AlertDialog.BUTTON_POSITIVE : android.app.AlertDialog.BUTTON_NEGATIVE).performClick());
    }

    private void captureSelection(Activity activity) throws IOException {
        View decor = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(target.getCacheDir(), "message_selection.png"))) {
            decor.draw(new Canvas(bitmap));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }
    private Object invokeSelectionMethod(Activity activity, String name, Class<?>[] types,
                                         Object... arguments) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, arguments);
    }

    private void drainDecryption(Activity activity) throws Exception {
        ExecutorService queue = (ExecutorService) getRoomField(activity, "decryptQueue");
        queue.submit(() -> { }).get(30, TimeUnit.SECONDS);
        waitForIdleSync();
    }

    private void setRoomField(Activity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private Object getRoomField(Activity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private void invokeRoomMethod(Activity activity, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private void runChecks() throws Exception {
        Sticker still = image("transparent.png");
        check(!still.isAnimated() && still.width == 128 && still.height == 96, "imagem estática");
        Bitmap decoded = still.decodeFrame(0);
        check(Color.alpha(decoded.getPixel(0, 0)) == 0, "transparência preservada");
        decoded.recycle();
        check(StickerCodec.decode(still.bytes).frameCount() == 1, "serialização estática");

        Sticker gif = image("animated.gif");
        check(gif.isAnimated() && gif.frameCount() >= 8 && gif.frameCount() <= 9,
                "GIF animado (" + gif.frameCount() + " quadros)");
        check(framesDiffer(gif), "movimento do GIF preservado");
        Sticker webp = image("animated.webp");
        check(webp.isAnimated() && framesDiffer(webp), "WebP animado");
        Sticker partial = image("partial.webp");
        Bitmap second = partial.decodeFrame(2);
        check(Color.alpha(second.getPixel(0, 0)) < 10 && Color.green(second.getPixel(40, 20)) > 150,
                "WebP com descarte transparente e substituição de quadro");
        second.recycle();

        Sticker video = StickerCodec.fromVideo(target, fixture("short.mp4"), p -> { });
        check(video.isAnimated() && video.frameCount() == 8 && framesDiffer(video), "vídeo convertido em animação");
        check(StickerCodec.decode(video.bytes).frameCount() == video.frameCount(), "serialização animada");
        Sticker rotated = StickerCodec.fromVideo(target, fixture("rotated.mp4"), p -> { });
        check(rotated.height > rotated.width, "orientação do vídeo");

        expectIOException(() -> StickerCodec.fromVideo(target, fixture("long.mp4"), p -> { }),
                StickerCodec.TooLongException.class, "rejeita vídeo acima de 6 segundos");
        expectIOException(() -> StickerCodec.fromImage(target, fixture("long.gif"), p -> { }),
                StickerCodec.TooLongException.class, "rejeita GIF acima de 6 segundos");
        expectIOException(() -> StickerCodec.fromVideo(target, fixture("short.mp4"), p -> {
            throw new IOException("cancelado");
        }), IOException.class, "cancelamento durante a criação");
        expectIOException(() -> StickerCodec.decode(new byte[15]), IOException.class, "rejeita cabeçalho inválido");
        expectIOException(() -> StickerCodec.decode(Arrays.copyOf(video.bytes, video.bytes.length - 1)),
                IOException.class, "rejeita arquivo truncado");
        byte[] trailing = Arrays.copyOf(still.bytes, still.bytes.length + 1);
        expectIOException(() -> StickerCodec.decode(trailing), IOException.class, "rejeita dados adicionais");
        byte[] tooMany = still.bytes.clone(); tooMany[4] = 0; tooMany[5] = 49;
        expectIOException(() -> StickerCodec.decode(tooMany), IOException.class, "limita número de quadros recebidos");
        byte[] tooWide = still.bytes.clone(); tooWide[6] = 2; tooWide[7] = 1;
        expectIOException(() -> StickerCodec.decode(tooWide), IOException.class, "limita dimensão recebida");
        expectIOException(() -> StickerCodec.readBounded(new java.io.ByteArrayInputStream(new byte[20]), 10),
                StickerCodec.TooLargeException.class, "limita leitura sem tamanho declarado");

        StickerStore store = new StickerStore(target);
        StickerStore.Entry entry = store.save("Teste de persistência", video);
        try {
            StickerStore reopened = new StickerStore(target);
            boolean found = false;
            for (StickerStore.Entry item : reopened.list()) if (item.file.equals(entry.file)) found = true;
            check(found && entry.thumbnail().isFile(), "acervo persistente com miniatura");
            check(Arrays.equals(video.bytes, reopened.load(entry).bytes), "reabertura do acervo");
        } finally { store.delete(entry); }
        check(!entry.file.exists() && !entry.thumbnail().exists(), "exclusão do acervo");

        Method encrypt = MainActivity.class.getDeclaredMethod("encryptContent", byte.class, byte[].class, char[].class);
        Method decrypt = MainActivity.class.getDeclaredMethod("decryptReceivedMessage", byte[].class, char[].class);
        encrypt.setAccessible(true); decrypt.setAccessible(true);
        char[] password = "senha-de-teste-com-mais-de-12".toCharArray();
        byte[] encrypted = (byte[]) encrypt.invoke(null, (byte) 5, video.bytes, password);
        Object message = decrypt.invoke(null, encrypted, password);
        Field stickerField = message.getClass().getDeclaredField("sticker"); stickerField.setAccessible(true);
        check(Arrays.equals(video.bytes, ((Sticker) stickerField.get(message)).bytes), "envio e recebimento criptografados");
        encrypted[encrypted.length - 1] ^= 1;
        try {
            decrypt.invoke(null, encrypted, password);
            throw new AssertionError("Pacote adulterado aceito");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof GeneralSecurityException, "autenticação de pacote adulterado");
        }
        byte[] text = "Texto preservado".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Object textMessage = decrypt.invoke(null, encrypt.invoke(null, (byte) 1, text, password), password);
        Field textField = textMessage.getClass().getDeclaredField("text"); textField.setAccessible(true);
        check("Texto preservado".equals(textField.get(textMessage)), "regressão do envio de texto");
        Object imageMessage = decrypt.invoke(null, encrypt.invoke(null, (byte) 2, png(), password), password);
        Field imageField = imageMessage.getClass().getDeclaredField("image"); imageField.setAccessible(true);
        Bitmap messageBitmap = (Bitmap) imageField.get(imageMessage);
        check(messageBitmap != null, "regressão do envio de imagem");
        messageBitmap.recycle();
        for (byte type : new byte[]{3, 4}) {
            byte[] audio = asset(type == 3 ? "audio.m4a" : "audio.mp3");
            Object audioMessage = decrypt.invoke(null, encrypt.invoke(null, type, audio, password), password);
            Field audioField = audioMessage.getClass().getDeclaredField("audio"); audioField.setAccessible(true);
            check(Arrays.equals(audio, (byte[]) audioField.get(audioMessage)),
                    type == 3 ? "regressão do envio de áudio" : "regressão do envio de MP3");
        }
        Arrays.fill(password, '\0');

        Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(intent);
        waitForIdleSync();
        StickerStore.Entry retained = store.save("Sobrevive a limpar chat", still);
        try {
            ActivityInfo info = target.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            check(info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "orientação vertical mantida");
            runOnMainSync(() -> {
                try {
                    Method clear = MainActivity.class.getDeclaredMethod("clearChat"); clear.setAccessible(true);
                    clear.invoke(activity);
                    View library = activity.findViewById(R.id.stickerButton);
                    if (!library.isEnabled()) throw new AssertionError("Acervo indisponível sem conexão");
                    library.performClick();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            waitForIdleSync();
            check(store.load(retained).width == still.width, "limpar chat preserva acervo");
            drainMediaQueue(activity);
            Field controllerField = MainActivity.class.getDeclaredField("stickerController");
            controllerField.setAccessible(true);
            StickerController controller = (StickerController) controllerField.get(activity);
            Field currentField = StickerController.class.getDeclaredField("current");
            currentField.setAccessible(true);
            Dialog libraryDialog = (Dialog) currentField.get(controller);
            runOnMainSync(() -> capture(libraryDialog, "sticker_test_library.png"));
            runOnMainSync(() -> controller.showPreview(gif, null));
            waitForIdleSync();
            Dialog previewDialog = (Dialog) currentField.get(controller);
            runOnMainSync(() -> capture(previewDialog, "sticker_test_preview.png"));
            runOnMainSync(() -> {
                // Draw/attach/detach an animated sticker to exercise background frame lifecycle.
                android.app.Dialog preview = new android.app.Dialog(activity);
                preview.setContentView(new StickerView(activity, gif));
                preview.show(); preview.dismiss();
            });
            check(true, "acervo offline e ciclo de vida da animação");
        } finally {
            store.delete(retained);
            runOnMainSync(activity::finish);
        }
        waitForIdleSync();
    }

    private void drainMediaQueue(Activity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("mediaQueue");
        field.setAccessible(true);
        ExecutorService queue = (ExecutorService) field.get(activity);
        queue.submit(() -> { }).get(15, TimeUnit.SECONDS);
        waitForIdleSync();
        queue.submit(() -> { }).get(15, TimeUnit.SECONDS);
        waitForIdleSync();
    }

    private void capture(Dialog dialog, String filename) {
        View decor = dialog.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(target.getCacheDir(), filename))) {
            decor.draw(new Canvas(bitmap));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (IOException e) { throw new RuntimeException(e); }
        finally { bitmap.recycle(); }
    }

    private byte[] png() throws IOException { return asset("transparent.png"); }
    private Sticker image(String name) throws IOException { return StickerCodec.fromImage(target, fixture(name), p -> { }); }

    private Uri fixture(String name) throws IOException {
        File file = new File(target.getCacheDir(), "sticker_test_" + name);
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(asset(name)); }
        file.deleteOnExit();
        return Uri.fromFile(file);
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream input = getContext().getAssets().open("stickers/" + name)) {
            return StickerCodec.readBounded(input, 10 * 1024 * 1024);
        }
    }

    private boolean framesDiffer(Sticker sticker) {
        Bitmap first = sticker.decodeFrame(0);
        Bitmap last = sticker.decodeFrame(sticker.frameCount() - 1);
        boolean different = !first.sameAs(last);
        first.recycle(); last.recycle();
        return different;
    }

    private interface Action { void run() throws Exception; }

    private void expectIOException(Action action, Class<? extends IOException> type, String label) throws Exception {
        try { action.run(); throw new AssertionError("Entrada inválida aceita: " + label); }
        catch (IOException expected) { check(type.isInstance(expected), label); }
    }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
        Bundle status = new Bundle(); status.putString("stream", "OK " + passed + ": " + label + "\n");
        sendStatus(0, status);
    }
}
