package com.capongasoftware.chat_de_rua;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chat Bluetooth Classic/RFCOMM com criptografia de ponta a ponta por senha.
 * Mesma instalacao nos dois celulares; o pareamento pode ser iniciado pelo sistema.
 * Uma ponta escolhe o dispositivo; a outra confirma explicitamente a conexao recebida.
 *
 * SEGURANCA:
 * - AES-256-GCM, nonce aleatorio de 12 bytes POR MENSAGEM.
 * - Salt aleatorio de 16 bytes POR MENSAGEM.
 * - PBKDF2WithHmacSHA256 com 210.000 iteracoes e chave de 256 bits.
 * - Senha nunca transmitida nem gravada pelo aplicativo.
 * - Todas as mensagens enviadas por RFCOMM sao pacotes cifrados.
 * - Mensagens recebidas sao abertas automaticamente quando a senha esta disponivel.
 * - Exige Android 8.0 / API 26 ou superior.
 *
 * Formato binario transmitido:
 *   [4 bytes: tamanho big-endian]
 *   [1 byte: versao 1][16 bytes: salt][12 bytes: nonce]
 *   [N bytes: AES-GCM ciphertext com tag de 16 bytes]
 * A senha deve ser igual nos dois celulares. Nao ha envio de senha.
 */
public class MainActivity extends Activity {

    private AlertDialog languageDialog;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrap(base));
    }

    private CharSequence uiText(int resource, Object... arguments) {
        return LocalizedText.text(this, resource, arguments);
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        View view = super.onCreateView(parent, name, context, attrs);
        if (view == null) {
            String[] prefixes = name.contains(".") ? new String[]{null}
                    : new String[]{"android.widget.", "android.view.", "android.webkit."};
            for (String prefix : prefixes) {
                try {
                    view = getLayoutInflater().createView(name, prefix, attrs);
                    break;
                } catch (ClassNotFoundException ignored) { }
            }
        }
        if (view != null) LocalizedText.bindAttributes(view, attrs);
        return view;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppLanguage.apply(this);
        LocalizedText.refreshViews(getWindow().getDecorView(), this);
    }

    private void applyLanguage(String tag) {
        AppLanguage.save(this, tag);
        AppLanguage.apply(this);
        LocalizedText.refreshViews(getWindow().getDecorView(), this);
        setTitle(R.string.app_name);
    }

    private void showLanguagePicker() {
        if (languageDialog != null) return;
        cancelAutomaticConversationCountdown();
        boolean firstLaunch = AppLanguage.saved(this) == null;
        int[] selected = {Arrays.asList(AppLanguage.TAGS).indexOf(AppLanguage.current(this))};
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_language)
                .setSingleChoiceItems(AppLanguage.NAMES, selected[0], (dialog, which) -> {
                    selected[0] = which;
                    Context preview = createConfigurationContext(
                            AppLanguage.configuration(this, AppLanguage.TAGS[which]));
                    languageDialog.setTitle(preview.getString(R.string.choose_language));
                    languageDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setText(preview.getString(R.string.language_continue));
                    if (!firstLaunch) languageDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                            .setText(preview.getString(R.string.cancel));
                })
                .setPositiveButton(R.string.language_continue, (dialog, which) -> {
                    applyLanguage(AppLanguage.TAGS[selected[0]]);
                    if (firstLaunch) {
                        ensurePermissionAndStart();
                        startBluetoothMonitoring();
                    }
                })
                .setCancelable(!firstLaunch);
        if (!firstLaunch) builder.setNegativeButton(R.string.cancel, null);
        languageDialog = builder.create();
        languageDialog.setOnDismissListener(dialog -> {
            languageDialog = null;
            BluetoothSocket pending;
            synchronized (socketLock) { pending = pendingIncomingSocket; }
            if (pending != null) showIncomingConnectionConfirmation(pending);
            scheduleAutomaticConversationStart();
        });
        languageDialog.show();
    }

    private OnBackInvokedCallback chatBackCallback;
    private boolean chatBackCallbackRegistered;

    private static final UUID CHAT_UUID = UUID.fromString(
            "5ea52c09-ef8a-4d96-9a67-c852cd632571");
    private static final String SERVICE_NAME = "ChatBluetoothTexto";
    private static final int REQUEST_CONNECT_PERMISSION = 100;
    private static final int REQUEST_ENABLE_BLUETOOTH = 101;
    private static final int REQUEST_PICK_IMAGE = 102;
    private static final int REQUEST_SAVE_IMAGE = 103;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 104;
    private static final int REQUEST_PICK_MP3 = 105;
    private static final int REQUEST_DISCOVERABLE = 106;
    private static final int REQUEST_ADVERTISE_PERMISSION = 108;
    private static final int REQUEST_ENABLE_LOCATION_FOR_DISCOVERY = 109;
    private static final int MAX_CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 900L;
    private static final int DISCOVERABLE_DURATION_SECONDS = 300;
    private static final long BLUETOOTH_CHECK_INTERVAL_MS = 10_000L;
    private static final long AUTOMATIC_START_IDLE_DELAY_MS = 10_000L;
    private static final long AUTOMATIC_START_SUSPENSION_MS = 60L * 60L * 1000L;
    private static final long CONNECT_TIMEOUT_MS = 30_000L;
    private static final long NAME_CHANGE_TIMEOUT_MS = 2_500L;
    // Recognize rooms in every app language, including the original Bluetooth name.
    private static final String[] DISCOVERABLE_ROOM_NAMES =
            {"Sala", "Room", "Raum", "聊天室", "Conversar"};
    private static final String BLUETOOTH_NICKNAME_SEPARATOR = " - ";
    private static final int MAX_BLUETOOTH_NAME_BYTES = 248;
    private static final String BLUETOOTH_STATE_PREFERENCES = "bluetooth_state";
    private static final String PREF_ORIGINAL_BLUETOOTH_NAME = "original_bluetooth_name";
    private static final String PREF_RESTORE_BLUETOOTH_NAME = "restore_bluetooth_name";
    private static final String PREF_CHAT_NICKNAME = "chat_nickname";
    private static final String PREF_AUTOMATIC_START_SUSPENDED_UNTIL =
            "automatic_start_suspended_until";
    private static final int HAPTIC_REQUEST = 1;
    private static final int HAPTIC_SUCCESS = 2;
    private static final int HAPTIC_FAILURE = 3;

    private static final byte PACKET_VERSION = 1;
    private static final byte PAYLOAD_TEXT = 1;
    private static final byte PAYLOAD_IMAGE = 2;
    private static final byte PAYLOAD_AUDIO = 3;
    private static final byte PAYLOAD_MP3 = 4;
    private static final byte PAYLOAD_STICKER = 5;
    private static final int CONTROL_CONNECTION_REJECTED = -1;
    private static final int CONTROL_MEDIA_PROGRESS = -2;
    private static final int CONTROL_MEDIA_CANCEL = -3;
    private static final int CONTROL_MEDIA_PACKET = -4;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int KDF_ITERATIONS = 210000;
    private static final int MIN_PASSWORD_CHARS = 12;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_AUDIO_DURATION_MS = 5 * 60 * 1000;
    private static final long MAX_SOURCE_IMAGE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 64_000_000L;
    private static final int MAX_SEND_IMAGE_DIMENSION = 1920;
    private static final int MAX_PREVIEW_DIMENSION = 2048;
    private static final int ENCRYPTION_HEADER_BYTES = 1 + SALT_BYTES + NONCE_BYTES;
    private static final int MIN_PACKET_BYTES = ENCRYPTION_HEADER_BYTES + TAG_BITS / 8 + 1;
    private static final int MAX_PACKET_BYTES =
            ENCRYPTION_HEADER_BYTES + TAG_BITS / 8 + 1 + MAX_IMAGE_BYTES;
    // Opcao de conveniencia compartilhada por todas as instalacoes; nao e segredo privado.
    private static final char[] DEFAULT_CHAT_PASSWORD =
            "Q7!vN2#rL9@xP4$kT8^mW3&zC6*eH1?yB5_G0+uD7=F2sJ9aR4pX8cK".toCharArray();
    private static final byte[] AUTH_DATA =
            "BTChat-AES256GCM-v1".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BluetoothAdapter adapter;
    private View homeScreen;
    private View chatScreen;
    private TextView bluetoothWarningView;
    private TextView statusView;
    private TextView chatConnectionStatusView;
    private EditText messageInput;
    private EditText passphraseInput;
    private EditText nicknameInput;
    private Button showPasswordButton;
    private Switch useDefaultPasswordSwitch;
    private View personalPasswordRow;
    private TextView defaultPasswordNoteView;
    private Button createConversationButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button cancelAutomaticConversationButton;
    private Button resumeAutomaticConversationButton;
    private Button openChatButton;
    private Button clearChatButton;
    private View messageSelectionBar;
    private TextView selectedMessageCount;
    private final LinkedHashMap<LinearLayout, CheckBox> messageSelections =
            new LinkedHashMap<>();
    private AlertDialog deleteSelectedMessagesDialog;
    private Button sendButton;
    private ImageButton backToHomeButton;
    private ImageButton imageButton;
    private ImageButton audioButton;
    private ImageButton mp3Button;
    private ImageButton stickerButton;
    private StickerController stickerController;
    private volatile boolean sendingSticker;
    private ImageSendOperation currentStickerOperation;
    private AlertDialog stickerSendDialog;
    private LinearLayout messageList;
    private ScrollView messageScroll;
    private View emptyStateView;
    private TextView emptyStateLabel;
    private ProgressBar incomingMediaProgressBar;
    private TextView incomingMediaProgressPercent;
    private boolean incomingMediaProgressActive;
    private byte incomingMediaPayloadType;
    private boolean chatRetainedWithoutConnection;

    private final Object socketLock = new Object();
    private final ExecutorService sendQueue = Executors.newSingleThreadExecutor();
    private final ExecutorService decryptQueue = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaQueue = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable bluetoothCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopped || !activityVisible) return;
            updateBluetoothWarning();
            mainHandler.postDelayed(this, BLUETOOTH_CHECK_INTERVAL_MS);
        }
    };
    private final List<PendingMessage> pendingMessages = new ArrayList<>();
    private final Runnable retryPendingMessagesRunnable = this::retryPendingMessages;
    private final Runnable automaticConversationRunnable =
            this::startAutomaticConversationIfIdle;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket activeSocket;
    private BluetoothSocket connectingSocket;
    private BluetoothSocket pendingIncomingSocket;
    private BluetoothDevice pendingPairingDevice;
    private AlertDialog incomingConnectionDialog;
    private AlertDialog discoveryProgressDialog;
    private AlertDialog discoveryResultDialog;
    private AlertDialog connectionProgressDialog;
    private AlertDialog clearChatDialog;
    private AlertDialog automaticConversationDecisionDialog;
    private Thread serverThread;
    private Thread connectionThread;
    private volatile boolean connecting = false;
    private volatile boolean connectionCancellationRequested = false;
    private volatile boolean acceptingConnections = false;
    private boolean hostingConversation = false;
    private boolean waitingForDiscoverableName = false;
    private boolean discoverabilityRequestLaunched = false;
    private boolean restoringBluetoothName = false;
    private String pendingDiscoverableBluetoothName;
    private boolean discoveryInProgress = false;
    private boolean showDiscoveryResultsWhenFinished = false;
    private boolean automaticConversationProcess = false;
    private boolean automaticCancellationDecisionInProgress = false;
    private final LinkedHashMap<String, BluetoothDevice> discoveryCandidates =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, String> discoveryCandidateNames =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, BluetoothDevice> discoveredConversations =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, String> discoveredConversationNames =
            new LinkedHashMap<>();
    private volatile boolean sendingImage = false;
    private volatile boolean sendingAudio = false;
    private volatile boolean sendingMp3 = false;
    private volatile boolean recordingAudio = false;
    private volatile boolean stopped = false;
    private boolean showingPassword = false;
    private volatile boolean activityVisible = false;
    private boolean bluetoothReceiverRegistered = false;
    private int passwordGeneration = 0;
    private Bitmap pendingSaveBitmap;
    private ImageSendOperation currentImageOperation;
    private Dialog imageProgressDialog;
    private ProgressBar imageProgressBar;
    private TextView imageProgressPercent;
    private TextView imageProgressStage;
    private Button imageProgressCancelButton;
    private AudioSendOperation currentAudioOperation;
    private Mp3SendOperation currentMp3Operation;
    private Dialog audioProgressDialog;
    private ProgressBar audioProgressBar;
    private TextView audioProgressPercent;
    private TextView audioProgressStage;
    private Button audioProgressCancelButton;
    private Dialog audioRecordingDialog;
    private TextView audioRecordingTimer;
    private MediaRecorder mediaRecorder;
    private File activeRecordingFile;
    private long audioRecordingStartedAt;
    private final List<AudioPlayback> audioPlaybacks = new ArrayList<>();
    private final List<File> temporaryAudioFiles = new ArrayList<>();
    private final Runnable audioRecordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!recordingAudio || audioRecordingTimer == null) return;
            long elapsed = SystemClock.elapsedRealtime() - audioRecordingStartedAt;
            audioRecordingTimer.setText(formatAudioTime((int) elapsed));
            mainHandler.postDelayed(this, 250L);
        }
    };
    private final Runnable discoverabilityTimeoutRunnable =
            () -> finishHostingWindow(uiText(R.string.status_visibility_ended));
    private final Runnable discoverableNameTimeoutRunnable = () -> {
        if (!waitingForDiscoverableName || stopped) return;
        waitingForDiscoverableName = false;
        finishHostingWindow(uiText(R.string.status_name_change_failed));
        vibrateConnectionEvent(HAPTIC_FAILURE);
    };
    private final Runnable verifyRestoredNameRunnable = this::verifyRestoredBluetoothName;
    private final BroadcastReceiver bluetoothEventsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            CharSequence deviceName = getDeviceLabel(device);
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                showStatus(uiText(R.string.status_pairing_request, deviceName));
                vibrateConnectionEvent(HAPTIC_REQUEST);
                toast(uiText(R.string.pairing_confirm_system));
                return;
            }
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                int previous = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (state == BluetoothDevice.BOND_BONDING) {
                    showStatus(uiText(R.string.status_pairing, deviceName));
                } else if (state == BluetoothDevice.BOND_BONDED) {
                    showStatus(uiText(R.string.status_paired, deviceName));
                    vibrateConnectionEvent(HAPTIC_SUCCESS);
                    continueConnectionAfterPairing(device);
                } else if (state == BluetoothDevice.BOND_NONE &&
                        previous == BluetoothDevice.BOND_BONDING) {
                    if (isPendingPairingDevice(device)) {
                        finishPendingPairing(device, false);
                    } else {
                        showStatus(uiText(R.string.status_pairing_failed, deviceName));
                        vibrateConnectionEvent(HAPTIC_FAILURE);
                    }
                }
                return;
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                handleDiscoveredDevice(device,
                        intent.getStringExtra(BluetoothDevice.EXTRA_NAME));
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                handleDiscoveryFinished();
                return;
            }
            if (BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                if (waitingForDiscoverableName &&
                        pendingDiscoverableBluetoothName != null &&
                        pendingDiscoverableBluetoothName.equals(newName)) {
                    waitingForDiscoverableName = false;
                    mainHandler.removeCallbacks(discoverableNameTimeoutRunnable);
                    launchDiscoverabilityRequest();
                }
                if (restoringBluetoothName) verifyRestoredBluetoothName();
                return;
            }
            if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(action)) {
                int scanMode = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR);
                int previousScanMode = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_PREVIOUS_SCAN_MODE, BluetoothAdapter.ERROR);
                if (hostingConversation &&
                        previousScanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE &&
                        scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                    finishHostingWindow(uiText(R.string.status_visibility_ended));
                }
                return;
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                updateBluetoothWarning();
                int state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    showStatus(uiText(R.string.status_waiting));
                    mainHandler.postDelayed(MainActivity.this::restoreOriginalBluetoothName,
                            350L);
                    ensureConnectionServerListening();
                    scheduleAutomaticConversationStart();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    cancelAutomaticConversationCountdown();
                    cancelDeviceDiscovery(false);
                    finishHostingWindow(null);
                    showStatus(uiText(R.string.status_connection_disabled));
                }
            }
        }
    };

    private static final class PendingMessage {
        private byte[] packet;
        private final LinearLayout bubble;
        private final TextView headerView;
        private final TextView messageView;
        private final ImageView imageView;
        private final LinearLayout audioContainer;
        private final boolean receivedMediaTransfer;
        private boolean decrypting;

        private PendingMessage(byte[] packet, LinearLayout bubble,
                               TextView headerView, TextView messageView,
                               ImageView imageView, LinearLayout audioContainer,
                               boolean receivedMediaTransfer) {
            this.packet = packet;
            this.bubble = bubble;
            this.headerView = headerView;
            this.messageView = messageView;
            this.imageView = imageView;
            this.audioContainer = audioContainer;
            this.receivedMediaTransfer = receivedMediaTransfer;
        }
    }

    private static final class DecodedMessage {
        private final String text;
        private final Bitmap image;
        private final byte[] audio;
        private final String audioExtension;
        private final Sticker sticker;

        private DecodedMessage(String text, Bitmap image, byte[] audio,
                               String audioExtension) {
            this(text, image, audio, audioExtension, null);
        }

        private DecodedMessage(String text, Bitmap image, byte[] audio,
                               String audioExtension, Sticker sticker) {
            this.text = text;
            this.image = image;
            this.audio = audio;
            this.audioExtension = audioExtension;
            this.sticker = sticker;
        }

        private static DecodedMessage text(String text) {
            return new DecodedMessage(text, null, null, null);
        }

        private static DecodedMessage image(Bitmap image) {
            return new DecodedMessage(null, image, null, null);
        }

        private static DecodedMessage audio(byte[] audio, String extension) {
            return new DecodedMessage(null, null, audio, extension);
        }

        private static DecodedMessage sticker(Sticker sticker) {
            return new DecodedMessage(null, null, null, null, sticker);
        }
    }

    private static final class PreparedImage {
        private final byte[] bytes;
        private final Bitmap bitmap;

        private PreparedImage(byte[] bytes, Bitmap bitmap) {
            this.bytes = bytes;
            this.bitmap = bitmap;
        }
    }

    private static final class ImageSendOperation {
        private final BluetoothSocket socket;
        private final char[] password;
        private volatile boolean cancelled;
        private volatile boolean writingToConnection;
        private int lastReceiverProgress;

        private ImageSendOperation(BluetoothSocket socket, char[] password) {
            this.socket = socket;
            this.password = password;
        }
    }

    private static final class AudioSendOperation {
        private final BluetoothSocket socket;
        private final char[] password;
        private final File file;
        private volatile boolean cancelled;
        private volatile boolean writingToConnection;
        private int lastReceiverProgress;

        private AudioSendOperation(BluetoothSocket socket, char[] password, File file) {
            this.socket = socket;
            this.password = password;
            this.file = file;
        }
    }

    private static final class Mp3SendOperation {
        private final BluetoothSocket socket;
        private final char[] password;
        private final Uri uri;
        private volatile boolean cancelled;
        private volatile boolean writingToConnection;
        private int lastReceiverProgress;

        private Mp3SendOperation(BluetoothSocket socket, char[] password, Uri uri) {
            this.socket = socket;
            this.password = password;
            this.uri = uri;
        }
    }

    private static final class ImageTooLargeException extends IOException { }
    private static final class InvalidImageException extends IOException { }
    private static final class ImageSendCancelledException extends IOException { }
    private static final class InvalidMp3Exception extends IOException { }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Impede capturas de tela e previews recentes exibirem a senha/textos.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(getWindow());
        findViewById(R.id.languageButton).setOnClickListener(v -> showLanguagePicker());

        homeScreen = findViewById(R.id.homeScreen);
        chatScreen = findViewById(R.id.chatScreen);
        bluetoothWarningView = findViewById(R.id.bluetoothWarningView);
        statusView = findViewById(R.id.statusView);
        chatConnectionStatusView = findViewById(R.id.chatConnectionStatusView);
        messageInput = findViewById(R.id.messageInput);
        passphraseInput = findViewById(R.id.passphraseInput);
        nicknameInput = findViewById(R.id.nicknameInput);
        showPasswordButton = findViewById(R.id.showPasswordButton);
        useDefaultPasswordSwitch = findViewById(R.id.useDefaultPasswordSwitch);
        personalPasswordRow = findViewById(R.id.personalPasswordRow);
        defaultPasswordNoteView = findViewById(R.id.defaultPasswordNoteView);
        createConversationButton = findViewById(R.id.createConversationButton);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        cancelAutomaticConversationButton = findViewById(
                R.id.cancelAutomaticConversationButton);
        resumeAutomaticConversationButton = findViewById(
                R.id.resumeAutomaticConversationButton);
        openChatButton = findViewById(R.id.openChatButton);
        clearChatButton = findViewById(R.id.clearChatButton);
        messageSelectionBar = findViewById(R.id.messageSelectionBar);
        selectedMessageCount = findViewById(R.id.selectedMessageCount);
        findViewById(R.id.deleteSelectedMessagesButton).setOnClickListener(
                v -> confirmDeleteSelectedMessages());
        findViewById(R.id.cancelMessageSelectionButton).setOnClickListener(
                v -> clearMessageSelection());
        sendButton = findViewById(R.id.sendButton);
        backToHomeButton = findViewById(R.id.backToHomeButton);
        imageButton = findViewById(R.id.imageButton);
        audioButton = findViewById(R.id.audioButton);
        mp3Button = findViewById(R.id.mp3Button);
        stickerButton = findViewById(R.id.stickerButton);
        stickerController = new StickerController(this, mediaQueue, new StickerController.Listener() {
            @Override public boolean send(Sticker sticker) { return sendSticker(sticker); }
            @Override public void stateChanged() {
                if (stopped) return;
                updateActionAvailability();
                scheduleAutomaticConversationStart();
            }
        });
        messageList = findViewById(R.id.messageList);
        messageScroll = findViewById(R.id.messageScroll);
        emptyStateView = findViewById(R.id.emptyStateView);
        emptyStateLabel = findViewById(R.id.emptyStateLabel);
        incomingMediaProgressBar = findViewById(R.id.incomingMediaProgressBar);
        incomingMediaProgressPercent = findViewById(
                R.id.incomingMediaProgressPercent);

        createConversationButton.setOnClickListener(v -> {
            cancelAutomaticConversationCountdown();
            createConversation();
        });
        connectButton.setOnClickListener(v -> {
            cancelAutomaticConversationCountdown();
            findNearbyConversation();
        });
        disconnectButton.setOnClickListener(v -> disconnect());
        cancelAutomaticConversationButton.setOnClickListener(v -> {
            if (automaticConversationProcess) {
                cancelAutomaticConversationProcess();
            } else {
                cancelConversationProcess();
            }
        });
        resumeAutomaticConversationButton.setOnClickListener(
                v -> resumeAutomaticConversationImmediately());
        openChatButton.setOnClickListener(v -> showChatScreen());
        clearChatButton.setOnClickListener(v -> confirmClearChat());
        backToHomeButton.setOnClickListener(v -> showHomeScreen());
        sendButton.setOnClickListener(v -> {
            if (ensureConversationAvailableForMessaging()) sendText();
        });
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean sendAction = actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_DONE;
            boolean enterKey = event != null &&
                    event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN;
            if (!sendAction && !enterKey) return false;
            if (sendButton.isEnabled()) sendText();
            return true;
        });
        imageButton.setOnClickListener(v -> {
            if (ensureConversationAvailableForMessaging()) selectImage();
        });
        audioButton.setOnClickListener(v -> {
            if (ensureConversationAvailableForMessaging()) requestAudioRecording();
        });
        mp3Button.setOnClickListener(v -> {
            if (ensureConversationAvailableForMessaging()) selectMp3();
        });
        stickerButton.setOnClickListener(v -> stickerController.openLibrary());
        showPasswordButton.setOnClickListener(v -> togglePasswordVisibility());
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { updateActionAvailability(); }
        });
        passphraseInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                passwordGeneration++;
                passphraseInput.setError(null);
                mainHandler.removeCallbacks(retryPendingMessagesRunnable);
                if (!useDefaultPasswordSwitch.isChecked() &&
                        s.length() >= MIN_PASSWORD_CHARS) {
                    mainHandler.postDelayed(retryPendingMessagesRunnable, 500L);
                }
            }
        });
        useDefaultPasswordSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> updatePasswordMode(isChecked));
        nicknameInput.setFilters(new InputFilter[]{this::filterBluetoothNickname});
        nicknameInput.setText(getSharedPreferences(BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                .getString(PREF_CHAT_NICKNAME, ""));
        nicknameInput.setSelection(nicknameInput.length());
        nicknameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                    int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before,
                                                int count) { }
            @Override public void afterTextChanged(Editable s) {
                nicknameInput.setError(null);
            }
        });
        updatePasswordMode(useDefaultPasswordSwitch.isChecked());
        updateConnectionUi(uiText(R.string.status_initial));
        cleanupStaleAudioFiles();

        BluetoothManager manager = (BluetoothManager)
                getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (AppLanguage.saved(this) == null) {
            showLanguagePicker();
            return;
        }
        if (adapter == null) {
            statusView.setText(uiText(R.string.status_no_connection_support));
            connectButton.setEnabled(false);
            return;
        }
        ensurePermissionAndStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityVisible = true;
        if (AppLanguage.saved(this) == null) return;
        startBluetoothMonitoring();
    }

    private void startBluetoothMonitoring() {
        mainHandler.removeCallbacks(bluetoothCheckRunnable);
        bluetoothCheckRunnable.run();
        registerBluetoothEventsReceiver();
        resumePendingPairingState();
        ensureConnectionServerListening();
        if (adapter != null && hasConnectPermission()) {
            try {
                if (adapter.isEnabled()) restoreOriginalBluetoothName();
            } catch (SecurityException ignored) { }
        }
        BluetoothSocket pending;
        synchronized (socketLock) { pending = pendingIncomingSocket; }
        if (pending != null) showIncomingConnectionConfirmation(pending);
        scheduleAutomaticConversationStart();
    }

    @Override
    protected void onStop() {
        activityVisible = false;
        mainHandler.removeCallbacks(bluetoothCheckRunnable);
        cancelAutomaticConversationCountdown();
        if (discoveryInProgress) cancelDeviceDiscovery(true);
        unregisterBluetoothEventsReceiver();
        if (incomingConnectionDialog != null) {
            incomingConnectionDialog.dismiss();
            incomingConnectionDialog = null;
        }
        super.onStop();
    }

    private void updateBluetoothWarning() {
        // Sem permissao, o estado e desconhecido; o status existente orienta o usuario.
        boolean bluetoothDisabled = false;
        if (adapter != null && hasConnectPermission()) {
            try {
                bluetoothDisabled = !adapter.isEnabled();
            } catch (SecurityException ignored) { }
        }
        int visibility = bluetoothDisabled ? View.VISIBLE : View.GONE;
        if (bluetoothWarningView.getVisibility() != visibility) {
            bluetoothWarningView.setVisibility(visibility);
        }
    }

    private void registerBluetoothEventsReceiver() {
        if (bluetoothReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothEventsReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(bluetoothEventsReceiver, filter);
            }
            bluetoothReceiverRegistered = true;
        } catch (SecurityException ignored) { }
    }

    private void unregisterBluetoothEventsReceiver() {
        if (!bluetoothReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothEventsReceiver);
        } catch (IllegalArgumentException ignored) { }
        bluetoothReceiverRegistered = false;
    }

    private void togglePasswordVisibility() {
        showingPassword = !showingPassword;
        if (showingPassword) {
            passphraseInput.setTransformationMethod(null);
            showPasswordButton.setText(uiText(R.string.hide_password));
        } else {
            passphraseInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
            showPasswordButton.setText(uiText(R.string.show_password));
        }
        passphraseInput.setSelection(passphraseInput.length());
    }

    private void updatePasswordMode(boolean useDefaultPassword) {
        personalPasswordRow.setVisibility(useDefaultPassword ? View.GONE : View.VISIBLE);
        defaultPasswordNoteView.setVisibility(useDefaultPassword ? View.VISIBLE : View.GONE);
        passphraseInput.setError(null);
        passwordGeneration++;
        mainHandler.removeCallbacks(retryPendingMessagesRunnable);
        if (hasUsablePassword()) {
            mainHandler.post(retryPendingMessagesRunnable);
        }
    }

    private boolean hasUsablePassword() {
        return useDefaultPasswordSwitch.isChecked() ||
                passphraseInput.length() >= MIN_PASSWORD_CHARS;
    }

    private char[] getActivePassword() {
        if (useDefaultPasswordSwitch.isChecked()) {
            return DEFAULT_CHAT_PASSWORD.clone();
        }
        return passphraseInput.getText().toString().toCharArray();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAdvertisePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLegacyDiscoveryLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true;
        LocationManager locationManager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled();
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            return false;
        }
    }

    private CharSequence getConnectionPermissionDeniedStatus() {
        return uiText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? R.string.status_permission_denied
                : R.string.status_location_permission_denied);
    }

    private long getAutomaticStartSuspendedUntil() {
        long suspendedUntil = getSharedPreferences(
                BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                .getLong(PREF_AUTOMATIC_START_SUSPENDED_UNTIL, 0L);
        if (suspendedUntil > 0L && suspendedUntil <= System.currentTimeMillis()) {
            getSharedPreferences(BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                    .edit().remove(PREF_AUTOMATIC_START_SUSPENDED_UNTIL).apply();
            return 0L;
        }
        return suspendedUntil;
    }

    private boolean isConversationOperationInProgress() {
        synchronized (socketLock) {
            if (activeSocket != null || pendingIncomingSocket != null) return true;
        }
        return connecting || discoveryInProgress || hostingConversation ||
                (stickerController != null && stickerController.isOpen()) ||
                waitingForDiscoverableName || discoverabilityRequestLaunched ||
                automaticCancellationDecisionInProgress ||
                (discoveryResultDialog != null && discoveryResultDialog.isShowing());
    }

    private void cancelAutomaticConversationCountdown() {
        mainHandler.removeCallbacks(automaticConversationRunnable);
    }

    private void scheduleAutomaticConversationStart() {
        cancelAutomaticConversationCountdown();
        if (stopped || !activityVisible || AppLanguage.saved(this) == null ||
                languageDialog != null || isConversationOperationInProgress()) return;
        long suspendedUntil = getAutomaticStartSuspendedUntil();
        long delay = suspendedUntil > 0L
                ? Math.max(1L, suspendedUntil - System.currentTimeMillis())
                : AUTOMATIC_START_IDLE_DELAY_MS;
        mainHandler.postDelayed(automaticConversationRunnable, delay);
        updateAutomaticConversationControls();
    }

    private void startAutomaticConversationIfIdle() {
        if (stopped || !activityVisible || AppLanguage.saved(this) == null ||
                languageDialog != null || isConversationOperationInProgress()) return;
        if (getAutomaticStartSuspendedUntil() > 0L) {
            scheduleAutomaticConversationStart();
            return;
        }
        boolean ready = false;
        try {
            ready = adapter != null && adapter.isEnabled() && hasConnectPermission() &&
                    hasScanPermission() && hasAdvertisePermission();
        } catch (SecurityException ignored) { }
        if (!ready) {
            mainHandler.postDelayed(automaticConversationRunnable,
                    AUTOMATIC_START_IDLE_DELAY_MS);
            return;
        }
        createConversation(true);
    }

    private void resumeAutomaticConversationImmediately() {
        getSharedPreferences(BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                .edit().remove(PREF_AUTOMATIC_START_SUSPENDED_UNTIL).apply();
        automaticCancellationDecisionInProgress = false;
        if (automaticConversationDecisionDialog != null) {
            automaticConversationDecisionDialog.dismiss();
            automaticConversationDecisionDialog = null;
        }
        cancelAutomaticConversationCountdown();
        updateAutomaticConversationControls();
        mainHandler.post(this::startAutomaticConversationIfIdle);
    }

    private void updateAutomaticConversationControls() {
        boolean connected;
        synchronized (socketLock) { connected = activeSocket != null; }
        boolean openingConversation = automaticConversationProcess || hostingConversation ||
                waitingForDiscoverableName || discoverabilityRequestLaunched ||
                pendingDiscoverableBluetoothName != null;
        cancelAutomaticConversationButton.setText(uiText(automaticConversationProcess
                ? R.string.cancel_automatic_conversation : R.string.cancel_conversation));
        cancelAutomaticConversationButton.setVisibility(
                openingConversation && !connected ? View.VISIBLE : View.GONE);
        boolean suspended = getAutomaticStartSuspendedUntil() > 0L;
        resumeAutomaticConversationButton.setVisibility(
                suspended && !connected && !isConversationOperationInProgress()
                        ? View.VISIBLE : View.GONE);
    }

    private void ensurePermissionAndStart() {
        if (adapter == null) {
            updateConnectionUi(uiText(R.string.status_no_connection_support));
            return;
        }
        if (!hasConnectPermission() || !hasScanPermission() ||
                !hasAdvertisePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_ADVERTISE
                        }, REQUEST_CONNECT_PERMISSION);
            } else if (!hasScanPermission()) {
                requestPermissions(new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        REQUEST_CONNECT_PERMISSION);
            }
            return;
        }
        if (!adapter.isEnabled()) {
            statusView.setText(uiText(R.string.status_enable_connection));
            updateActionAvailability();
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQUEST_ENABLE_BLUETOOTH);
            } catch (SecurityException e) {
                statusView.setText(uiText(R.string.status_permission_denied));
                updateActionAvailability();
            }
            return;
        }
        updateConnectionUi(uiText(R.string.status_waiting));
        restoreOriginalBluetoothName();
        ensureConnectionServerListening();
        scheduleAutomaticConversationStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateBluetoothWarning();
        if (requestCode == REQUEST_CONNECT_PERMISSION) {
            if (hasConnectPermission() && hasScanPermission() &&
                    hasAdvertisePermission()) {
                ensurePermissionAndStart();
            } else {
                statusView.setText(getConnectionPermissionDeniedStatus());
                updateActionAvailability();
                scheduleAutomaticConversationStart();
            }
            return;
        }
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioRecording();
            } else {
                toast(uiText(R.string.audio_permission_denied));
            }
            return;
        }
        if (requestCode == REQUEST_ADVERTISE_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                createConversation();
            } else {
                showStatus(uiText(R.string.status_advertise_permission_denied));
                vibrateConnectionEvent(HAPTIC_FAILURE);
                scheduleAutomaticConversationStart();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (stickerController.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == REQUEST_PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                sendImage(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_SAVE_IMAGE) {
            Bitmap bitmap = pendingSaveBitmap;
            pendingSaveBitmap = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null &&
                    bitmap != null && !bitmap.isRecycled()) {
                saveImage(bitmap, data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_PICK_MP3) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                sendMp3(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_DISCOVERABLE) {
            discoverabilityRequestLaunched = false;
            if (resultCode > 0) {
                hostingConversation = true;
                updateAutomaticConversationControls();
                startServer();
                mainHandler.removeCallbacks(discoverabilityTimeoutRunnable);
                mainHandler.postDelayed(discoverabilityTimeoutRunnable,
                        resultCode * 1000L + 1_000L);
                updateConnectionUi(uiText(
                        R.string.status_conversation_visible, resultCode));
                vibrateConnectionEvent(HAPTIC_SUCCESS);
            } else {
                boolean cancelledAutomaticProcess = automaticConversationProcess;
                if (cancelledAutomaticProcess) {
                    automaticCancellationDecisionInProgress = true;
                }
                finishHostingWindow(uiText(R.string.status_discoverable_cancelled));
                vibrateConnectionEvent(HAPTIC_FAILURE);
                if (cancelledAutomaticProcess) {
                    showAutomaticConversationSearchSuggestion();
                }
            }
            return;
        }
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                ensurePermissionAndStart();
            } else {
                statusView.setText(uiText(R.string.status_connection_disabled));
                updateActionAvailability();
                scheduleAutomaticConversationStart();
            }
            return;
        }
        if (requestCode == REQUEST_ENABLE_LOCATION_FOR_DISCOVERY) {
            if (isLegacyDiscoveryLocationEnabled()) {
                mainHandler.post(this::findNearbyConversation);
            } else {
                updateConnectionUi(uiText(R.string.status_location_required));
                scheduleAutomaticConversationStart();
            }
        }
    }

    private void selectImage() {
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            passphraseInput.requestFocus();
            return;
        }
        Arrays.fill(password, '\0');

        synchronized (socketLock) {
            if (activeSocket == null) {
                toast(uiText(R.string.connect_before_sending));
                return;
            }
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    private void selectMp3() {
        if (sendingMp3 || sendingImage || sendingAudio || recordingAudio) return;
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            passphraseInput.requestFocus();
            return;
        }
        Arrays.fill(password, '\0');
        synchronized (socketLock) {
            if (activeSocket == null) {
                toast(uiText(R.string.connect_before_sending));
                return;
            }
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/mpeg");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"audio/mpeg", "audio/mp3", "audio/x-mp3"});
        startActivityForResult(intent, REQUEST_PICK_MP3);
    }

    private void requestAudioRecording() {
        if (recordingAudio || sendingAudio || sendingImage || sendingMp3) return;
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            passphraseInput.requestFocus();
            return;
        }
        Arrays.fill(password, '\0');
        synchronized (socketLock) {
            if (activeSocket == null) {
                toast(uiText(R.string.connect_before_sending));
                return;
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
            return;
        }
        startAudioRecording();
    }

    private void startAudioRecording() {
        if (recordingAudio || sendingAudio || stopped) return;
        synchronized (socketLock) {
            if (activeSocket == null) {
                toast(uiText(R.string.connect_before_sending));
                return;
            }
        }
        File recordingFile;
        try {
            recordingFile = File.createTempFile("chat_audio_recording_", ".m4a", getCacheDir());
        } catch (IOException e) {
            toast(uiText(R.string.audio_recording_failed));
            return;
        }

        MediaRecorder recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setMaxDuration(MAX_AUDIO_DURATION_MS);
            recorder.setMaxFileSize(MAX_AUDIO_BYTES);
            recorder.setOutputFile(recordingFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException e) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) { }
            deleteQuietly(recordingFile);
            toast(uiText(R.string.audio_recording_failed));
            return;
        }

        mediaRecorder = recorder;
        activeRecordingFile = recordingFile;
        recordingAudio = true;
        audioRecordingStartedAt = SystemClock.elapsedRealtime();
        recorder.setOnInfoListener((mediaRecorder, what, extra) -> {
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                runOnUiThread(() -> finishAudioRecording(true));
            }
        });
        showAudioRecordingDialog();
        mainHandler.post(audioRecordingTimerRunnable);
        updateActionAvailability();
    }

    private void showAudioRecordingDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_audio_recording);
        applySystemBarInsets(dialog.getWindow());
        dialog.setCancelable(false);
        audioRecordingDialog = dialog;
        audioRecordingTimer = dialog.findViewById(R.id.audioRecordingTimer);
        Button cancel = dialog.findViewById(R.id.cancelAudioRecordingButton);
        Button finish = dialog.findViewById(R.id.finishAudioRecordingButton);
        cancel.setOnClickListener(v -> finishAudioRecording(false));
        finish.setOnClickListener(v -> finishAudioRecording(true));
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setStatusBarColor(getColor(R.color.surface));
            dialog.getWindow().setNavigationBarColor(getColor(R.color.surface));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void finishAudioRecording(boolean send) {
        if (!recordingAudio) return;
        recordingAudio = false;
        mainHandler.removeCallbacks(audioRecordingTimerRunnable);
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        File file = activeRecordingFile;
        activeRecordingFile = null;
        boolean stoppedCleanly = false;
        if (recorder != null) {
            try {
                recorder.setOnInfoListener(null);
                recorder.stop();
                stoppedCleanly = true;
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    recorder.release();
                } catch (RuntimeException ignored) { }
            }
        }
        if (audioRecordingDialog != null) audioRecordingDialog.dismiss();
        audioRecordingDialog = null;
        audioRecordingTimer = null;
        updateActionAvailability();

        if (!send) {
            deleteQuietly(file);
            return;
        }
        if (!stoppedCleanly || file == null || file.length() == 0L) {
            deleteQuietly(file);
            toast(uiText(R.string.audio_too_short));
            return;
        }
        sendAudio(file);
    }

    private CharSequence filterBluetoothNickname(CharSequence source, int start, int end,
                                                  Spanned dest, int dstart, int dend) {
        StringBuilder sanitized = new StringBuilder();
        boolean changed = false;
        for (int index = start; index < end; ) {
            int codePoint = Character.codePointAt(source, index);
            int charCount = Character.charCount(codePoint);
            if (index + charCount > end) {
                codePoint = source.charAt(index);
                charCount = 1;
            }
            if (isAllowedBluetoothNameCodePoint(codePoint)) {
                sanitized.appendCodePoint(codePoint);
            } else {
                changed = true;
            }
            index += charCount;
        }

        String before = dest.subSequence(0, dstart).toString();
        String after = dest.subSequence(dend, dest.length()).toString();
        StringBuilder accepted = new StringBuilder();
        for (int index = 0; index < sanitized.length(); ) {
            int codePoint = Character.codePointAt(sanitized, index);
            String candidateNickname = before + accepted +
                    new String(Character.toChars(codePoint)) + after;
            if (!isValidBluetoothName(buildDiscoverableBluetoothName(candidateNickname))) {
                changed = true;
                break;
            }
            accepted.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }

        String result = accepted.toString();
        String original = source.subSequence(start, end).toString();
        return !changed && result.equals(original) ? null : result;
    }

    private static boolean isAllowedBluetoothNameCodePoint(int codePoint) {
        return codePoint >= 0 && codePoint <= Character.MAX_CODE_POINT &&
                !(codePoint >= Character.MIN_SURROGATE &&
                        codePoint <= Character.MAX_SURROGATE) &&
                !Character.isISOControl(codePoint);
    }

    private String buildDiscoverableBluetoothName(String nickname) {
        String normalizedNickname = nickname == null ? "" : nickname.trim();
        String roomName = getString(R.string.bluetooth_room_name);
        return normalizedNickname.isEmpty()
                ? roomName
                : roomName + BLUETOOTH_NICKNAME_SEPARATOR +
                        normalizedNickname;
    }

    private static boolean isValidBluetoothName(String name) {
        if (name == null || name.isEmpty() ||
                name.getBytes(StandardCharsets.UTF_8).length > MAX_BLUETOOTH_NAME_BYTES) {
            return false;
        }
        for (int index = 0; index < name.length(); ) {
            int codePoint = name.codePointAt(index);
            if (!isAllowedBluetoothNameCodePoint(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isConversationBluetoothName(String name) {
        if (name == null || !isValidBluetoothName(name)) return false;
        for (String prefix : DISCOVERABLE_ROOM_NAMES) {
            if (hasDiscoverableBluetoothNamePrefix(name, prefix)) return true;
        }
        return false;
    }

    private static boolean hasDiscoverableBluetoothNamePrefix(String name,
                                                                String baseName) {
        if (baseName.equals(name)) return true;
        String prefix = baseName + BLUETOOTH_NICKNAME_SEPARATOR;
        return name.startsWith(prefix) && name.length() > prefix.length();
    }

    private void createConversation() {
        createConversation(false);
    }

    private void createConversation(boolean automatic) {
        if (!hasConnectPermission() || !hasScanPermission() ||
                adapter == null || !adapter.isEnabled()) {
            toast(uiText(R.string.permission_and_connection_required));
            scheduleAutomaticConversationStart();
            return;
        }
        if (!hasAdvertisePermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE},
                        REQUEST_ADVERTISE_PERMISSION);
            }
            return;
        }
        synchronized (socketLock) {
            if (activeSocket != null) {
                toast(uiText(R.string.disconnect_first));
                return;
            }
        }
        if (hostingConversation || waitingForDiscoverableName ||
                discoverabilityRequestLaunched) {
            toast(uiText(R.string.conversation_already_available));
            return;
        }
        cancelDeviceDiscovery(false);
        String nickname = nicknameInput.getText().toString().trim();
        String discoverableName = buildDiscoverableBluetoothName(nickname);
        if (!isValidBluetoothName(discoverableName)) {
            nicknameInput.setError(uiText(R.string.nickname_invalid));
            nicknameInput.requestFocus();
            scheduleAutomaticConversationStart();
            return;
        }
        automaticConversationProcess = automatic;
        pendingDiscoverableBluetoothName = discoverableName;
        updateAutomaticConversationControls();
        getSharedPreferences(BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                .edit().putString(PREF_CHAT_NICKNAME, nickname).apply();
        if (automatic) {
            showStatus(uiText(R.string.status_automatic_conversation_preparing));
        } else {
            showStatus(uiText(R.string.status_preparing_conversation));
        }
        if (!saveOriginalBluetoothName()) {
            finishHostingWindow(uiText(R.string.status_name_change_failed));
            vibrateConnectionEvent(HAPTIC_FAILURE);
            return;
        }
        try {
            String currentName = adapter.getName();
            if (discoverableName.equals(currentName)) {
                launchDiscoverabilityRequest();
                return;
            }
            waitingForDiscoverableName = true;
            showStatus(uiText(R.string.status_preparing_conversation));
            if (!adapter.setName(discoverableName)) {
                waitingForDiscoverableName = false;
                finishHostingWindow(uiText(R.string.status_name_change_failed));
                vibrateConnectionEvent(HAPTIC_FAILURE);
                return;
            }
            mainHandler.removeCallbacks(discoverableNameTimeoutRunnable);
            mainHandler.postDelayed(discoverableNameTimeoutRunnable,
                    NAME_CHANGE_TIMEOUT_MS);
        } catch (SecurityException e) {
            waitingForDiscoverableName = false;
            finishHostingWindow(uiText(R.string.status_permission_denied));
            vibrateConnectionEvent(HAPTIC_FAILURE);
        }
    }

    private void launchDiscoverabilityRequest() {
        if (discoverabilityRequestLaunched || stopped) return;
        discoverabilityRequestLaunched = true;
        Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_DURATION_SECONDS);
        try {
            showStatus(uiText(R.string.status_requesting_visibility));
            startActivityForResult(discoverable, REQUEST_DISCOVERABLE);
        } catch (SecurityException e) {
            discoverabilityRequestLaunched = false;
            finishHostingWindow(uiText(R.string.status_advertise_permission_denied));
            vibrateConnectionEvent(HAPTIC_FAILURE);
        } catch (RuntimeException e) {
            discoverabilityRequestLaunched = false;
            finishHostingWindow(uiText(R.string.status_discoverable_failed));
            vibrateConnectionEvent(HAPTIC_FAILURE);
        }
    }

    private void cancelConversationProcess() {
        BluetoothSocket pending;
        synchronized (socketLock) {
            pending = pendingIncomingSocket;
            pendingIncomingSocket = null;
        }
        closeQuietly(pending);
        if (incomingConnectionDialog != null) {
            incomingConnectionDialog.dismiss();
            incomingConnectionDialog = null;
        }
        finishHostingWindow(uiText(R.string.status_conversation_process_cancelled));
    }

    private void cancelAutomaticConversationProcess() {
        if (!automaticConversationProcess) return;
        automaticCancellationDecisionInProgress = true;
        BluetoothSocket pending;
        synchronized (socketLock) {
            pending = pendingIncomingSocket;
            pendingIncomingSocket = null;
        }
        closeQuietly(pending);
        if (incomingConnectionDialog != null) {
            incomingConnectionDialog.dismiss();
            incomingConnectionDialog = null;
        }
        finishHostingWindow(uiText(R.string.status_conversation_process_cancelled));
        showAutomaticConversationSearchSuggestion();
    }

    private void showAutomaticConversationSearchSuggestion() {
        if (isFinishing() || stopped) return;
        if (automaticConversationDecisionDialog != null) {
            automaticConversationDecisionDialog.dismiss();
        }
        automaticConversationDecisionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.automatic_search_suggestion_title)
                .setMessage(R.string.automatic_search_suggestion_message)
                .setPositiveButton(R.string.search_conversations_now, (dialog, which) -> {
                    automaticConversationDecisionDialog = null;
                    automaticCancellationDecisionInProgress = false;
                    cancelAutomaticConversationCountdown();
                    updateAutomaticConversationControls();
                    findNearbyConversation();
                })
                .setNegativeButton(R.string.not_now, (dialog, which) -> {
                    automaticConversationDecisionDialog = null;
                    showAutomaticConversationSuspensionQuestion();
                })
                .setCancelable(false)
                .create();
        automaticConversationDecisionDialog.show();
    }

    private void showAutomaticConversationSuspensionQuestion() {
        automaticConversationDecisionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.suspend_automatic_conversation_title)
                .setMessage(R.string.suspend_automatic_conversation_message)
                .setPositiveButton(R.string.suspend_for_one_hour, (dialog, which) -> {
                    automaticConversationDecisionDialog = null;
                    getSharedPreferences(BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE)
                            .edit().putLong(PREF_AUTOMATIC_START_SUSPENDED_UNTIL,
                                    System.currentTimeMillis() +
                                            AUTOMATIC_START_SUSPENSION_MS)
                            .apply();
                    automaticCancellationDecisionInProgress = false;
                    updateConnectionUi(uiText(
                            R.string.status_automatic_conversation_suspended));
                    scheduleAutomaticConversationStart();
                })
                .setNegativeButton(R.string.keep_automatic_start, (dialog, which) -> {
                    automaticConversationDecisionDialog = null;
                    automaticCancellationDecisionInProgress = false;
                    updateAutomaticConversationControls();
                    scheduleAutomaticConversationStart();
                })
                .setCancelable(false)
                .create();
        automaticConversationDecisionDialog.show();
    }

    private void findNearbyConversation() {
        if (!hasConnectPermission() || !hasScanPermission() ||
                adapter == null || !adapter.isEnabled()) {
            toast(uiText(R.string.permission_and_connection_required));
            scheduleAutomaticConversationStart();
            return;
        }
        if (!isLegacyDiscoveryLocationEnabled()) {
            showLegacyDiscoveryLocationRequirement();
            return;
        }
        synchronized (socketLock) {
            if (activeSocket != null) {
                toast(uiText(R.string.disconnect_first));
                return;
            }
        }
        if (connecting || discoveryInProgress) {
            toast(uiText(R.string.connection_in_progress));
            return;
        }
        if (hostingConversation || waitingForDiscoverableName ||
                discoverabilityRequestLaunched) {
            toast(uiText(R.string.stop_hosting_before_search));
            return;
        }
        discoveryCandidates.clear();
        discoveryCandidateNames.clear();
        discoveredConversations.clear();
        discoveredConversationNames.clear();
        addBondedDevicesToSearchResults();
        showDiscoveryResultsWhenFinished = true;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            discoveryInProgress = adapter.startDiscovery();
        } catch (SecurityException e) {
            discoveryInProgress = false;
            showStatus(getConnectionPermissionDeniedStatus());
            vibrateConnectionEvent(HAPTIC_FAILURE);
            scheduleAutomaticConversationStart();
            return;
        }
        if (!discoveryInProgress) {
            showDiscoveryResultsWhenFinished = false;
            showStatus(uiText(R.string.status_discovery_failed));
            vibrateConnectionEvent(HAPTIC_FAILURE);
            scheduleAutomaticConversationStart();
            return;
        }
        showStatus(uiText(R.string.status_searching_nearby));
        discoveryProgressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.searching_conversations_title)
                .setMessage(R.string.searching_conversations_message)
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> cancelDeviceDiscovery(true))
                .create();
        discoveryProgressDialog.setOnCancelListener(dialog -> cancelDeviceDiscovery(true));
        discoveryProgressDialog.show();
    }

    private void showLegacyDiscoveryLocationRequirement() {
        updateConnectionUi(uiText(R.string.status_location_required));
        discoveryResultDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.location_required_title)
                .setMessage(R.string.location_required_message)
                .setPositiveButton(R.string.open_location_settings,
                        (dialog, which) -> openLocationSettingsForDiscovery())
                .setNegativeButton(R.string.cancel, null)
                .create();
        discoveryResultDialog.setOnDismissListener(dialog -> {
            discoveryResultDialog = null;
            scheduleAutomaticConversationStart();
        });
        discoveryResultDialog.show();
    }

    private void openLocationSettingsForDiscovery() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    REQUEST_ENABLE_LOCATION_FOR_DISCOVERY);
        } catch (RuntimeException e) {
            updateConnectionUi(uiText(R.string.status_location_settings_unavailable));
            vibrateConnectionEvent(HAPTIC_FAILURE);
        }
    }

    private void handleDiscoveredDevice(BluetoothDevice device, String advertisedName) {
        if (!discoveryInProgress || device == null) return;
        String key = getDeviceKey(device);
        if (key == null) return;
        discoveryCandidates.put(key, device);
        String resolvedName = resolveDiscoveredDeviceName(device, advertisedName);
        if (resolvedName != null) discoveryCandidateNames.put(key, resolvedName);
        addDiscoveredBluetoothDevice(key, device, resolvedName);
    }

    private String resolveDiscoveredDeviceName(BluetoothDevice device,
                                               String advertisedName) {
        if (isConversationBluetoothName(advertisedName)) return advertisedName;
        try {
            String deviceName = device.getName();
            if (isConversationBluetoothName(deviceName)) return deviceName;
            if (advertisedName != null && !advertisedName.trim().isEmpty()) {
                return advertisedName;
            }
            return deviceName;
        } catch (SecurityException ignored) { }
        return advertisedName;
    }

    private void addDiscoveredBluetoothDevice(String key, BluetoothDevice device,
                                              String discoveredName) {
        discoveredConversations.put(key, device);
        if (discoveredName != null && !discoveredName.trim().isEmpty()) {
            discoveredConversationNames.put(key, discoveredName.trim());
        }
        showStatus(uiText(R.string.status_conversations_found,
                discoveredConversations.size()));
    }

    private void addBondedDevicesToSearchResults() {
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String key = getDeviceKey(device);
                if (key == null) continue;
                addDiscoveredBluetoothDevice(
                        key, device, resolveDiscoveredDeviceName(device, null));
            }
        } catch (SecurityException ignored) { }
    }

    private void handleDiscoveryFinished() {
        if (!discoveryInProgress) return;
        discoveryInProgress = false;
        for (java.util.Map.Entry<String, BluetoothDevice> entry :
                discoveryCandidates.entrySet()) {
            String key = entry.getKey();
            String name = resolveDiscoveredDeviceName(
                    entry.getValue(), discoveryCandidateNames.get(key));
            addDiscoveredBluetoothDevice(key, entry.getValue(), name);
        }
        if (discoveryProgressDialog != null) {
            discoveryProgressDialog.dismiss();
            discoveryProgressDialog = null;
        }
        if (!showDiscoveryResultsWhenFinished) return;
        showDiscoveryResultsWhenFinished = false;
        showDiscoveredConversations();
    }

    private void showDiscoveredConversations() {
        if (isFinishing() || stopped) return;
        List<String> deviceKeys = new ArrayList<>(discoveredConversations.keySet());
        List<BluetoothDevice> devices = new ArrayList<>(discoveredConversations.values());
        if (devices.isEmpty()) {
            showStatus(uiText(R.string.status_no_nearby_conversation));
            vibrateConnectionEvent(HAPTIC_FAILURE);
            discoveryResultDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.no_nearby_title)
                    .setMessage(R.string.no_nearby_message)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .create();
            discoveryResultDialog.setOnDismissListener(dialog -> {
                discoveryResultDialog = null;
                scheduleAutomaticConversationStart();
            });
            discoveryResultDialog.show();
            return;
        }
        CharSequence[] labels = new CharSequence[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            labels[i] = getDiscoveredDeviceLabel(
                    devices.get(i), discoveredConversationNames.get(deviceKeys.get(i)), i + 1);
        }
        discoveryResultDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_conversation)
                .setItems(labels, (dialog, index) -> {
                    cancelDiscoveryBeforeConnect();
                    pairOrConnectTo(devices.get(index));
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        discoveryResultDialog.setOnDismissListener(dialog -> {
            discoveryResultDialog = null;
            scheduleAutomaticConversationStart();
        });
        discoveryResultDialog.show();
    }

    private void cancelDeviceDiscovery(boolean updateStatus) {
        boolean wasDiscovering = discoveryInProgress;
        discoveryInProgress = false;
        showDiscoveryResultsWhenFinished = false;
        if (discoveryProgressDialog != null) {
            discoveryProgressDialog.dismiss();
            discoveryProgressDialog = null;
        }
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException ignored) { }
        if (wasDiscovering && updateStatus) {
            showStatus(uiText(R.string.status_discovery_cancelled));
        }
        if (wasDiscovering) scheduleAutomaticConversationStart();
    }

    private String getDeviceKey(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return null;
        }
    }

    private CharSequence getDiscoveredDeviceLabel(BluetoothDevice device,
                                            String discoveredName, int index) {
        try {
            String address = device.getAddress();
            CharSequence displayName = discoveredName == null || discoveredName.trim().isEmpty()
                    ? getDeviceLabel(device) : discoveredName.trim();
            if (address != null && address.length() >= 5) {
                boolean paired = device.getBondState() == BluetoothDevice.BOND_BONDED;
                int labelResource;
                if (isConversationBluetoothName(displayName.toString())) {
                    labelResource = paired
                            ? R.string.conversation_device_label
                            : R.string.conversation_device_unpaired_label;
                } else {
                    labelResource = paired
                            ? R.string.bluetooth_device_paired_label
                            : R.string.bluetooth_device_unpaired_label;
                }
                return uiText(labelResource,
                        displayName, address.substring(address.length() - 5));
            }
            if (displayName.length() > 0) return displayName;
        } catch (SecurityException ignored) { }
        return uiText(R.string.bluetooth_device_fallback, index);
    }

    private boolean saveOriginalBluetoothName() {
        SharedPreferences preferences = getSharedPreferences(
                BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE);
        if (preferences.getBoolean(PREF_RESTORE_BLUETOOTH_NAME, false)) return true;
        try {
            String originalName = adapter.getName();
            if (originalName == null) return false;
            return preferences.edit()
                    .putString(PREF_ORIGINAL_BLUETOOTH_NAME, originalName)
                    .putBoolean(PREF_RESTORE_BLUETOOTH_NAME, true)
                    .commit();
        } catch (SecurityException e) {
            return false;
        }
    }

    private void restoreOriginalBluetoothName() {
        // Ao voltar do dialogo do sistema, onStart pode ocorrer antes do resultado.
        // Nao desfaça o nome temporario enquanto a sessao de hospedagem estiver ativa.
        if (waitingForDiscoverableName || discoverabilityRequestLaunched ||
                hostingConversation) return;
        SharedPreferences preferences = getSharedPreferences(
                BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_RESTORE_BLUETOOTH_NAME, false) ||
                adapter == null || !hasConnectPermission()) return;
        String originalName = preferences.getString(PREF_ORIGINAL_BLUETOOTH_NAME, null);
        if (originalName == null) return;
        try {
            if (!adapter.isEnabled()) return;
            if (originalName.equals(adapter.getName())) {
                clearStoredBluetoothName(preferences);
                return;
            }
            restoringBluetoothName = adapter.setName(originalName);
            if (restoringBluetoothName) {
                mainHandler.removeCallbacks(verifyRestoredNameRunnable);
                mainHandler.postDelayed(verifyRestoredNameRunnable,
                        NAME_CHANGE_TIMEOUT_MS);
            }
        } catch (SecurityException ignored) { }
    }

    private void verifyRestoredBluetoothName() {
        SharedPreferences preferences = getSharedPreferences(
                BLUETOOTH_STATE_PREFERENCES, MODE_PRIVATE);
        if (!preferences.getBoolean(PREF_RESTORE_BLUETOOTH_NAME, false) ||
                adapter == null || !hasConnectPermission()) {
            restoringBluetoothName = false;
            return;
        }
        String originalName = preferences.getString(PREF_ORIGINAL_BLUETOOTH_NAME, null);
        try {
            if (originalName != null && originalName.equals(adapter.getName())) {
                clearStoredBluetoothName(preferences);
            }
        } catch (SecurityException ignored) { }
    }

    private void clearStoredBluetoothName(SharedPreferences preferences) {
        restoringBluetoothName = false;
        mainHandler.removeCallbacks(verifyRestoredNameRunnable);
        preferences.edit()
                .remove(PREF_ORIGINAL_BLUETOOTH_NAME)
                .remove(PREF_RESTORE_BLUETOOTH_NAME)
                .apply();
    }

    private void finishHostingWindow(CharSequence status) {
        boolean nameChangeWasPending = waitingForDiscoverableName;
        automaticConversationProcess = false;
        hostingConversation = false;
        waitingForDiscoverableName = false;
        discoverabilityRequestLaunched = false;
        pendingDiscoverableBluetoothName = null;
        mainHandler.removeCallbacks(discoverableNameTimeoutRunnable);
        mainHandler.removeCallbacks(discoverabilityTimeoutRunnable);
        stopServer();
        if (nameChangeWasPending) {
            mainHandler.postDelayed(this::restoreOriginalBluetoothName,
                    NAME_CHANGE_TIMEOUT_MS);
        } else {
            restoreOriginalBluetoothName();
        }
        runOnUiThread(() -> {
            updateAutomaticConversationControls();
            mainHandler.postDelayed(this::ensureConnectionServerListening, 350L);
            scheduleAutomaticConversationStart();
        });
        if (status != null) {
            synchronized (socketLock) {
                if (activeSocket == null) showStatus(status);
            }
        }
    }

    private void startServer() {
        if (stopped || !hasConnectPermission() || adapter == null ||
                !adapter.isEnabled()) return;
        synchronized (socketLock) {
            if (serverThread != null && serverThread.isAlive()) {
                if (!acceptingConnections) {
                    mainHandler.postDelayed(this::ensureConnectionServerListening, 250L);
                }
                return;
            }
            acceptingConnections = true;
            serverThread = new Thread(() -> {
                BluetoothServerSocket localServer = null;
                try {
                    localServer = adapter.listenUsingRfcommWithServiceRecord(
                            SERVICE_NAME, CHAT_UUID);
                    synchronized (socketLock) {
                        if (stopped || !acceptingConnections) {
                            closeQuietly(localServer);
                            return;
                        }
                        serverSocket = localServer;
                    }
                    while (!stopped && acceptingConnections) {
                        BluetoothSocket incoming = localServer.accept();
                        if (incoming != null) handleIncomingSocket(incoming);
                    }
                } catch (IOException | SecurityException e) {
                    if (!stopped && acceptingConnections) {
                        finishHostingWindow(uiText(R.string.status_wait_failed));
                        vibrateConnectionEvent(HAPTIC_FAILURE);
                    }
                } finally {
                    closeQuietly(localServer);
                    synchronized (socketLock) {
                        if (serverSocket == localServer) serverSocket = null;
                        if (serverThread == Thread.currentThread()) serverThread = null;
                    }
                }
            }, "BT-Server");
            serverThread.start();
        }
    }

    private void ensureConnectionServerListening() {
        if (stopped || adapter == null || !hasConnectPermission()) return;
        synchronized (socketLock) {
            if (activeSocket != null) return;
        }
        try {
            if (adapter.isEnabled()) startServer();
        } catch (SecurityException ignored) { }
    }

    private void stopServer() {
        acceptingConnections = false;
        synchronized (socketLock) {
            closeQuietly(serverSocket);
            serverSocket = null;
        }
    }

    private void handleIncomingSocket(BluetoothSocket incoming) {
        synchronized (socketLock) {
            if (stopped || connecting || activeSocket != null ||
                    pendingIncomingSocket != null) {
                closeQuietly(incoming);
                return;
            }
            pendingIncomingSocket = incoming;
        }
        CharSequence peerName = getDeviceLabel(incoming.getRemoteDevice());
        showStatus(uiText(R.string.status_incoming_connection, peerName));
        vibrateConnectionEvent(HAPTIC_REQUEST);
        runOnUiThread(() -> {
            if (activityVisible && !stopped) {
                showIncomingConnectionConfirmation(incoming);
            }
        });
        mainHandler.postDelayed(() -> {
            synchronized (socketLock) {
                if (pendingIncomingSocket != incoming) return;
                pendingIncomingSocket = null;
            }
            closeQuietly(incoming);
            if (incomingConnectionDialog != null) {
                incomingConnectionDialog.dismiss();
                incomingConnectionDialog = null;
            }
            showStatus(uiText(R.string.status_incoming_timeout));
            vibrateConnectionEvent(HAPTIC_FAILURE);
        }, 30_000L);
    }

    private void showIncomingConnectionConfirmation(BluetoothSocket incoming) {
        if (languageDialog != null || !activityVisible) return;
        synchronized (socketLock) {
            if (pendingIncomingSocket != incoming || activeSocket != null) return;
        }
        if (incomingConnectionDialog != null && incomingConnectionDialog.isShowing()) return;
        CharSequence peerName = getDeviceLabel(incoming.getRemoteDevice());
        incomingConnectionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.incoming_connection_title)
                .setMessage(uiText(R.string.incoming_connection_message, peerName))
                .setPositiveButton(R.string.accept_connection, (dialog, which) -> {
                    synchronized (socketLock) {
                        if (pendingIncomingSocket == incoming) {
                            pendingIncomingSocket = null;
                        }
                    }
                    incomingConnectionDialog = null;
                    adoptSocket(incoming);
                })
                .setNegativeButton(R.string.reject_connection, (dialog, which) -> {
                    synchronized (socketLock) {
                        if (pendingIncomingSocket == incoming) {
                            pendingIncomingSocket = null;
                        }
                    }
                    incomingConnectionDialog = null;
                    rejectIncomingConnection(incoming);
                })
                .setCancelable(false)
                .create();
        incomingConnectionDialog.show();
    }

    private void pairOrConnectTo(BluetoothDevice device) {
        if (device == null || connecting || stopped) return;
        synchronized (socketLock) {
            if (activeSocket != null) return;
        }
        cancelDiscoveryBeforeConnect();
        try {
            int bondState = device.getBondState();
            if (bondState == BluetoothDevice.BOND_BONDED) {
                connectTo(device);
                return;
            }
            connecting = true;
            connectionCancellationRequested = false;
            pendingPairingDevice = device;
            CharSequence deviceLabel = getDiscoveryDeviceLabel(device);
            showStatus(uiText(R.string.status_pairing, deviceLabel));
            showPairingProgressDialog(deviceLabel);
            updateActionAvailability();
            if (bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    continueConnectionAfterPairing(device);
                } else {
                    finishPendingPairing(device, false);
                }
            }
        } catch (SecurityException e) {
            finishPendingPairing(device, false);
            showStatus(getConnectionPermissionDeniedStatus());
        }
    }

    private void continueConnectionAfterPairing(BluetoothDevice device) {
        if (!isPendingPairingDevice(device) || connectionCancellationRequested || stopped) {
            return;
        }
        pendingPairingDevice = null;
        connecting = false;
        dismissConnectionProgressDialog();
        updateActionAvailability();
        try {
            device.fetchUuidsWithSdp();
        } catch (SecurityException ignored) { }
        mainHandler.postDelayed(() -> {
            if (!stopped && !connectionCancellationRequested) connectTo(device);
        }, 500L);
    }

    private void resumePendingPairingState() {
        BluetoothDevice device = pendingPairingDevice;
        if (device == null || !connecting || connectionCancellationRequested) return;
        try {
            int bondState = device.getBondState();
            if (bondState == BluetoothDevice.BOND_BONDED) {
                continueConnectionAfterPairing(device);
            } else if (bondState == BluetoothDevice.BOND_NONE) {
                mainHandler.postDelayed(() -> verifyPendingPairingAfterResume(device),
                        1_000L);
            }
        } catch (SecurityException e) {
            finishPendingPairing(device, false);
            showStatus(getConnectionPermissionDeniedStatus());
        }
    }

    private void verifyPendingPairingAfterResume(BluetoothDevice device) {
        if (!isPendingPairingDevice(device) || !connecting ||
                connectionCancellationRequested || stopped) return;
        try {
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                finishPendingPairing(device, false);
            }
        } catch (SecurityException e) {
            finishPendingPairing(device, false);
            showStatus(getConnectionPermissionDeniedStatus());
        }
    }

    private void finishPendingPairing(BluetoothDevice device, boolean cancelled) {
        if (!isPendingPairingDevice(device)) return;
        pendingPairingDevice = null;
        connecting = false;
        dismissConnectionProgressDialog();
        CharSequence status = cancelled
                ? uiText(R.string.status_connection_attempt_cancelled)
                : uiText(R.string.status_pairing_failed,
                        getDiscoveryDeviceLabel(device));
        updateConnectionUi(status);
        if (!cancelled) vibrateConnectionEvent(HAPTIC_FAILURE);
        scheduleAutomaticConversationStart();
    }

    private CharSequence getDiscoveryDeviceLabel(BluetoothDevice device) {
        String key = getDeviceKey(device);
        String discoveredName = key == null ? null : discoveredConversationNames.get(key);
        return discoveredName != null && !discoveredName.trim().isEmpty()
                ? discoveredName.trim() : getDeviceLabel(device);
    }

    private boolean isPendingPairingDevice(BluetoothDevice device) {
        BluetoothDevice pending = pendingPairingDevice;
        if (pending == null || device == null) return false;
        try {
            return pending.getAddress().equals(device.getAddress());
        } catch (SecurityException e) {
            return pending.equals(device);
        }
    }

    private void connectTo(BluetoothDevice device) {
        if (connecting || stopped) return;
        synchronized (socketLock) {
            if (activeSocket != null) return;
        }
        connecting = true;
        connectionCancellationRequested = false;
        final CharSequence deviceLabel = getDeviceLabel(device);
        showStatus(uiText(R.string.status_connecting_to, deviceLabel));
        showConnectionProgressDialog(deviceLabel);
        Thread worker = new Thread(() -> {
            try {
                IOException lastFailure = null;
                for (int attempt = 1;
                     attempt <= MAX_CONNECT_ATTEMPTS && !stopped &&
                             !connectionCancellationRequested;
                     attempt++) {
                    synchronized (socketLock) {
                        if (activeSocket != null) return;
                    }
                    BluetoothSocket outgoing = null;
                    try {
                        cancelDiscoveryBeforeConnect();
                        if (attempt > 1) {
                            showStatus(uiText(R.string.status_connect_retry,
                                    attempt, MAX_CONNECT_ATTEMPTS, deviceLabel));
                        }
                        outgoing = device.createRfcommSocketToServiceRecord(CHAT_UUID);
                        final BluetoothSocket socketBeingConnected = outgoing;
                        synchronized (socketLock) {
                            if (connectionCancellationRequested || stopped) {
                                closeQuietly(outgoing);
                                return;
                            }
                            connectingSocket = outgoing;
                        }
                        Runnable connectionTimeout = () -> closeQuietly(socketBeingConnected);
                        mainHandler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
                        try {
                            // O socket seguro aciona o pareamento do Android quando necessario.
                            outgoing.connect(); // Nunca conectar na thread da interface.
                        } finally {
                            mainHandler.removeCallbacks(connectionTimeout);
                        }
                        synchronized (socketLock) {
                            if (connectingSocket == outgoing) connectingSocket = null;
                        }
                        if (connectionCancellationRequested) {
                            closeQuietly(outgoing);
                            return;
                        }
                        adoptSocket(outgoing);
                        outgoing = null;
                        return;
                    } catch (IOException e) {
                        lastFailure = e;
                        closeQuietly(outgoing);
                    } catch (SecurityException e) {
                        closeQuietly(outgoing);
                        if (!connectionCancellationRequested) {
                            showStatus(getConnectionPermissionDeniedStatus());
                            vibrateConnectionEvent(HAPTIC_FAILURE);
                        }
                        return;
                    } finally {
                        synchronized (socketLock) {
                            if (connectingSocket == outgoing) connectingSocket = null;
                        }
                    }
                    if (connectionCancellationRequested) return;
                    if (attempt < MAX_CONNECT_ATTEMPTS && !stopped) {
                        try {
                            // Atualiza o registro SDP remoto antes da nova tentativa.
                            device.fetchUuidsWithSdp();
                        } catch (SecurityException ignored) { }
                        try {
                            Thread.sleep(CONNECT_RETRY_DELAY_MS * attempt);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                synchronized (socketLock) {
                    if (activeSocket == null && !stopped &&
                            !connectionCancellationRequested) {
                        showStatus(uiText(
                                R.string.status_connect_failed, deviceLabel));
                        vibrateConnectionEvent(HAPTIC_FAILURE);
                    }
                }
                if (lastFailure != null && !connectionCancellationRequested) {
                    android.util.Log.w("ChatBluetooth",
                            "RFCOMM connection failed after retries", lastFailure);
                }
            } finally {
                connecting = false;
                synchronized (socketLock) {
                    connectingSocket = null;
                    if (connectionThread == Thread.currentThread()) connectionThread = null;
                }
                if (!stopped) runOnUiThread(() -> {
                    dismissConnectionProgressDialog();
                    updateActionAvailability();
                    scheduleAutomaticConversationStart();
                });
            }
        }, "BT-Connect");
        synchronized (socketLock) { connectionThread = worker; }
        worker.start();
    }

    private void showConnectionProgressDialog(CharSequence deviceLabel) {
        dismissConnectionProgressDialog();
        connectionProgressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.connecting_conversation_title)
                .setMessage(uiText(R.string.connecting_conversation_message, deviceLabel))
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> cancelConnectionAttempt())
                .setCancelable(false)
                .create();
        connectionProgressDialog.show();
    }

    private void showPairingProgressDialog(CharSequence deviceLabel) {
        dismissConnectionProgressDialog();
        connectionProgressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pairing_connection_title)
                .setMessage(uiText(R.string.pairing_connection_message, deviceLabel))
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> cancelConnectionAttempt())
                .setCancelable(false)
                .create();
        connectionProgressDialog.show();
    }

    private void cancelConnectionAttempt() {
        BluetoothSocket socket;
        Thread worker;
        BluetoothDevice pairingDevice;
        synchronized (socketLock) {
            if (!connecting || activeSocket != null) return;
            connectionCancellationRequested = true;
            socket = connectingSocket;
            connectingSocket = null;
            worker = connectionThread;
            pairingDevice = pendingPairingDevice;
            pendingPairingDevice = null;
            if (pairingDevice != null) connecting = false;
        }
        closeQuietly(socket);
        if (worker != null) worker.interrupt();
        dismissConnectionProgressDialog();
        updateConnectionUi(uiText(R.string.status_connection_attempt_cancelled));
        scheduleAutomaticConversationStart();
    }

    private void dismissConnectionProgressDialog() {
        if (connectionProgressDialog != null) {
            connectionProgressDialog.dismiss();
            connectionProgressDialog = null;
        }
    }

    private void cancelDiscoveryBeforeConnect() {
        if (adapter == null) return;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasScanPermission()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) { }
    }

    private void adoptSocket(BluetoothSocket socket) {
        synchronized (socketLock) {
            if (stopped || connectionCancellationRequested || activeSocket != null) {
                closeQuietly(socket);
                return;
            }
            activeSocket = socket;
            connecting = false;
        }
        cancelDeviceDiscovery(false);
        finishHostingWindow(null);
        CharSequence peerName = uiText(R.string.nearby_device_fallback);
        try {
            String name = socket.getRemoteDevice().getName();
            if (name != null && !name.trim().isEmpty()) peerName = name;
        } catch (SecurityException ignored) { }
        showStatus(uiText(R.string.status_connected, peerName));
        runOnUiThread(() -> {
            chatRetainedWithoutConnection = false;
            chatConnectionStatusView.setVisibility(View.GONE);
            showChatScreen();
        });
        vibrateConnectionEvent(HAPTIC_SUCCESS);
        new Thread(() -> receiveLoop(socket), "BT-Receive").start();
    }

    private void receiveLoop(BluetoothSocket socket) {
        CharSequence closeStatus = uiText(R.string.status_connection_closed);
        try {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            while (!stopped) {
                int frame = input.readInt();
                if (frame == CONTROL_CONNECTION_REJECTED) {
                    closeStatus = uiText(R.string.status_connection_rejected);
                    break;
                }
                if (frame == CONTROL_MEDIA_PROGRESS) {
                    byte payloadType = (byte) input.readUnsignedByte();
                    int progress = input.readInt();
                    if (!isMediaPayloadType(payloadType) || progress < 0 ||
                            progress > 100) {
                        throw new IOException("Progresso de mídia inválido.");
                    }
                    updateIncomingMediaProgress(payloadType, progress);
                    continue;
                }
                if (frame == CONTROL_MEDIA_CANCEL) {
                    byte payloadType = (byte) input.readUnsignedByte();
                    if (!isMediaPayloadType(payloadType)) {
                        throw new IOException("Cancelamento de mídia inválido.");
                    }
                    cancelIncomingMediaProgress(payloadType);
                    continue;
                }
                if (frame == CONTROL_MEDIA_PACKET) {
                    byte payloadType = (byte) input.readUnsignedByte();
                    int mediaLength = input.readInt();
                    if (!isMediaPayloadType(payloadType) ||
                            mediaLength < MIN_PACKET_BYTES ||
                            mediaLength > MAX_PACKET_BYTES) {
                        throw new IOException("Pacote de mídia inválido.");
                    }
                    byte[] mediaPacket = readIncomingMediaPacket(
                            input, payloadType, mediaLength);
                    runOnUiThread(() -> {
                        synchronized (socketLock) {
                            if (activeSocket == socket && !stopped) {
                                appendReceivedBubble(mediaPacket, true);
                            }
                        }
                    });
                    continue;
                }
                int length = frame;
                if (length < MIN_PACKET_BYTES || length > MAX_PACKET_BYTES) {
                    throw new IOException("Pacote criptografado com tamanho inválido.");
                }
                byte[] packet = new byte[length];
                input.readFully(packet);
                runOnUiThread(() -> {
                    synchronized (socketLock) {
                        if (activeSocket == socket && !stopped) {
                            appendReceivedBubble(packet);
                        }
                    }
                });
            }
        } catch (IOException e) {
            // Fim da conexao, dados invalidos ou perda de sinal.
        } finally {
            finishSocket(socket, closeStatus);
        }
    }

    private byte[] readIncomingMediaPacket(DataInputStream input, byte payloadType,
                                           int length) throws IOException {
        byte[] packet = new byte[length];
        int offset = 0;
        int lastDisplayedProgress = calculateMediaTransferProgress(
                payloadType, 0, length);
        while (offset < length) {
            int read = input.read(packet, offset, Math.min(32 * 1024, length - offset));
            if (read <= 0) throw new IOException("Transferência de mídia interrompida.");
            offset += read;
            int progress = calculateMediaTransferProgress(payloadType, offset, length);
            if (progress > lastDisplayedProgress) {
                for (int next = lastDisplayedProgress + 1; next <= progress; next++) {
                    updateIncomingMediaProgress(payloadType, next);
                }
                lastDisplayedProgress = progress;
            }
        }
        return packet;
    }

    private static int calculateMediaTransferProgress(byte payloadType, int received,
                                                      int total) {
        if (received >= total) return 100;
        int start;
        int span;
        if (payloadType == PAYLOAD_IMAGE) {
            start = 90;
            span = 9;
        } else if (payloadType == PAYLOAD_STICKER) {
            start = 10;
            span = 89;
        } else if (payloadType == PAYLOAD_AUDIO) {
            start = 65;
            span = 34;
        } else {
            start = 68;
            span = 31;
        }
        return start + (int) ((long) received * span / Math.max(1, total));
    }

    private static boolean isMediaPayloadType(byte payloadType) {
        return payloadType == PAYLOAD_IMAGE || payloadType == PAYLOAD_AUDIO ||
                payloadType == PAYLOAD_MP3 || payloadType == PAYLOAD_STICKER;
    }

    private void rejectIncomingConnection(BluetoothSocket incoming) {
        new Thread(() -> {
            try {
                DataOutputStream output = new DataOutputStream(incoming.getOutputStream());
                output.writeInt(CONTROL_CONNECTION_REJECTED);
                output.flush();
            } catch (IOException ignored) {
                // Mesmo sem enviar o controle, encerra a tentativa de conexao recebida.
            } finally {
                closeQuietly(incoming);
            }
        }, "BT-Reject").start();
        CharSequence status = uiText(R.string.status_connection_rejected);
        finishHostingWindow(null);
        retainChatWithoutConnection(status);
        vibrateConnectionEvent(HAPTIC_FAILURE);
    }

    private void sendText() {
        final String text = messageInput.getText().toString();
        if (text.trim().isEmpty()) return;
        final byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > MAX_TEXT_BYTES) {
            Arrays.fill(textBytes, (byte) 0);
            toast(uiText(R.string.message_too_long));
            return;
        }
        final char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(textBytes, (byte) 0);
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            passphraseInput.requestFocus();
            return;
        }
        final BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket == null) {
            Arrays.fill(textBytes, (byte) 0);
            Arrays.fill(password, '\0');
            toast(uiText(R.string.connect_before_sending));
            return;
        }
        messageInput.setText("");
        sendQueue.execute(() -> {
            byte[] encrypted = null;
            try {
                // Falha de criptografia interrompe o envio: sem fallback plaintext.
                encrypted = encryptContent(PAYLOAD_TEXT, textBytes, password);
                if (encrypted.length > MAX_PACKET_BYTES) {
                    throw new GeneralSecurityException("Pacote excede o limite.");
                }
                writeEncryptedPacket(socket, encrypted);
                runOnUiThread(() -> {
                    if (!stopped) appendSentBubble(text);
                });
            } catch (GeneralSecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped) {
                        toast(uiText(R.string.message_protection_failed));
                        restoreUnsentText(text);
                    }
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (!stopped) {
                        toast(uiText(R.string.message_send_failed));
                        restoreUnsentText(text);
                    }
                });
                finishSocket(socket, uiText(R.string.status_connection_lost));
            } finally {
                Arrays.fill(textBytes, (byte) 0);
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(password, '\0');
            }
        });
    }

    private boolean sendSticker(Sticker sticker) {
        if (stopped || sendingSticker || sendingImage || sendingAudio || sendingMp3 ||
                recordingAudio || stickerController.isBusy()) {
            toast(uiText(R.string.sticker_wait_for_media));
            return false;
        }
        if (!ensureConversationAvailableForMessaging()) return false;
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            toast(uiText(R.string.password_too_short));
            return false;
        }
        final BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket == null) {
            Arrays.fill(password, '\0');
            toast(uiText(R.string.connect_before_sending));
            return false;
        }
        ImageSendOperation operation = new ImageSendOperation(socket, password);
        currentStickerOperation = operation;
        sendingSticker = true;
        updateActionAvailability();
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setPadding(dp(24), dp(16), dp(24), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.sending_sticker)
                .setView(progress).setCancelable(false)
                .setNegativeButton(R.string.cancel_sticker, (d, which) -> {
                    operation.cancelled = true;
                    if (operation.writingToConnection) closeQuietly(operation.socket);
                }).create();
        stickerSendDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        sendQueue.execute(() -> {
            byte[] encrypted = null;
            boolean startedWriting = false;
            boolean success = false;
            try {
                checkImageSendCancelled(operation);
                encrypted = encryptContent(PAYLOAD_STICKER, sticker.bytes, operation.password);
                checkImageSendCancelled(operation);
                operation.lastReceiverProgress = reportReceiverMediaProgress(socket, PAYLOAD_STICKER, 10, 0);
                synchronized (socketLock) {
                    if (activeSocket != socket || stopped) throw new IOException("Conexão encerrada");
                }
                operation.writingToConnection = true;
                checkImageSendCancelled(operation);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                startedWriting = true;
                output.writeInt(CONTROL_MEDIA_PACKET);
                output.writeByte(PAYLOAD_STICKER);
                output.writeInt(encrypted.length);
                for (int offset = 0; offset < encrypted.length;) {
                    checkImageSendCancelled(operation);
                    int size = Math.min(32 * 1024, encrypted.length - offset);
                    output.write(encrypted, offset, size);
                    offset += size;
                    final int percent = 10 + (int) ((long) offset * 90 / encrypted.length);
                    runOnUiThread(() -> { if (!stopped && dialog.isShowing()) progress.setProgress(percent); });
                }
                output.flush();
                operation.writingToConnection = false;
                checkImageSendCancelled(operation);
                success = true;
                runOnUiThread(() -> { if (!stopped) appendSentStickerBubble(sticker); });
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                if (startedWriting) finishSocket(socket, uiText(R.string.status_connection_lost));
                if (!operation.cancelled) runOnUiThread(() -> {
                    if (!stopped) toast(uiText(R.string.sticker_send_failed));
                });
            } finally {
                operation.writingToConnection = false;
                if (!success && !startedWriting && operation.lastReceiverProgress > 0) {
                    cancelReceiverMediaProgress(socket, PAYLOAD_STICKER);
                }
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(operation.password, '\0');
                runOnUiThread(() -> {
                    if (currentStickerOperation != operation) return;
                    currentStickerOperation = null;
                    sendingSticker = false;
                    dialog.dismiss();
                    if (stickerSendDialog == dialog) stickerSendDialog = null;
                    if (!stopped) updateActionAvailability();
                });
            }
        });
        return true;
    }

    private void sendImage(Uri uri) {
        if (sendingImage) return;

        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            passphraseInput.requestFocus();
            return;
        }

        final BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket == null) {
            Arrays.fill(password, '\0');
            toast(uiText(R.string.connect_before_sending));
            return;
        }

        ImageSendOperation operation = new ImageSendOperation(socket, password);
        currentImageOperation = operation;
        sendingImage = true;
        updateActionAvailability();
        showImageProgress(operation);
        sendQueue.execute(() -> {
            PreparedImage prepared = null;
            byte[] encrypted = null;
            boolean startedWriting = false;
            boolean bitmapAddedToConversation = false;
            boolean sentSuccessfully = false;
            try {
                checkImageSendCancelled(operation);
                prepared = prepareImageForSending(uri, operation);
                final PreparedImage sentImage = prepared;
                updateImageProgress(operation, 82, R.string.image_progress_encrypting);
                checkImageSendCancelled(operation);
                encrypted = encryptContent(
                        PAYLOAD_IMAGE, prepared.bytes, operation.password);
                if (encrypted.length > MAX_PACKET_BYTES) throw new ImageTooLargeException();
                checkImageSendCancelled(operation);
                updateImageProgress(operation, 90, R.string.image_progress_sending);
                startedWriting = true;
                operation.writingToConnection = true;
                try {
                    writeEncryptedImagePacket(operation, encrypted);
                } finally {
                    operation.writingToConnection = false;
                }
                checkImageSendCancelled(operation);
                sentSuccessfully = true;
                bitmapAddedToConversation = true;
                runOnUiThread(() -> {
                    if (!stopped) {
                        appendSentImageBubble(sentImage.bitmap);
                        completeImageProgress(operation);
                    } else if (!sentImage.bitmap.isRecycled()) {
                        sentImage.bitmap.recycle();
                    }
                });
            } catch (ImageSendCancelledException e) {
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_closed));
                }
            } catch (ImageTooLargeException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.image_too_large));
                    }
                });
            } catch (InvalidImageException | OutOfMemoryError e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.invalid_image));
                    }
                });
            } catch (GeneralSecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.image_protection_failed));
                    }
                });
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.image_send_failed));
                    }
                });
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_lost));
                }
            } finally {
                if (!sentSuccessfully && operation.lastReceiverProgress > 0) {
                    cancelReceiverMediaProgress(operation.socket, PAYLOAD_IMAGE);
                }
                if (prepared != null) {
                    Arrays.fill(prepared.bytes, (byte) 0);
                    if (!bitmapAddedToConversation && !prepared.bitmap.isRecycled()) {
                        prepared.bitmap.recycle();
                    }
                }
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(operation.password, '\0');
                final boolean completed = sentSuccessfully;
                runOnUiThread(() -> {
                    if (currentImageOperation == operation) {
                        sendingImage = false;
                        if (!stopped) updateActionAvailability();
                        if (!completed) {
                            currentImageOperation = null;
                            dismissImageProgress();
                        }
                    }
                });
            }
        });
    }

    private void sendAudio(File file) {
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            deleteQuietly(file);
            passphraseInput.setError(uiText(R.string.password_too_short));
            return;
        }
        BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket == null) {
            Arrays.fill(password, '\0');
            deleteQuietly(file);
            toast(uiText(R.string.connect_before_sending));
            return;
        }

        AudioSendOperation operation = new AudioSendOperation(socket, password, file);
        currentAudioOperation = operation;
        sendingAudio = true;
        updateActionAvailability();
        showAudioProgress(operation);
        sendQueue.execute(() -> {
            byte[] audioBytes = null;
            byte[] encrypted = null;
            boolean startedWriting = false;
            boolean retainedForPlayback = false;
            boolean sentSuccessfully = false;
            try {
                checkAudioSendCancelled(operation);
                audioBytes = readAudioBytes(operation);
                updateAudioProgress(operation, 55, R.string.audio_progress_encrypting);
                checkAudioSendCancelled(operation);
                encrypted = encryptContent(PAYLOAD_AUDIO, audioBytes, operation.password);
                if (encrypted.length > MAX_PACKET_BYTES) throw new ImageTooLargeException();
                checkAudioSendCancelled(operation);
                updateAudioProgress(operation, 65, R.string.audio_progress_sending);
                startedWriting = true;
                operation.writingToConnection = true;
                try {
                    writeEncryptedAudioPacket(operation, encrypted);
                } finally {
                    operation.writingToConnection = false;
                }
                checkAudioSendCancelled(operation);
                sentSuccessfully = true;
                retainedForPlayback = true;
                runOnUiThread(() -> {
                    if (!stopped) {
                        temporaryAudioFiles.add(file);
                        appendSentAudioBubble(file);
                        completeAudioProgress(operation);
                    } else {
                        deleteQuietly(file);
                    }
                });
            } catch (ImageSendCancelledException e) {
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_closed));
                }
            } catch (ImageTooLargeException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.audio_too_large));
                    }
                });
            } catch (GeneralSecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.audio_protection_failed));
                    }
                });
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.audio_send_failed));
                    }
                });
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_lost));
                }
            } finally {
                if (!sentSuccessfully && operation.lastReceiverProgress > 0) {
                    cancelReceiverMediaProgress(operation.socket, PAYLOAD_AUDIO);
                }
                if (audioBytes != null) Arrays.fill(audioBytes, (byte) 0);
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(operation.password, '\0');
                if (!retainedForPlayback) deleteQuietly(file);
                final boolean completed = sentSuccessfully;
                runOnUiThread(() -> {
                    if (currentAudioOperation == operation) {
                        sendingAudio = false;
                        if (!stopped) updateActionAvailability();
                        if (!completed) {
                            currentAudioOperation = null;
                            dismissAudioProgress();
                        }
                    }
                });
            }
        });
    }

    private byte[] readAudioBytes(AudioSendOperation operation) throws IOException {
        File file = operation.file;
        long length = file.length();
        if (length <= 0L) throw new IOException("Áudio vazio.");
        if (length > MAX_AUDIO_BYTES) throw new ImageTooLargeException();
        updateAudioProgress(operation, 5, R.string.audio_progress_validating);
        checkAudioSendCancelled(operation);
        byte[] buffer = new byte[32 * 1024];
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) length)) {
            int total = 0;
            int lastProgress = 5;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkAudioSendCancelled(operation);
                if (total > MAX_AUDIO_BYTES - read) throw new ImageTooLargeException();
                output.write(buffer, 0, read);
                total += read;
                int progress = 10 + Math.min(35,
                        (int) ((long) total * 35L / Math.max(1L, length)));
                if (progress > lastProgress) {
                    lastProgress = progress;
                    updateAudioProgress(operation, progress, R.string.audio_progress_loading);
                }
            }
            byte[] bytes = output.toByteArray();
            if (!isM4aAudio(bytes, 0, bytes.length)) {
                Arrays.fill(bytes, (byte) 0);
                throw new IOException("Formato de áudio inválido.");
            }
            return bytes;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void sendMp3(Uri uri) {
        char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            passphraseInput.setError(uiText(R.string.password_too_short));
            return;
        }
        BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket == null) {
            Arrays.fill(password, '\0');
            toast(uiText(R.string.connect_before_sending));
            return;
        }

        Mp3SendOperation operation = new Mp3SendOperation(socket, password, uri);
        currentMp3Operation = operation;
        sendingMp3 = true;
        updateActionAvailability();
        showMp3Progress(operation);
        sendQueue.execute(() -> {
            byte[] mp3Bytes = null;
            byte[] encrypted = null;
            File playbackFile = null;
            boolean startedWriting = false;
            boolean retainedForPlayback = false;
            boolean sentSuccessfully = false;
            try {
                checkMp3SendCancelled(operation);
                mp3Bytes = readMp3Bytes(operation);
                updateMp3Progress(operation, 50, R.string.mp3_progress_preparing);
                checkMp3SendCancelled(operation);
                playbackFile = writeTemporaryAudioFile(
                        mp3Bytes, "chat_audio_mp3_", ".mp3");
                final File sentFile = playbackFile;
                updateMp3Progress(operation, 58, R.string.mp3_progress_encrypting);
                encrypted = encryptContent(PAYLOAD_MP3, mp3Bytes, operation.password);
                if (encrypted.length > MAX_PACKET_BYTES) throw new ImageTooLargeException();
                checkMp3SendCancelled(operation);
                updateMp3Progress(operation, 68, R.string.mp3_progress_sending);
                startedWriting = true;
                operation.writingToConnection = true;
                try {
                    writeEncryptedMp3Packet(operation, encrypted);
                } finally {
                    operation.writingToConnection = false;
                }
                checkMp3SendCancelled(operation);
                sentSuccessfully = true;
                retainedForPlayback = true;
                runOnUiThread(() -> {
                    if (!stopped) {
                        temporaryAudioFiles.add(sentFile);
                        appendSentAudioBubble(sentFile);
                        completeMp3Progress(operation);
                    } else {
                        deleteQuietly(sentFile);
                    }
                });
            } catch (ImageSendCancelledException e) {
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_closed));
                }
            } catch (ImageTooLargeException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.mp3_too_large));
                    }
                });
            } catch (InvalidMp3Exception e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.invalid_mp3));
                    }
                });
            } catch (GeneralSecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.mp3_protection_failed));
                    }
                });
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> {
                    if (!stopped && !operation.cancelled) {
                        toast(uiText(R.string.mp3_send_failed));
                    }
                });
                if (startedWriting) {
                    finishSocket(socket, uiText(R.string.status_connection_lost));
                }
            } finally {
                if (!sentSuccessfully && operation.lastReceiverProgress > 0) {
                    cancelReceiverMediaProgress(operation.socket, PAYLOAD_MP3);
                }
                if (mp3Bytes != null) Arrays.fill(mp3Bytes, (byte) 0);
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(operation.password, '\0');
                if (!retainedForPlayback) deleteQuietly(playbackFile);
                final boolean completed = sentSuccessfully;
                runOnUiThread(() -> {
                    if (currentMp3Operation == operation) {
                        sendingMp3 = false;
                        if (!stopped) updateActionAvailability();
                        if (!completed) {
                            currentMp3Operation = null;
                            dismissAudioProgress();
                        }
                    }
                });
            }
        });
    }

    private byte[] readMp3Bytes(Mp3SendOperation operation) throws IOException {
        String mimeType = getContentResolver().getType(operation.uri);
        if (mimeType != null && !mimeType.equals("audio/mpeg") &&
                !mimeType.equals("audio/mp3") && !mimeType.equals("audio/x-mp3") &&
                !mimeType.equals("audio/mpeg3") && !mimeType.equals("audio/x-mpeg-3") &&
                !mimeType.equals("application/octet-stream")) {
            throw new InvalidMp3Exception();
        }
        long declaredSize = queryImageSize(operation.uri);
        if (declaredSize > MAX_AUDIO_BYTES) throw new ImageTooLargeException();
        updateMp3Progress(operation, 5, R.string.mp3_progress_validating);
        checkMp3SendCancelled(operation);
        int initialCapacity = declaredSize > 0L
                ? (int) Math.min(declaredSize, MAX_AUDIO_BYTES) : 64 * 1024;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = getContentResolver().openInputStream(operation.uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity)) {
            if (input == null) throw new InvalidMp3Exception();
            int total = 0;
            int lastProgress = 5;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkMp3SendCancelled(operation);
                if (total > MAX_AUDIO_BYTES - read) throw new ImageTooLargeException();
                output.write(buffer, 0, read);
                total += read;
                int progress = declaredSize > 0L
                        ? 10 + Math.min(35, (int) ((long) total * 35L / declaredSize))
                        : Math.min(45, 10 + total / (256 * 1024));
                if (progress > lastProgress) {
                    lastProgress = progress;
                    updateMp3Progress(operation, progress, R.string.mp3_progress_loading);
                }
            }
            byte[] bytes = output.toByteArray();
            if (!isMp3Audio(bytes, 0, bytes.length)) {
                Arrays.fill(bytes, (byte) 0);
                throw new InvalidMp3Exception();
            }
            return bytes;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void showMp3Progress(Mp3SendOperation operation) {
        dismissAudioProgress();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_audio_progress);
        applySystemBarInsets(dialog.getWindow());
        dialog.setCancelable(false);
        audioProgressDialog = dialog;
        TextView title = dialog.findViewById(R.id.audioProgressTitle);
        title.setText(uiText(R.string.mp3_progress_title));
        audioProgressBar = dialog.findViewById(R.id.audioProgressBar);
        audioProgressPercent = dialog.findViewById(R.id.audioProgressPercent);
        audioProgressStage = dialog.findViewById(R.id.audioProgressStage);
        audioProgressCancelButton = dialog.findViewById(R.id.cancelAudioProgressButton);
        audioProgressCancelButton.setOnClickListener(v -> cancelMp3Send(operation));
        setAudioProgressValues(0, uiText(R.string.mp3_progress_starting));
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setStatusBarColor(getColor(R.color.surface));
            dialog.getWindow().setNavigationBarColor(getColor(R.color.surface));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void updateMp3Progress(Mp3SendOperation operation, int progress, int stageString) {
        if (operation.cancelled) return;
        if (!operation.writingToConnection) {
            operation.lastReceiverProgress = reportReceiverMediaProgress(
                    operation.socket, PAYLOAD_MP3, progress,
                    operation.lastReceiverProgress);
        }
        runOnUiThread(() -> {
            if (!stopped && currentMp3Operation == operation && !operation.cancelled) {
                setAudioProgressValues(progress, uiText(stageString));
            }
        });
    }

    private void completeMp3Progress(Mp3SendOperation operation) {
        if (currentMp3Operation != operation || operation.cancelled) return;
        setAudioProgressValues(100, uiText(R.string.mp3_progress_complete));
        if (audioProgressCancelButton != null) audioProgressCancelButton.setEnabled(false);
        mainHandler.postDelayed(() -> {
            if (currentMp3Operation == operation) {
                currentMp3Operation = null;
                dismissAudioProgress();
            }
        }, 450L);
    }

    private void cancelMp3Send(Mp3SendOperation operation) {
        if (currentMp3Operation != operation) return;
        operation.cancelled = true;
        Arrays.fill(operation.password, '\0');
        if (operation.writingToConnection) {
            finishSocket(operation.socket, uiText(R.string.status_connection_closed));
        }
        currentMp3Operation = null;
        sendingMp3 = false;
        dismissAudioProgress();
        updateActionAvailability();
    }

    private static void checkMp3SendCancelled(Mp3SendOperation operation)
            throws ImageSendCancelledException {
        if (operation.cancelled || Thread.currentThread().isInterrupted()) {
            throw new ImageSendCancelledException();
        }
    }

    private void writeEncryptedMp3Packet(Mp3SendOperation operation, byte[] encrypted)
            throws IOException {
        synchronized (socketLock) {
            if (activeSocket != operation.socket || stopped) {
                throw new IOException("Conexão encerrada antes do envio.");
            }
        }
        DataOutputStream output = new DataOutputStream(operation.socket.getOutputStream());
        checkMp3SendCancelled(operation);
        output.writeInt(CONTROL_MEDIA_PACKET);
        output.writeByte(PAYLOAD_MP3);
        output.writeInt(encrypted.length);
        int offset = 0;
        int lastProgress = 68;
        while (offset < encrypted.length) {
            checkMp3SendCancelled(operation);
            int count = Math.min(32 * 1024, encrypted.length - offset);
            output.write(encrypted, offset, count);
            offset += count;
            int progress = 68 + Math.min(31,
                    (int) ((long) offset * 31L / Math.max(1, encrypted.length)));
            if (progress > lastProgress) {
                lastProgress = progress;
                updateMp3Progress(operation, progress, R.string.mp3_progress_sending);
            }
        }
        output.flush();
    }

    private void showAudioProgress(AudioSendOperation operation) {
        dismissAudioProgress();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_audio_progress);
        applySystemBarInsets(dialog.getWindow());
        dialog.setCancelable(false);
        audioProgressDialog = dialog;
        audioProgressBar = dialog.findViewById(R.id.audioProgressBar);
        audioProgressPercent = dialog.findViewById(R.id.audioProgressPercent);
        audioProgressStage = dialog.findViewById(R.id.audioProgressStage);
        audioProgressCancelButton = dialog.findViewById(R.id.cancelAudioProgressButton);
        audioProgressCancelButton.setOnClickListener(v -> cancelAudioSend(operation));
        setAudioProgressValues(0, uiText(R.string.audio_progress_starting));
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setStatusBarColor(getColor(R.color.surface));
            dialog.getWindow().setNavigationBarColor(getColor(R.color.surface));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void updateAudioProgress(AudioSendOperation operation, int progress, int stageString) {
        if (operation.cancelled) return;
        if (!operation.writingToConnection) {
            operation.lastReceiverProgress = reportReceiverMediaProgress(
                    operation.socket, PAYLOAD_AUDIO, progress,
                    operation.lastReceiverProgress);
        }
        runOnUiThread(() -> {
            if (!stopped && currentAudioOperation == operation && !operation.cancelled) {
                setAudioProgressValues(progress, uiText(stageString));
            }
        });
    }

    private void setAudioProgressValues(int progress, CharSequence stage) {
        if (audioProgressBar == null || audioProgressPercent == null ||
                audioProgressStage == null) return;
        int safeProgress = Math.max(0, Math.min(100, progress));
        audioProgressBar.setProgress(safeProgress);
        audioProgressPercent.setText(uiText(R.string.image_progress_percent, safeProgress));
        audioProgressStage.setText(LocalizedText.refresh(this, stage));
    }

    private void completeAudioProgress(AudioSendOperation operation) {
        if (currentAudioOperation != operation || operation.cancelled) return;
        setAudioProgressValues(100, uiText(R.string.audio_progress_complete));
        if (audioProgressCancelButton != null) audioProgressCancelButton.setEnabled(false);
        mainHandler.postDelayed(() -> {
            if (currentAudioOperation == operation) {
                currentAudioOperation = null;
                dismissAudioProgress();
            }
        }, 450L);
    }

    private void cancelAudioSend(AudioSendOperation operation) {
        if (currentAudioOperation != operation) return;
        operation.cancelled = true;
        Arrays.fill(operation.password, '\0');
        if (operation.writingToConnection) {
            finishSocket(operation.socket, uiText(R.string.status_connection_closed));
        }
        currentAudioOperation = null;
        sendingAudio = false;
        dismissAudioProgress();
        updateActionAvailability();
    }

    private void dismissAudioProgress() {
        if (audioProgressDialog != null) audioProgressDialog.dismiss();
        audioProgressDialog = null;
        audioProgressBar = null;
        audioProgressPercent = null;
        audioProgressStage = null;
        audioProgressCancelButton = null;
    }

    private static void checkAudioSendCancelled(AudioSendOperation operation)
            throws ImageSendCancelledException {
        if (operation.cancelled || Thread.currentThread().isInterrupted()) {
            throw new ImageSendCancelledException();
        }
    }

    private void writeEncryptedAudioPacket(AudioSendOperation operation, byte[] encrypted)
            throws IOException {
        synchronized (socketLock) {
            if (activeSocket != operation.socket || stopped) {
                throw new IOException("Conexão encerrada antes do envio.");
            }
        }
        DataOutputStream output = new DataOutputStream(operation.socket.getOutputStream());
        checkAudioSendCancelled(operation);
        output.writeInt(CONTROL_MEDIA_PACKET);
        output.writeByte(PAYLOAD_AUDIO);
        output.writeInt(encrypted.length);
        int offset = 0;
        int lastProgress = 65;
        while (offset < encrypted.length) {
            checkAudioSendCancelled(operation);
            int count = Math.min(32 * 1024, encrypted.length - offset);
            output.write(encrypted, offset, count);
            offset += count;
            int progress = 65 + Math.min(34,
                    (int) ((long) offset * 34L / Math.max(1, encrypted.length)));
            if (progress > lastProgress) {
                lastProgress = progress;
                updateAudioProgress(operation, progress, R.string.audio_progress_sending);
            }
        }
        output.flush();
    }

    private void showImageProgress(ImageSendOperation operation) {
        dismissImageProgress();
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_image_progress);
        applySystemBarInsets(dialog.getWindow());
        dialog.setCancelable(false);
        imageProgressDialog = dialog;
        imageProgressBar = dialog.findViewById(R.id.imageProgressBar);
        imageProgressPercent = dialog.findViewById(R.id.imageProgressPercent);
        imageProgressStage = dialog.findViewById(R.id.imageProgressStage);
        imageProgressCancelButton = dialog.findViewById(R.id.cancelImageProgressButton);
        imageProgressCancelButton.setOnClickListener(v -> cancelImageSend(operation));
        setImageProgressValues(0, uiText(R.string.image_progress_starting));
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setStatusBarColor(getColor(R.color.surface));
            dialog.getWindow().setNavigationBarColor(getColor(R.color.surface));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void updateImageProgress(ImageSendOperation operation, int progress,
                                     int stageString) {
        if (operation.cancelled) return;
        if (!operation.writingToConnection) {
            operation.lastReceiverProgress = reportReceiverMediaProgress(
                    operation.socket, PAYLOAD_IMAGE, progress,
                    operation.lastReceiverProgress);
        }
        runOnUiThread(() -> {
            if (!stopped && currentImageOperation == operation && !operation.cancelled) {
                setImageProgressValues(progress, uiText(stageString));
            }
        });
    }

    private void setImageProgressValues(int progress, CharSequence stage) {
        if (imageProgressBar == null || imageProgressPercent == null ||
                imageProgressStage == null) return;
        int safeProgress = Math.max(0, Math.min(100, progress));
        imageProgressBar.setProgress(safeProgress);
        imageProgressPercent.setText(uiText(R.string.image_progress_percent, safeProgress));
        imageProgressStage.setText(LocalizedText.refresh(this, stage));
    }

    private void completeImageProgress(ImageSendOperation operation) {
        if (currentImageOperation != operation || operation.cancelled) return;
        setImageProgressValues(100, uiText(R.string.image_progress_complete));
        if (imageProgressCancelButton != null) {
            imageProgressCancelButton.setEnabled(false);
        }
        mainHandler.postDelayed(() -> {
            if (currentImageOperation == operation) {
                currentImageOperation = null;
                dismissImageProgress();
            }
        }, 450L);
    }

    private void cancelImageSend(ImageSendOperation operation) {
        if (currentImageOperation != operation) return;
        operation.cancelled = true;
        Arrays.fill(operation.password, '\0');
        if (operation.writingToConnection) {
            // Um pacote parcialmente escrito nao pode ser reutilizado com seguranca.
            // Encerra a conexao para que o destinatario descarte o quadro incompleto.
            finishSocket(operation.socket, uiText(R.string.status_connection_closed));
        }
        currentImageOperation = null;
        sendingImage = false;
        dismissImageProgress();
        updateActionAvailability();
    }

    private void dismissImageProgress() {
        if (imageProgressDialog != null) {
            imageProgressDialog.dismiss();
        }
        imageProgressDialog = null;
        imageProgressBar = null;
        imageProgressPercent = null;
        imageProgressStage = null;
        imageProgressCancelButton = null;
    }

    private static void checkImageSendCancelled(ImageSendOperation operation)
            throws ImageSendCancelledException {
        if (operation.cancelled || Thread.currentThread().isInterrupted()) {
            throw new ImageSendCancelledException();
        }
    }

    private void writeEncryptedImagePacket(ImageSendOperation operation, byte[] encrypted)
            throws IOException {
        synchronized (socketLock) {
            if (activeSocket != operation.socket || stopped) {
                throw new IOException("Conexão encerrada antes do envio.");
            }
        }
        DataOutputStream output = new DataOutputStream(operation.socket.getOutputStream());
        checkImageSendCancelled(operation);
        output.writeInt(CONTROL_MEDIA_PACKET);
        output.writeByte(PAYLOAD_IMAGE);
        output.writeInt(encrypted.length);
        int offset = 0;
        int lastProgress = 90;
        final int chunkSize = 32 * 1024;
        while (offset < encrypted.length) {
            checkImageSendCancelled(operation);
            int count = Math.min(chunkSize, encrypted.length - offset);
            output.write(encrypted, offset, count);
            offset += count;
            int progress = 90 + Math.min(9,
                    (int) ((long) offset * 9L / Math.max(1, encrypted.length)));
            if (progress > lastProgress) {
                lastProgress = progress;
                updateImageProgress(operation, progress, R.string.image_progress_sending);
            }
        }
        output.flush();
    }

    /**
     * Decodifica e recria a imagem. Como nenhum bloco do arquivo original e copiado,
     * EXIF, GPS, perfil, miniatura e demais metadados nao entram no JPEG enviado.
     */
    private PreparedImage prepareImageForSending(Uri uri, ImageSendOperation operation)
            throws IOException {
        updateImageProgress(operation, 3, R.string.image_progress_validating);
        checkImageSendCancelled(operation);
        String mimeType = getContentResolver().getType(uri);
        if (mimeType != null && !mimeType.startsWith("image/")) {
            throw new InvalidImageException();
        }

        long declaredSize = queryImageSize(uri);
        if (declaredSize > MAX_SOURCE_IMAGE_BYTES) throw new ImageTooLargeException();

        updateImageProgress(operation, 10, R.string.image_progress_reading);
        checkImageSendCancelled(operation);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new InvalidImageException();
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                (long) bounds.outWidth * bounds.outHeight > MAX_IMAGE_PIXELS) {
            throw new InvalidImageException();
        }

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_SEND_IMAGE_DIMENSION * 2 ||
                bounds.outHeight / sampleSize > MAX_SEND_IMAGE_DIMENSION * 2) {
            sampleSize <<= 1;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        updateImageProgress(operation, 24, R.string.image_progress_decoding);
        checkImageSendCancelled(operation);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new InvalidImageException();
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        if (decoded == null) throw new InvalidImageException();

        Bitmap working = decoded;
        try {
            updateImageProgress(operation, 40, R.string.image_progress_orientation);
            checkImageSendCancelled(operation);
            int orientation = readExifOrientation(uri);
            Bitmap oriented = applyExifOrientation(working, orientation);
            if (oriented != working) {
                working.recycle();
                working = oriented;
            }

            updateImageProgress(operation, 52, R.string.image_progress_resizing);
            checkImageSendCancelled(operation);
            Bitmap scaled = scaleBitmapToFit(working, MAX_SEND_IMAGE_DIMENSION);
            if (scaled != working) {
                working.recycle();
                working = scaled;
            }

            updateImageProgress(operation, 62, R.string.image_progress_metadata);
            checkImageSendCancelled(operation);
            Bitmap flattened = flattenOnWhite(working);
            if (flattened != working) {
                working.recycle();
                working = flattened;
            }

            updateImageProgress(operation, 70, R.string.image_progress_compressing);
            checkImageSendCancelled(operation);
            return encodeSanitizedImage(working, declaredSize, operation);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            if (!working.isRecycled()) working.recycle();
            throw e;
        }
    }

    private long queryImageSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException ignored) { }
        return -1L;
    }

    private PreparedImage encodeSanitizedImage(Bitmap bitmap, long originalSize,
                                               ImageSendOperation operation)
            throws IOException {
        int[] qualities = new int[]{82, 74, 66, 58};
        byte[] best = null;
        for (int index = 0; index < qualities.length; index++) {
            checkImageSendCancelled(operation);
            int quality = qualities[index];
            updateImageProgress(operation, 70 + index * 2,
                    R.string.image_progress_compressing);
            ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw new InvalidImageException();
            }
            byte[] candidate = output.toByteArray();
            if (best != null) Arrays.fill(best, (byte) 0);
            best = candidate;
            boolean smallerThanOriginal = originalSize <= 0 || candidate.length < originalSize;
            if (candidate.length <= MAX_IMAGE_BYTES &&
                    (smallerThanOriginal || quality == qualities[qualities.length - 1])) {
                return new PreparedImage(candidate, bitmap);
            }
        }
        if (best != null) Arrays.fill(best, (byte) 0);
        throw new ImageTooLargeException();
    }

    private static Bitmap scaleBitmapToFit(Bitmap source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxDimension) return source;
        float ratio = maxDimension / (float) largest;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(width * ratio)),
                Math.max(1, Math.round(height * ratio)), true);
    }

    private static Bitmap flattenOnWhite(Bitmap source) {
        Bitmap flattened = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return flattened;
    }

    private int readExifOrientation(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null || readByte(input) != 0xff || readByte(input) != 0xd8) return 1;
            while (true) {
                int markerStart;
                do {
                    markerStart = readByte(input);
                } while (markerStart != -1 && markerStart != 0xff);
                if (markerStart == -1) return 1;

                int marker;
                do {
                    marker = readByte(input);
                } while (marker == 0xff);
                if (marker == -1 || marker == 0xd9 || marker == 0xda) return 1;
                int segmentLength = readUnsignedShort(input) - 2;
                if (segmentLength < 0) return 1;
                if (marker == 0xe1 && segmentLength <= 1024 * 1024) {
                    byte[] segment = new byte[segmentLength];
                    if (!readFully(input, segment)) return 1;
                    if (segment.length >= 6 && segment[0] == 'E' && segment[1] == 'x' &&
                            segment[2] == 'i' && segment[3] == 'f' &&
                            segment[4] == 0 && segment[5] == 0) {
                        return parseExifOrientation(segment);
                    }
                    continue;
                }
                if (!skipFully(input, segmentLength)) return 1;
            }
        } catch (IOException | RuntimeException ignored) {
            return 1;
        }
    }

    private static int parseExifOrientation(byte[] data) {
        if (data.length < 14 || data[0] != 'E' || data[1] != 'x' ||
                data[2] != 'i' || data[3] != 'f' || data[4] != 0 || data[5] != 0) {
            return 1;
        }
        int tiff = 6;
        boolean littleEndian;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }
        if (readUnsignedShort(data, tiff + 2, littleEndian) != 42) return 1;
        long ifdOffset = readUnsignedInt(data, tiff + 4, littleEndian);
        long ifdStartLong = tiff + ifdOffset;
        if (ifdStartLong < 0 || ifdStartLong > data.length - 2) return 1;
        int ifdStart = (int) ifdStartLong;
        int entries = readUnsignedShort(data, ifdStart, littleEndian);
        for (int i = 0; i < entries; i++) {
            long entryLong = ifdStart + 2L + i * 12L;
            if (entryLong < 0 || entryLong > data.length - 12) return 1;
            int entry = (int) entryLong;
            if (readUnsignedShort(data, entry, littleEndian) == 0x0112) {
                int type = readUnsignedShort(data, entry + 2, littleEndian);
                long count = readUnsignedInt(data, entry + 4, littleEndian);
                if (type != 3 || count < 1) return 1;
                int orientation = readUnsignedShort(data, entry + 8, littleEndian);
                return orientation >= 1 && orientation <= 8 ? orientation : 1;
            }
        }
        return 1;
    }

    private static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        if (orientation <= 1 || orientation > 8) return source;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2:
                matrix.setScale(-1f, 1f);
                break;
            case 3:
                matrix.setRotate(180f);
                break;
            case 4:
                matrix.setScale(1f, -1f);
                break;
            case 5:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case 6:
                matrix.setRotate(90f);
                break;
            case 7:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case 8:
                matrix.setRotate(-90f);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
    }

    private static int readByte(InputStream input) throws IOException {
        return input.read();
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        if ((high | low) < 0) throw new IOException("Fim inesperado da imagem.");
        return (high << 8) | low;
    }

    private static boolean readFully(InputStream input, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int read = input.read(data, offset, data.length - offset);
            if (read < 0) return false;
            offset += read;
        }
        return true;
    }

    private static boolean skipFully(InputStream input, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= (int) skipped;
            } else if (input.read() == -1) {
                return false;
            } else {
                remaining--;
            }
        }
        return true;
    }

    private static int readUnsignedShort(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset > data.length - 2) return -1;
        int first = data[offset] & 0xff;
        int second = data[offset + 1] & 0xff;
        return littleEndian ? first | (second << 8) : (first << 8) | second;
    }

    private static long readUnsignedInt(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset > data.length - 4) return -1L;
        long first = data[offset] & 0xffL;
        long second = data[offset + 1] & 0xffL;
        long third = data[offset + 2] & 0xffL;
        long fourth = data[offset + 3] & 0xffL;
        return littleEndian
                ? first | (second << 8) | (third << 16) | (fourth << 24)
                : (first << 24) | (second << 16) | (third << 8) | fourth;
    }

    private int reportReceiverMediaProgress(BluetoothSocket socket, byte payloadType,
                                            int progress, int lastProgress) {
        int target = Math.min(90, Math.max(0, progress));
        if (target <= lastProgress) return lastProgress;
        synchronized (socketLock) {
            if (activeSocket != socket || stopped) return lastProgress;
        }
        int reported = lastProgress;
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            for (int next = lastProgress + 1; next <= target; next++) {
                output.writeInt(CONTROL_MEDIA_PROGRESS);
                output.writeByte(payloadType);
                output.writeInt(next);
                reported = next;
            }
            output.flush();
        } catch (IOException e) {
            finishSocket(socket, uiText(R.string.status_connection_lost));
        }
        return reported;
    }

    private void cancelReceiverMediaProgress(BluetoothSocket socket, byte payloadType) {
        synchronized (socketLock) {
            if (activeSocket != socket || stopped) return;
        }
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(CONTROL_MEDIA_CANCEL);
            output.writeByte(payloadType);
            output.flush();
        } catch (IOException e) {
            finishSocket(socket, uiText(R.string.status_connection_lost));
        }
    }

    private void writeEncryptedPacket(BluetoothSocket socket, byte[] encrypted)
            throws IOException {
        synchronized (socketLock) {
            if (activeSocket != socket || stopped) {
                throw new IOException("Conexão encerrada antes do envio.");
            }
        }
        // Uma unica fila preserva a ordem de textos e imagens.
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(encrypted.length);
        output.write(encrypted);
        output.flush();
    }

    private void restoreUnsentText(String text) {
        if (messageInput.getText().length() == 0) messageInput.setText(text);
    }

    /**
     * Gera bytes versionados contendo salt, nonce e ciphertext autenticado.
     * NAO recebe nem transmite texto puro pelo Bluetooth.
     */
    private static byte[] encryptContent(byte payloadType, byte[] content, char[] password)
            throws GeneralSecurityException {
        if (payloadType == PAYLOAD_TEXT && content.length > MAX_TEXT_BYTES) {
            throw new GeneralSecurityException("Texto excede o limite.");
        }
        if (payloadType == PAYLOAD_IMAGE && content.length > MAX_IMAGE_BYTES) {
            throw new GeneralSecurityException("Imagem excede o limite.");
        }
        if (payloadType == PAYLOAD_AUDIO && content.length > MAX_AUDIO_BYTES) {
            throw new GeneralSecurityException("Áudio excede o limite.");
        }
        if (payloadType == PAYLOAD_MP3 && content.length > MAX_AUDIO_BYTES) {
            throw new GeneralSecurityException("MP3 excede o limite.");
        }
        if (payloadType == PAYLOAD_STICKER && (content.length == 0 || content.length > StickerCodec.MAX_BYTES)) {
            throw new GeneralSecurityException("Figurinha excede o limite.");
        }
        if (payloadType != PAYLOAD_TEXT && payloadType != PAYLOAD_IMAGE &&
                payloadType != PAYLOAD_AUDIO && payloadType != PAYLOAD_MP3 && payloadType != PAYLOAD_STICKER) {
            throw new GeneralSecurityException("Tipo de conteúdo inválido.");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);

        SecretKeySpec key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(AUTH_DATA);

        int encryptedCapacity = cipher.getOutputSize(1 + content.length);
        byte[] packet = new byte[ENCRYPTION_HEADER_BYTES + encryptedCapacity];
        packet[0] = PACKET_VERSION;
        System.arraycopy(salt, 0, packet, 1, SALT_BYTES);
        System.arraycopy(nonce, 0, packet, 1 + SALT_BYTES, NONCE_BYTES);

        byte[] type = new byte[]{payloadType};
        int written = cipher.update(type, 0, 1, packet, ENCRYPTION_HEADER_BYTES);
        written += cipher.doFinal(content, 0, content.length, packet,
                ENCRYPTION_HEADER_BYTES + written);
        int finalSize = ENCRYPTION_HEADER_BYTES + written;
        if (finalSize == packet.length) return packet;

        byte[] resized = Arrays.copyOf(packet, finalSize);
        Arrays.fill(packet, (byte) 0);
        return resized;
    }

    private static DecodedMessage decryptReceivedMessage(byte[] packet, char[] password)
            throws GeneralSecurityException, IOException {
        if (packet.length < MIN_PACKET_BYTES || packet.length > MAX_PACKET_BYTES) {
            throw new GeneralSecurityException("Tamanho inválido.");
        }
        if (packet[0] != PACKET_VERSION) {
            throw new GeneralSecurityException("Versão de criptografia incompatível.");
        }

        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(packet, 1, salt, 0, SALT_BYTES);
        System.arraycopy(packet, 1 + SALT_BYTES, nonce, 0, NONCE_BYTES);
        SecretKeySpec key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(AUTH_DATA);
        byte[] plainBytes = cipher.doFinal(packet, ENCRYPTION_HEADER_BYTES,
                packet.length - ENCRYPTION_HEADER_BYTES);
        try {
            if (plainBytes.length == 0) {
                throw new GeneralSecurityException("Conteúdo vazio.");
            }
            byte payloadType = plainBytes[0];
            int contentLength = plainBytes.length - 1;
            if (payloadType == PAYLOAD_TEXT) {
                if (contentLength > MAX_TEXT_BYTES) {
                    throw new GeneralSecurityException("Texto excede o limite.");
                }
                return DecodedMessage.text(new String(
                        plainBytes, 1, contentLength, StandardCharsets.UTF_8));
            }
            if (payloadType == PAYLOAD_IMAGE) {
                if (contentLength <= 0) throw new InvalidImageException();
                if (contentLength > MAX_IMAGE_BYTES) throw new ImageTooLargeException();
                return DecodedMessage.image(
                        decodeImagePreview(plainBytes, 1, contentLength));
            }
            if (payloadType == PAYLOAD_AUDIO) {
                if (contentLength <= 0 || contentLength > MAX_AUDIO_BYTES ||
                        !isM4aAudio(plainBytes, 1, contentLength)) {
                    throw new GeneralSecurityException("Áudio inválido.");
                }
                return DecodedMessage.audio(
                        Arrays.copyOfRange(plainBytes, 1, plainBytes.length), ".m4a");
            }
            if (payloadType == PAYLOAD_MP3) {
                if (contentLength <= 0 || contentLength > MAX_AUDIO_BYTES ||
                        !isMp3Audio(plainBytes, 1, contentLength)) {
                    throw new GeneralSecurityException("MP3 inválido.");
                }
                return DecodedMessage.audio(
                        Arrays.copyOfRange(plainBytes, 1, plainBytes.length), ".mp3");
            }

            if (payloadType == PAYLOAD_STICKER) {
                if (contentLength <= 0 || contentLength > StickerCodec.MAX_BYTES) {
                    throw new GeneralSecurityException("Figurinha inválida.");
                }
                return DecodedMessage.sticker(StickerCodec.decode(
                        Arrays.copyOfRange(plainBytes, 1, plainBytes.length)));
            }

            // Compatibilidade com mensagens de texto de versoes anteriores.
            if (plainBytes.length > MAX_TEXT_BYTES) {
                throw new GeneralSecurityException("Conteúdo incompatível.");
            }
            return DecodedMessage.text(new String(plainBytes, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    private static Bitmap decodeImagePreview(byte[] data, int offset, int length)
            throws InvalidImageException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, offset, length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new InvalidImageException();
        }
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (pixels > MAX_IMAGE_PIXELS) throw new InvalidImageException();

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > MAX_PREVIEW_DIMENSION ||
                bounds.outHeight / sampleSize > MAX_PREVIEW_DIMENSION) {
            sampleSize <<= 1;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, offset, length, options);
        if (bitmap == null) throw new InvalidImageException();
        return bitmap;
    }

    private static boolean isM4aAudio(byte[] data, int offset, int length) {
        return data != null && offset >= 0 && length >= 12 &&
                offset <= data.length - length &&
                data[offset + 4] == 'f' && data[offset + 5] == 't' &&
                data[offset + 6] == 'y' && data[offset + 7] == 'p';
    }

    private static boolean isMp3Audio(byte[] data, int offset, int length) {
        if (data == null || offset < 0 || length < 4 || offset > data.length - length) {
            return false;
        }
        int scanStart = offset;
        int end = offset + length;
        if (length >= 10 && data[offset] == 'I' && data[offset + 1] == 'D' &&
                data[offset + 2] == '3') {
            int tagSize = ((data[offset + 6] & 0x7f) << 21) |
                    ((data[offset + 7] & 0x7f) << 14) |
                    ((data[offset + 8] & 0x7f) << 7) |
                    (data[offset + 9] & 0x7f);
            long afterTag = offset + 10L + tagSize;
            if (afterTag > end - 4L) return false;
            scanStart = (int) afterTag;
        }
        int scanEnd = Math.min(end - 3, scanStart + 64 * 1024);
        for (int i = scanStart; i < scanEnd; i++) {
            int first = data[i] & 0xff;
            int second = data[i + 1] & 0xff;
            int third = data[i + 2] & 0xff;
            int version = (second >> 3) & 0x03;
            int layer = (second >> 1) & 0x03;
            int bitrate = (third >> 4) & 0x0f;
            int sampleRate = (third >> 2) & 0x03;
            if (first == 0xff && (second & 0xe0) == 0xe0 && version != 1 &&
                    layer != 0 && bitrate != 0 && bitrate != 0x0f && sampleRate != 3) {
                return true;
            }
        }
        return false;
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
        byte[] keyBytes = null;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private void updateIncomingMediaProgress(byte payloadType, int progress) {
        int displayedProgress = Math.max(0, Math.min(100, progress));
        runOnUiThread(() -> {
            if (stopped) return;
            if (!incomingMediaProgressActive || incomingMediaPayloadType != payloadType) {
                incomingMediaProgressActive = true;
                incomingMediaPayloadType = payloadType;
                incomingMediaProgressBar.setProgress(0);
                messageList.removeView(emptyStateView);
                messageList.addView(emptyStateView);
            }
            if (displayedProgress <= incomingMediaProgressBar.getProgress()) return;
            emptyStateLabel.setText(uiText(getIncomingMediaProgressLabel(payloadType)));
            incomingMediaProgressBar.setVisibility(View.VISIBLE);
            incomingMediaProgressPercent.setVisibility(View.VISIBLE);
            incomingMediaProgressBar.setProgress(displayedProgress);
            incomingMediaProgressPercent.setText(uiText(
                    R.string.image_progress_percent, displayedProgress));
            emptyStateView.setVisibility(View.VISIBLE);
            messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private int getIncomingMediaProgressLabel(byte payloadType) {
        if (payloadType == PAYLOAD_IMAGE) return R.string.receiving_image;
        if (payloadType == PAYLOAD_MP3) return R.string.receiving_mp3;
        if (payloadType == PAYLOAD_STICKER) return R.string.receiving_sticker;
        return R.string.receiving_audio;
    }

    private void cancelIncomingMediaProgress(byte payloadType) {
        runOnUiThread(() -> {
            if (incomingMediaProgressActive && incomingMediaPayloadType == payloadType) {
                finishIncomingMediaProgress();
            }
        });
    }

    private void finishIncomingMediaProgress() {
        incomingMediaProgressActive = false;
        incomingMediaPayloadType = 0;
        incomingMediaProgressBar.setProgress(0);
        incomingMediaProgressBar.setVisibility(View.GONE);
        incomingMediaProgressPercent.setVisibility(View.GONE);
        emptyStateLabel.setText(uiText(R.string.empty_conversation));
        messageList.removeView(emptyStateView);
        messageList.addView(emptyStateView, 0);
        emptyStateView.setVisibility(
                messageList.getChildCount() > 1 ? View.GONE : View.VISIBLE);
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void appendSentBubble(String text) {
        LinearLayout bubble = createBubble(true, false);
        addMessageHeader(bubble, uiText(R.string.message_sender_me), true, false);
        TextView view = new TextView(this);
        view.setText(text);
        styleMessageText(view, true, false);
        bubble.addView(view);
        addBubble(bubble);
    }

    private void appendSentImageBubble(Bitmap bitmap) {
        LinearLayout bubble = createBubble(true, false);
        addMessageHeader(bubble, uiText(R.string.message_sender_me), true, false);
        bubble.addView(createMessageImageView(bitmap));
        addBubble(bubble);
    }

    private View createStickerMessageView(Sticker sticker) {
        StickerView view = new StickerView(this, sticker);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(200), dp(200)));
        view.setContentDescription(uiText(sticker.isAnimated()
                ? R.string.open_animated_sticker : R.string.open_static_sticker));
        view.setOnClickListener(v -> stickerController.showPreview(sticker, null));
        return view;
    }

    private void appendSentStickerBubble(Sticker sticker) {
        LinearLayout bubble = createBubble(true, false);
        addMessageHeader(bubble, uiText(R.string.message_sender_me), true, false);
        bubble.addView(createStickerMessageView(sticker));
        addBubble(bubble);
    }

    private void appendSentAudioBubble(File file) {
        LinearLayout bubble = createBubble(true, false);
        addMessageHeader(bubble, uiText(R.string.message_sender_me), true, false);
        bubble.addView(createAudioPlayerView(file));
        addBubble(bubble);
    }

    private void appendReceivedBubble(byte[] packet) {
        appendReceivedBubble(packet, false);
    }

    private void appendReceivedBubble(byte[] packet, boolean receivedMediaTransfer) {
        LinearLayout bubble = createBubble(false, true);
        TextView header = addMessageHeader(
                bubble, uiText(R.string.message_sender_contact), false, true);
        TextView view = new TextView(this);
        view.setText(uiText(R.string.message_encrypted));
        view.setTextIsSelectable(false);
        styleMessageText(view, false, true);
        bubble.addView(view);

        ImageView imageView = createMessageImageView(null);
        imageView.setVisibility(View.GONE);
        bubble.addView(imageView);

        LinearLayout audioContainer = new LinearLayout(this);
        audioContainer.setOrientation(LinearLayout.VERTICAL);
        audioContainer.setVisibility(View.GONE);
        bubble.addView(audioContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        PendingMessage pending = new PendingMessage(
                packet, bubble, header, view, imageView, audioContainer,
                receivedMediaTransfer);
        if (receivedMediaTransfer) bubble.setVisibility(View.GONE);
        pendingMessages.add(pending);
        addBubble(bubble);
        tryDecryptPendingMessage(pending);
    }

    private void retryPendingMessages() {
        if (stopped) return;
        for (PendingMessage pending : new ArrayList<>(pendingMessages)) {
            tryDecryptPendingMessage(pending);
        }
    }

    private void tryDecryptPendingMessage(PendingMessage pending) {
        if (stopped || pending.decrypting || !pendingMessages.contains(pending)) return;
        final char[] password = getActivePassword();
        if (password.length < MIN_PASSWORD_CHARS) {
            Arrays.fill(password, '\0');
            return;
        }

        pending.decrypting = true;
        final int attemptedGeneration = passwordGeneration;
        decryptQueue.execute(() -> {
            try {
                final DecodedMessage decoded =
                        decryptReceivedMessage(pending.packet, password);
                File receivedAudio = null;
                if (decoded.audio != null) {
                    try {
                        receivedAudio = writeReceivedAudioFile(
                                decoded.audio, decoded.audioExtension);
                    } finally {
                        Arrays.fill(decoded.audio, (byte) 0);
                    }
                }
                final File audioFile = receivedAudio;
                runOnUiThread(() -> {
                    if (stopped || !pendingMessages.remove(pending)) {
                        if (decoded.image != null) decoded.image.recycle();
                        deleteQuietly(audioFile);
                        if (pending.packet != null) {
                            Arrays.fill(pending.packet, (byte) 0);
                            pending.packet = null;
                        }
                        return;
                    }
                    if (decoded.image != null) {
                        pending.messageView.setVisibility(View.GONE);
                        pending.imageView.setImageBitmap(decoded.image);
                        pending.imageView.setVisibility(View.VISIBLE);
                        pending.imageView.setContentDescription(
                                uiText(R.string.open_received_image));
                        pending.imageView.setOnClickListener(v -> showImageViewer(
                                decoded.image, pending.bubble, pending.imageView));
                    } else if (decoded.sticker != null) {
                        pending.messageView.setVisibility(View.GONE);
                        pending.audioContainer.addView(createStickerMessageView(decoded.sticker));
                        pending.audioContainer.setVisibility(View.VISIBLE);
                    } else if (audioFile != null) {
                        pending.messageView.setVisibility(View.GONE);
                        temporaryAudioFiles.add(audioFile);
                        pending.audioContainer.addView(createAudioPlayerView(audioFile));
                        pending.audioContainer.setVisibility(View.VISIBLE);
                    } else {
                        pending.messageView.setText(decoded.text);
                        pending.messageView.setTextIsSelectable(true);
                    }
                    applyBubbleBackground(pending.bubble, false, false);
                    pending.headerView.setTextColor(getColor(R.color.text_secondary));
                    styleMessageText(pending.messageView, false, false);
                    pending.bubble.setVisibility(View.VISIBLE);
                    if (pending.receivedMediaTransfer) {
                        finishIncomingMediaProgress();
                    }
                    Arrays.fill(pending.packet, (byte) 0);
                    pending.packet = null;
                });
            } catch (GeneralSecurityException | IOException | OutOfMemoryError e) {
                handlePendingDecryptionFailure(pending, attemptedGeneration);
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void handlePendingDecryptionFailure(PendingMessage pending,
                                                int attemptedGeneration) {
        runOnUiThread(() -> {
            if (stopped || !pendingMessages.contains(pending)) {
                if (pending.packet != null) {
                    Arrays.fill(pending.packet, (byte) 0);
                    pending.packet = null;
                }
                return;
            }
            pending.decrypting = false;
            if (pending.receivedMediaTransfer) {
                pending.bubble.setVisibility(View.VISIBLE);
                finishIncomingMediaProgress();
            }
            if (attemptedGeneration != passwordGeneration && hasUsablePassword()) {
                tryDecryptPendingMessage(pending);
            }
        });
    }

    private File writeReceivedAudioFile(byte[] audio, String extension) throws IOException {
        boolean valid = ".mp3".equals(extension)
                ? isMp3Audio(audio, 0, audio.length)
                : ".m4a".equals(extension) && isM4aAudio(audio, 0, audio.length);
        if (!valid || audio.length > MAX_AUDIO_BYTES) {
            throw new IOException("Áudio recebido inválido.");
        }
        return writeTemporaryAudioFile(audio, "chat_audio_received_", extension);
    }

    private File writeTemporaryAudioFile(byte[] audio, String prefix, String extension)
            throws IOException {
        File file = File.createTempFile(prefix, extension, getCacheDir());
        boolean written = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(audio);
            output.flush();
            written = true;
            return file;
        } finally {
            if (!written) deleteQuietly(file);
        }
    }

    private View createAudioPlayerView(File file) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setMinimumWidth(dp(224));
        container.setContentDescription(uiText(R.string.conversation_audio));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button playButton = new Button(this);
        playButton.setText(uiText(R.string.play_audio));
        playButton.setAllCaps(false);
        controls.addView(playButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));

        TextView time = new TextView(this);
        time.setText(uiText(R.string.audio_time_initial));
        time.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        timeParams.setMargins(dp(10), 0, 0, 0);
        controls.addView(time, timeParams);
        container.addView(controls);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(1);
        seekBar.setProgress(0);
        container.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AudioPlayback playback = new AudioPlayback(file, playButton, seekBar, time);
        audioPlaybacks.add(playback);
        return container;
    }

    private final class AudioPlayback {
        private final File file;
        private final Button playButton;
        private final SeekBar seekBar;
        private final TextView timeView;
        private MediaPlayer player;
        private final Runnable progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                try {
                    int position = player.getCurrentPosition();
                    int duration = player.getDuration();
                    seekBar.setMax(Math.max(1, duration));
                    seekBar.setProgress(Math.min(position, duration));
                    timeView.setText(uiText(R.string.audio_time,
                            formatAudioTime(position), formatAudioTime(duration)));
                    if (player.isPlaying()) mainHandler.postDelayed(this, 250L);
                } catch (IllegalStateException ignored) { }
            }
        };

        private AudioPlayback(File file, Button playButton,
                              SeekBar seekBar, TextView timeView) {
            this.file = file;
            this.playButton = playButton;
            this.seekBar = seekBar;
            this.timeView = timeView;
            playButton.setOnClickListener(v -> toggle());
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                        boolean fromUser) {
                    if (fromUser && player != null) {
                        try {
                            player.seekTo(progress);
                        } catch (IllegalStateException ignored) { }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
        }

        private void toggle() {
            try {
                if (player == null) {
                    player = new MediaPlayer();
                    player.setDataSource(file.getAbsolutePath());
                    player.prepare();
                    seekBar.setMax(Math.max(1, player.getDuration()));
                    player.setOnCompletionListener(completed -> {
                        playButton.setText(uiText(R.string.play_audio));
                        seekBar.setProgress(0);
                        timeView.setText(uiText(R.string.audio_time,
                                formatAudioTime(0), formatAudioTime(completed.getDuration())));
                    });
                }
                if (player.isPlaying()) {
                    player.pause();
                    playButton.setText(uiText(R.string.play_audio));
                } else {
                    for (AudioPlayback other : new ArrayList<>(audioPlaybacks)) {
                        if (other != this) other.pause();
                    }
                    player.start();
                    playButton.setText(uiText(R.string.pause_audio));
                    mainHandler.removeCallbacks(progressRunnable);
                    mainHandler.post(progressRunnable);
                }
            } catch (IOException | RuntimeException e) {
                release();
                toast(uiText(R.string.audio_playback_failed));
            }
        }

        private void pause() {
            if (player == null) return;
            try {
                if (player.isPlaying()) player.pause();
                playButton.setText(uiText(R.string.play_audio));
            } catch (IllegalStateException ignored) { }
            mainHandler.removeCallbacks(progressRunnable);
        }

        private void release() {
            mainHandler.removeCallbacks(progressRunnable);
            if (player != null) {
                try {
                    player.release();
                } catch (RuntimeException ignored) { }
                player = null;
            }
        }
    }

    private ImageView createMessageImageView(Bitmap bitmap) {
        ImageView imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setMaxHeight(dp(360));
        imageView.setContentDescription(uiText(R.string.conversation_image));
        imageView.setImageBitmap(bitmap);
        imageView.setClickable(false);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return imageView;
    }

    private void showImageViewer(Bitmap bitmap, LinearLayout bubble, ImageView thumbnail) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Dialog dialog = new Dialog(this, R.style.ImageViewerTheme);
        dialog.setContentView(R.layout.dialog_image_viewer);
        applySystemBarInsets(dialog.getWindow());
        ZoomImageView fullImage = dialog.findViewById(R.id.fullscreenImage);
        Button closeButton = dialog.findViewById(R.id.closeImageButton);
        Button saveButton = dialog.findViewById(R.id.saveImageButton);
        Button deleteButton = dialog.findViewById(R.id.deleteImageButton);
        fullImage.setImageBitmap(bitmap);
        closeButton.setOnClickListener(v -> dialog.dismiss());
        saveButton.setOnClickListener(v -> requestSaveImage(bitmap));
        deleteButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.delete_image_title)
                .setMessage(R.string.delete_image_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_image, (confirmDialog, which) -> {
                    dialog.dismiss();
                    thumbnail.setOnClickListener(null);
                    thumbnail.setImageDrawable(null);
                    removeMessage(bubble);
                    updateActionAvailability();
                })
                .show());

        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(getColor(R.color.app_background)));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void requestSaveImage(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            toast(uiText(R.string.image_save_failed));
            return;
        }
        pendingSaveBitmap = bitmap;
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_TITLE, "chat-de-rua-" + timestamp + ".jpg");
        try {
            startActivityForResult(intent, REQUEST_SAVE_IMAGE);
        } catch (RuntimeException e) {
            pendingSaveBitmap = null;
            toast(uiText(R.string.image_save_failed));
        }
    }

    private void saveImage(Bitmap bitmap, Uri destination) {
        mediaQueue.execute(() -> {
            boolean saved = false;
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output != null && !bitmap.isRecycled()) {
                    saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output);
                    output.flush();
                }
            } catch (IOException | RuntimeException ignored) { }
            final boolean result = saved;
            runOnUiThread(() -> {
                if (!stopped) toast(uiText(result
                        ? R.string.image_saved
                        : R.string.image_save_failed));
            });
        });
    }

    private LinearLayout createBubble(boolean mine, boolean locked) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(9), dp(14), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        params.setMarginStart(mine ? dp(36) : 0);
        params.setMarginEnd(mine ? 0 : dp(36));
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        bubble.setLayoutParams(params);
        applyBubbleBackground(bubble, mine, locked);
        return bubble;
    }

    private void applyBubbleBackground(LinearLayout bubble, boolean mine, boolean locked) {
        GradientDrawable background = new GradientDrawable();
        if (bubble.isSelected()) {
            background.setColor(getColor(R.color.message_selected));
            background.setStroke(dp(2), getColor(R.color.primary));
        } else if (locked) {
            background.setColor(getColor(R.color.message_locked));
            background.setStroke(dp(1), getColor(R.color.message_locked_border));
        } else if (mine) {
            background.setColor(getColor(R.color.message_mine));
        } else {
            background.setColor(getColor(R.color.message_contact));
            background.setStroke(dp(1), getColor(R.color.border));
        }
        float rounded = dp(16);
        boolean smallCornerOnRight = mine !=
                (getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        float left = smallCornerOnRight ? rounded : dp(4);
        float right = smallCornerOnRight ? dp(4) : rounded;
        background.setCornerRadii(new float[]{left, left, right, right,
                rounded, rounded, rounded, rounded});
        bubble.setBackground(background);
    }

    private TextView addMessageHeader(LinearLayout bubble, CharSequence text,
                                      boolean mine, boolean locked) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(12f);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setTextColor(getColor(locked ? R.color.message_locked_text :
                mine ? R.color.message_mine_text : R.color.text_secondary));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(3));
        bubble.addView(header, params);
        addMessageSelection(bubble, header, mine);
        return header;
    }

    private void styleMessageText(TextView view, boolean mine, boolean locked) {
        view.setTextSize(16f);
        view.setLineSpacing(0f, 1.08f);
        view.setSingleLine(false);
        view.setMaxLines(Integer.MAX_VALUE);
        view.setHorizontallyScrolling(false);
        view.setEllipsize(null);
        view.setTextColor(getColor(locked ? R.color.message_locked_text :
                mine ? R.color.message_mine_text : R.color.message_contact_text));
        view.setTypeface(Typeface.DEFAULT, locked ? Typeface.ITALIC : Typeface.NORMAL);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addBubble(LinearLayout bubble) {
        messageList.addView(bubble);
        if (incomingMediaProgressActive) {
            emptyStateView.setVisibility(View.VISIBLE);
            messageList.removeView(emptyStateView);
            messageList.addView(emptyStateView);
        } else {
            emptyStateView.setVisibility(View.GONE);
        }
        updateActionAvailability();
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean hasConversationContent() {
        for (int index = 0; index < messageList.getChildCount(); index++) {
            if (messageList.getChildAt(index) != emptyStateView) return true;
        }
        return false;
    }

    private void addMessageSelection(LinearLayout bubble, TextView header, boolean mine) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bubble.removeView(header);
        row.addView(header, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        CheckBox selection = new CheckBox(this);
        selection.setContentDescription(uiText(mine
                ? R.string.select_sent_message : R.string.select_received_message));
        selection.setMinWidth(dp(48));
        selection.setMinHeight(dp(48));
        // Selection has its own control so text copying, playback and previews keep working.
        selection.setSaveEnabled(false);
        row.addView(selection, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bubble.addView(row, 0);
        messageSelections.put(bubble, selection);
        selection.setOnCheckedChangeListener((button, checked) -> {
            bubble.setSelected(checked);
            boolean locked = false;
            for (PendingMessage pending : pendingMessages) {
                if (pending.bubble == bubble) { locked = true; break; }
            }
            applyBubbleBackground(bubble, mine, locked);
            updateMessageSelectionBar();
        });
    }

    private List<LinearLayout> getSelectedMessages() {
        List<LinearLayout> selected = new ArrayList<>();
        for (LinearLayout bubble : messageSelections.keySet()) {
            if (bubble.isSelected()) selected.add(bubble);
        }
        return selected;
    }

    private void updateMessageSelectionBar() {
        int count = getSelectedMessages().size();
        selectedMessageCount.setText(LocalizedText.plural(this, 
                R.plurals.selected_message_count, count, count));
        messageSelectionBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    private void clearMessageSelection() {
        for (CheckBox selection : messageSelections.values()) selection.setChecked(false);
        updateMessageSelectionBar();
    }

    private void confirmDeleteSelectedMessages() {
        final List<LinearLayout> selected = getSelectedMessages();
        if (selected.isEmpty() || deleteSelectedMessagesDialog != null) return;
        deleteSelectedMessagesDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.delete_selected_messages)
                .setMessage(LocalizedText.plural(this, 
                        R.plurals.delete_selected_messages_confirmation, selected.size(), selected.size()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_selected_messages, (dialog, which) -> {
                    for (LinearLayout bubble : selected) removeMessage(bubble);
                    updateActionAvailability();
                })
                .create();
        deleteSelectedMessagesDialog.setOnDismissListener(
                dialog -> deleteSelectedMessagesDialog = null);
        deleteSelectedMessagesDialog.show();
    }

    private void removeMessage(LinearLayout bubble) {
        if (messageSelections.remove(bubble) == null) return;
        for (PendingMessage pending : new ArrayList<>(pendingMessages)) {
            if (pending.bubble != bubble) continue;
            pendingMessages.remove(pending);
            // The worker owns the packet until its callback; removing it prevents redisplay.
            if (!pending.decrypting && pending.packet != null) {
                Arrays.fill(pending.packet, (byte) 0);
                pending.packet = null;
            }
            if (pending.receivedMediaTransfer && incomingMediaProgressActive) {
                finishIncomingMediaProgress();
            }
        }
        for (AudioPlayback playback : new ArrayList<>(audioPlaybacks)) {
            View view = playback.playButton;
            while (view != null && view != bubble) {
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
            if (view != bubble) continue;
            playback.release();
            audioPlaybacks.remove(playback);
            temporaryAudioFiles.remove(playback.file);
            deleteQuietly(playback.file);
        }
        messageList.removeView(bubble);
        bubble.removeAllViews();
        emptyStateView.setVisibility(incomingMediaProgressActive || !hasConversationContent()
                ? View.VISIBLE : View.GONE);
        updateMessageSelectionBar();
    }

    private void confirmClearChat() {
        if (!hasConversationContent()) return;
        if (clearChatDialog != null && clearChatDialog.isShowing()) return;
        clearChatDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.clear_chat_title)
                .setMessage(R.string.clear_chat_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_chat_confirm,
                        (dialog, which) -> clearChat())
                .create();
        clearChatDialog.setOnDismissListener(dialog -> clearChatDialog = null);
        clearChatDialog.show();
    }

    private void clearChat() {
        if (deleteSelectedMessagesDialog != null) deleteSelectedMessagesDialog.dismiss();
        messageSelections.clear();
        updateMessageSelectionBar();
        for (AudioPlayback playback : new ArrayList<>(audioPlaybacks)) {
            playback.release();
        }
        audioPlaybacks.clear();
        for (File file : new ArrayList<>(temporaryAudioFiles)) deleteQuietly(file);
        temporaryAudioFiles.clear();

        for (PendingMessage pending : new ArrayList<>(pendingMessages)) {
            pendingMessages.remove(pending);
            if (!pending.decrypting && pending.packet != null) {
                Arrays.fill(pending.packet, (byte) 0);
                pending.packet = null;
            }
        }
        if (incomingMediaProgressActive) finishIncomingMediaProgress();

        messageList.removeAllViews();
        messageList.addView(emptyStateView);
        emptyStateLabel.setText(uiText(R.string.empty_conversation));
        incomingMediaProgressBar.setProgress(0);
        incomingMediaProgressBar.setVisibility(View.GONE);
        incomingMediaProgressPercent.setVisibility(View.GONE);
        emptyStateView.setVisibility(View.VISIBLE);

        synchronized (socketLock) {
            if (activeSocket == null) {
                chatRetainedWithoutConnection = false;
                chatConnectionStatusView.setVisibility(View.GONE);
            }
        }
        updateActionAvailability();
        toast(uiText(R.string.chat_cleared));
    }

    private void disconnect() {
        BluetoothSocket socket;
        synchronized (socketLock) { socket = activeSocket; }
        if (socket != null) finishSocket(socket, uiText(R.string.status_disconnected));
    }

    private void finishSocket(BluetoothSocket socket, CharSequence status) {
        boolean wasActive;
        synchronized (socketLock) {
            wasActive = (activeSocket == socket);
            if (wasActive) activeSocket = null;
        }
        closeQuietly(socket);
        if (wasActive && !stopped) {
            runOnUiThread(() -> {
                if (incomingMediaProgressActive) finishIncomingMediaProgress();
                retainChatWithoutConnection(status);
                ensureConnectionServerListening();
                scheduleAutomaticConversationStart();
            });
        }
    }

    private void retainChatWithoutConnection(CharSequence status) {
        chatRetainedWithoutConnection = hasConversationContent();
        chatConnectionStatusView.setText(LocalizedText.refresh(this, status));
        chatConnectionStatusView.setVisibility(
                chatRetainedWithoutConnection ? View.VISIBLE : View.GONE);
        updateConnectionUi(status);
    }

    private void updateConnectionUi(CharSequence status) {
        statusView.setText(LocalizedText.refresh(this, status));
        updateActionAvailability();
    }

    private void updateActionAvailability() {
        boolean connected;
        synchronized (socketLock) { connected = activeSocket != null; }
        boolean chatHasContent = hasConversationContent();
        if (!connected && !chatHasContent) chatRetainedWithoutConnection = false;
        boolean connectionReady = false;
        try {
            connectionReady = adapter != null && hasConnectPermission() &&
                    hasScanPermission() && hasAdvertisePermission() && adapter.isEnabled();
        } catch (SecurityException ignored) { }

        boolean connectionActionAvailable = connectionReady && !connected && !connecting &&
                !hostingConversation && !waitingForDiscoverableName &&
                !discoverabilityRequestLaunched && !discoveryInProgress;
        createConversationButton.setEnabled(connectionActionAvailable);
        connectButton.setEnabled(connectionActionAvailable);
        disconnectButton.setEnabled(connected);
        disconnectButton.setVisibility(connected ? View.VISIBLE : View.GONE);
        updateAutomaticConversationControls();
        clearChatButton.setEnabled(chatHasContent);
        clearChatButton.setAlpha(chatHasContent ? 1.0f : 0.5f);
        boolean chatAvailable = connected || chatRetainedWithoutConnection;
        openChatButton.setVisibility(chatAvailable ? View.VISIBLE : View.GONE);
        openChatButton.setEnabled(chatAvailable);
        if (!chatAvailable && chatScreen.getVisibility() == View.VISIBLE) {
            showHomeScreen();
        }
        boolean mediaActionAvailable = connected && !sendingImage && !sendingAudio &&
                !sendingMp3 && !recordingAudio && !sendingSticker &&
                (stickerController == null || !stickerController.isBusy());
        // Sem uma conversa, os botoes continuam clicaveis para explicar ao usuario
        // como habilitar o envio, mas conservam a aparencia de controles inativos.
        boolean messagingGuidanceAvailable = !connected &&
                !chatRetainedWithoutConnection;
        imageButton.setEnabled(mediaActionAvailable || messagingGuidanceAvailable);
        audioButton.setEnabled(mediaActionAvailable || messagingGuidanceAvailable);
        mp3Button.setEnabled(mediaActionAvailable || messagingGuidanceAvailable);
        imageButton.setAlpha(mediaActionAvailable ? 1.0f : 0.5f);
        audioButton.setAlpha(mediaActionAvailable ? 1.0f : 0.5f);
        mp3Button.setAlpha(mediaActionAvailable ? 1.0f : 0.5f);
        boolean stickerLibraryAvailable = !sendingImage && !sendingAudio && !sendingMp3 &&
                !recordingAudio && !sendingSticker && !stickerController.isBusy();
        stickerButton.setEnabled(stickerLibraryAvailable);
        stickerButton.setAlpha(stickerLibraryAvailable ? 1.0f : 0.5f);

        boolean sendActionAvailable = connected &&
                !messageInput.getText().toString().trim().isEmpty();
        sendButton.setEnabled(sendActionAvailable || messagingGuidanceAvailable);
        sendButton.setAlpha(sendActionAvailable ? 1.0f : 0.5f);
    }

    private boolean ensureConversationAvailableForMessaging() {
        synchronized (socketLock) {
            if (activeSocket != null) return true;
        }
        Toast.makeText(this, R.string.start_or_find_conversation_before_messaging,
                Toast.LENGTH_LONG).show();
        return false;
    }

    private void showChatScreen() {
        synchronized (socketLock) {
            if (activeSocket == null && !chatRetainedWithoutConnection) {
                showHomeScreen();
                return;
            }
        }
        homeScreen.setVisibility(View.GONE);
        chatScreen.setVisibility(View.VISIBLE);
        updateChatBackCallback();
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showHomeScreen() {
        chatScreen.setVisibility(View.GONE);
        homeScreen.setVisibility(View.VISIBLE);
        updateChatBackCallback();
    }

    private void updateChatBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean chatVisible = chatScreen.getVisibility() == View.VISIBLE;
        if (chatVisible == chatBackCallbackRegistered) return;
        if (chatVisible) {
            if (chatBackCallback == null) chatBackCallback = this::showHomeScreen;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, chatBackCallback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(chatBackCallback);
        }
        chatBackCallbackRegistered = chatVisible;
    }

    private static void applySystemBarInsets(Window window) {
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        ViewGroup content = window.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View root = content.getChildAt(0);
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        // API 35+ draws behind system bars; also keep input above the keyboard.
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets safeArea = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(left + safeArea.left, top + safeArea.top,
                    right + safeArea.right, bottom + safeArea.bottom);
            return WindowInsets.CONSUMED;
        });
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightBars, lightBars);
        }
        root.requestApplyInsets();
    }

    // Legacy back handling for API 24-32; API 33+ uses updateChatBackCallback().
    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (chatScreen != null && chatScreen.getVisibility() == View.VISIBLE) {
            showHomeScreen();
            return;
        }
        super.onBackPressed();
    }

    private void showStatus(CharSequence status) {
        runOnUiThread(() -> {
            if (!stopped) updateConnectionUi(status);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private CharSequence getDeviceLabel(BluetoothDevice device) {
        if (device == null) return uiText(R.string.nearby_device_fallback);
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
            String address = device.getAddress();
            if (address != null && address.length() >= 5) {
                return uiText(R.string.nearby_device_with_suffix,
                        address.substring(address.length() - 5));
            }
        } catch (SecurityException ignored) { }
        return uiText(R.string.nearby_device_fallback);
    }

    @SuppressWarnings("deprecation")
    private void vibrateConnectionEvent(int event) {
        if (!activityVisible) return;
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern;
        if (event == HAPTIC_SUCCESS) {
            pattern = new long[]{0L, 90L};
        } else if (event == HAPTIC_FAILURE) {
            pattern = new long[]{0L, 90L, 80L, 150L};
        } else {
            pattern = new long[]{0L, 55L, 70L, 55L};
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (SecurityException ignored) { }
    }

    private static String formatAudioTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void cleanupStaleAudioFiles() {
        File[] files = getCacheDir().listFiles((dir, name) ->
                name.startsWith("chat_audio_recording_") ||
                        name.startsWith("chat_audio_received_") ||
                        name.startsWith("chat_audio_mp3_"));
        if (files == null) return;
        for (File file : files) deleteQuietly(file);
    }

    private static void deleteQuietly(File file) {
        if (file != null) {
            try {
                if (file.exists()) file.delete();
            } catch (SecurityException ignored) { }
        }
    }

    private void toast(CharSequence message) {
        Toast.makeText(this, LocalizedText.refresh(this, message), Toast.LENGTH_SHORT).show();
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private void closeQuietly(BluetoothServerSocket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        activityVisible = false;
        if (languageDialog != null) languageDialog.dismiss();
        languageDialog = null;
        if (stickerController != null) stickerController.close();
        if (currentStickerOperation != null) {
            currentStickerOperation.cancelled = true;
            if (currentStickerOperation.writingToConnection) closeQuietly(currentStickerOperation.socket);
        }
        if (stickerSendDialog != null) stickerSendDialog.dismiss();
        stickerSendDialog = null;
        cancelAutomaticConversationCountdown();
        cancelDeviceDiscovery(false);
        connectionCancellationRequested = true;
        BluetoothSocket socketBeingConnected;
        Thread connectionWorker;
        synchronized (socketLock) {
            socketBeingConnected = connectingSocket;
            connectingSocket = null;
            pendingPairingDevice = null;
            connectionWorker = connectionThread;
            connectionThread = null;
        }
        closeQuietly(socketBeingConnected);
        if (connectionWorker != null) connectionWorker.interrupt();
        dismissConnectionProgressDialog();
        if (clearChatDialog != null) clearChatDialog.dismiss();
        clearChatDialog = null;
        if (deleteSelectedMessagesDialog != null) deleteSelectedMessagesDialog.dismiss();
        messageSelections.clear();
        if (discoveryResultDialog != null) discoveryResultDialog.dismiss();
        discoveryResultDialog = null;
        if (automaticConversationDecisionDialog != null) {
            automaticConversationDecisionDialog.dismiss();
        }
        automaticConversationDecisionDialog = null;
        hostingConversation = false;
        waitingForDiscoverableName = false;
        discoverabilityRequestLaunched = false;
        pendingDiscoverableBluetoothName = null;
        stopServer();
        restoreOriginalBluetoothName();
        stopped = true;
        activityVisible = false;
        unregisterBluetoothEventsReceiver();
        if (incomingConnectionDialog != null) {
            incomingConnectionDialog.dismiss();
            incomingConnectionDialog = null;
        }
        if (currentImageOperation != null) {
            currentImageOperation.cancelled = true;
            Arrays.fill(currentImageOperation.password, '\0');
            if (currentImageOperation.writingToConnection) {
                closeQuietly(currentImageOperation.socket);
            }
            currentImageOperation = null;
        }
        dismissImageProgress();
        if (currentAudioOperation != null) {
            currentAudioOperation.cancelled = true;
            Arrays.fill(currentAudioOperation.password, '\0');
            if (currentAudioOperation.writingToConnection) {
                closeQuietly(currentAudioOperation.socket);
            }
            deleteQuietly(currentAudioOperation.file);
            currentAudioOperation = null;
        }
        if (currentMp3Operation != null) {
            currentMp3Operation.cancelled = true;
            Arrays.fill(currentMp3Operation.password, '\0');
            if (currentMp3Operation.writingToConnection) {
                closeQuietly(currentMp3Operation.socket);
            }
            currentMp3Operation = null;
        }
        dismissAudioProgress();
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException ignored) { }
            try {
                mediaRecorder.release();
            } catch (RuntimeException ignored) { }
            mediaRecorder = null;
        }
        deleteQuietly(activeRecordingFile);
        activeRecordingFile = null;
        if (audioRecordingDialog != null) audioRecordingDialog.dismiss();
        audioRecordingDialog = null;
        for (AudioPlayback playback : new ArrayList<>(audioPlaybacks)) {
            playback.release();
        }
        audioPlaybacks.clear();
        for (File file : new ArrayList<>(temporaryAudioFiles)) deleteQuietly(file);
        temporaryAudioFiles.clear();
        mainHandler.removeCallbacksAndMessages(null);
        for (PendingMessage pending : pendingMessages) {
            if (pending.packet != null) Arrays.fill(pending.packet, (byte) 0);
        }
        pendingMessages.clear();
        synchronized (socketLock) {
            closeQuietly(pendingIncomingSocket);
            pendingIncomingSocket = null;
            closeQuietly(activeSocket);
            activeSocket = null;
            closeQuietly(serverSocket);
            serverSocket = null;
        }
        sendQueue.shutdownNow();
        decryptQueue.shutdownNow();
        mediaQueue.shutdownNow();
        pendingSaveBitmap = null;
        super.onDestroy();
    }
}

