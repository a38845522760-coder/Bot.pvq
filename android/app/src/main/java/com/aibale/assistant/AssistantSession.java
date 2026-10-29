package com.aibale.assistant;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compact Android assistant surface.
 *
 * It sends requests to the existing Hugging Face Mini App API and displays
 * the response inside the system VoiceInteractionSession.
 */
public class AssistantSession extends VoiceInteractionSession {

    private static final String PREFS_NAME = "ai_bale_assistant";
    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_ID_SOURCE_KEY = "client_id_source";
    private static final int MAX_API_RESPONSE_BYTES = 3 * 1024 * 1024;
    private static final long JOB_TIMEOUT_MS = 4 * 60 * 1000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ai-bale-assistant-api");
                thread.setDaemon(true);
                return thread;
            });

    private EditText promptInput;
    private TextView statusView;
    private TextView answerView;
    private ScrollView answerScroll;
    private Button sendButton;
    private Button micButton;
    private Button speakButton;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech textToSpeech;

    private volatile boolean busy;
    private volatile boolean ttsReady;
    private volatile String lastAnswer = "";
    private boolean shouldSpeakCurrentAnswer;

    public AssistantSession(Context context) {
        super(context);

        textToSpeech = new TextToSpeech(context, result -> {
            ttsReady = result == TextToSpeech.SUCCESS;

            if (ttsReady && textToSpeech != null) {
                int languageResult =
                        textToSpeech.setLanguage(new Locale("fa", "IR"));

                if (languageResult == TextToSpeech.LANG_MISSING_DATA
                        || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(new Locale("fa"));
                }
            }
        });
    }

    @Override
    public View onCreateContentView() {
        Context context = getContext();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackground(
                rounded(Color.rgb(250, 250, 253), dp(24),
                        Color.rgb(225, 226, 234)));

        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText("AI Bale");
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(35, 38, 50));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(context);
        subtitle.setText("دستیار هوش مصنوعی");
        subtitle.setTextSize(12);
        subtitle.setTextColor(Color.rgb(105, 108, 120));
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(2);
        subtitleParams.bottomMargin = dp(10);
        root.addView(subtitle, subtitleParams);

        promptInput = new EditText(context);
        promptInput.setTextSize(15);
        promptInput.setSingleLine(false);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(4);
        promptInput.setHint("پیامت را بنویس یا با میکروفن بگو…");
        promptInput.setGravity(Gravity.TOP | Gravity.RIGHT);
        promptInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        promptInput.setTextColor(Color.rgb(30, 32, 42));
        promptInput.setHintTextColor(Color.rgb(125, 128, 140));
        promptInput.setBackground(
                rounded(Color.WHITE, dp(14), Color.rgb(225, 226, 234)));
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);

        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.bottomMargin = dp(8);
        root.addView(promptInput, inputParams);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);

        micButton = actionButton("🎙 میکروفن", false);
        sendButton = actionButton("ارسال", true);

        actionRow.addView(micButton, weightedButtonParams());
        actionRow.addView(sendButton, weightedButtonParams());
        root.addView(actionRow, matchWrap());

        statusView = new TextView(context);
        statusView.setText("آماده‌ام؛ سؤال را بفرست.");
        statusView.setTextSize(12);
        statusView.setTextColor(Color.rgb(98, 102, 116));
        statusView.setGravity(Gravity.RIGHT);
        statusView.setPadding(dp(2), dp(7), dp(2), dp(5));
        statusView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        root.addView(statusView, matchWrap());

        answerScroll = new ScrollView(context);
        answerScroll.setFillViewport(false);
        answerScroll.setBackground(
                rounded(Color.WHITE, dp(14), Color.rgb(230, 231, 237)));

        answerView = new TextView(context);
        answerView.setText("پاسخ دستیار اینجا نمایش داده می‌شود.");
        answerView.setTextSize(14);
        answerView.setTextColor(Color.rgb(35, 38, 50));
        answerView.setGravity(Gravity.RIGHT);
        answerView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        answerView.setPadding(dp(12), dp(10), dp(12), dp(10));

        answerScroll.addView(answerView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams answerParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(150));

        answerParams.topMargin = dp(2);
        root.addView(answerScroll, answerParams);

        LinearLayout utilityRow = new LinearLayout(context);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);

        speakButton = actionButton("🔊 شنیدن پاسخ", false);
        Button roleButton = actionButton("تنظیم دستیار", false);
        Button openButton = actionButton("برنامه کامل", false);

        utilityRow.addView(speakButton, weightedSmallButtonParams());
        utilityRow.addView(roleButton, weightedSmallButtonParams());
        utilityRow.addView(openButton, weightedSmallButtonParams());

        LinearLayout.LayoutParams utilityParams = matchWrap();
        utilityParams.topMargin = dp(8);
        root.addView(utilityRow, utilityParams);

        Button closeButton = new Button(context);
        closeButton.setText("بستن");
        closeButton.setTextSize(12);

        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(38));

        closeParams.topMargin = dp(3);
        root.addView(closeButton, closeParams);

        sendButton.setOnClickListener(v -> sendTypedQuery());
        micButton.setOnClickListener(v -> startSpeechRecognition());
        speakButton.setOnClickListener(v -> speakAnswer(lastAnswer));
        roleButton.setOnClickListener(v -> requestAssistantRole());
        openButton.setOnClickListener(v -> openMainActivity());
        closeButton.setOnClickListener(v -> finish());

        promptInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                sendTypedQuery();
                return true;
            }

            return false;
        });

        return root;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);

        if (statusView != null && !busy) {
            statusView.setText("آماده‌ام؛ سؤال را بفرست.");
        }
    }

    @Override
    public void onHide() {
        stopSpeechRecognition();
        super.onHide();
    }

    @Override
    public void onDestroy() {
        stopSpeechRecognition();

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }

        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void sendTypedQuery() {
        if (promptInput == null) return;

        String query = promptInput.getText().toString().trim();
        sendQuery(query, false);
    }

    private void sendQuery(String query, boolean speakWhenFinished) {
        query = query == null ? "" : query.trim();

        if (query.isEmpty()) {
            setStatus("اول یک سؤال بنویس یا با میکروفن بگو.");
            return;
        }

        if (query.length() > 4000) {
            setStatus("پیام خیلی طولانی است؛ آن را کوتاه‌تر کن.");
            return;
        }

        if (busy) {
            setStatus("درخواست قبلی هنوز در حال پردازش است.");
            return;
        }

        final String submittedQuery = query;
        shouldSpeakCurrentAnswer = speakWhenFinished;
        busy = true;

        updateBusyUi(true);

        if (promptInput != null) promptInput.setText("");
        if (answerView != null) answerView.setText("");

        setStatus("در حال اتصال به دستیار…");

        networkExecutor.execute(() -> {
            try {
                String answer = askExistingMiniApp(submittedQuery);

                mainHandler.post(() -> {
                    lastAnswer = answer;

                    if (answerView != null) {
                        answerView.setText(answer);
                    }

                    if (answerScroll != null) {
                        answerScroll.post(
                                () -> answerScroll.fullScroll(View.FOCUS_UP));
                    }

                    setStatus("پاسخ آماده شد.");
                    busy = false;
                    updateBusyUi(false);

                    if (shouldSpeakCurrentAnswer) {
                        speakAnswer(answer);
                    }
                });
            } catch (Exception error) {
                String message = friendlyError(error);

                mainHandler.post(() -> {
                    busy = false;
                    updateBusyUi(false);
                    setStatus(message);
                });
            }
        });
    }

    private String askExistingMiniApp(String query) throws Exception {
        JSONObject bootstrap = apiRequest(
                "GET", "/api/bootstrap", null, 25000);

        JSONObject chat = findTextChat(bootstrap);

        if (chat == null || chat.optString("id", "").trim().isEmpty()) {
            JSONObject newChatPayload = new JSONObject()
                    .put("type", "text")
                    .put("title", "گفت‌وگوی دستیار AI Bale");

            JSONObject created = apiRequest(
                    "POST", "/api/chats", newChatPayload, 25000);

            chat = created.optJSONObject("chat");
        }

        if (chat == null) {
            throw new IOException(
                    "سرور نتوانست گفت‌وگوی دستیار را ایجاد یا بازیابی کند.");
        }

        String chatId = chat.optString("id", "").trim();

        if (chatId.isEmpty()) {
            throw new IOException("شناسهٔ گفت‌وگو از سرور دریافت نشد.");
        }

        JSONObject messagePayload = new JSONObject()
                .put("request_id",
                        "assistant_" + UUID.randomUUID()
                                .toString().replace("-", ""))
                .put("text", query);

        JSONObject submitted = apiRequest(
                "POST",
                "/api/chats/" + encodePathSegment(chatId) + "/messages",
                messagePayload,
                30000);

        JSONObject submittedJob = submitted.optJSONObject("job");
        String jobId = submittedJob == null
                ? ""
                : submittedJob.optString("id", "").trim();

        if (jobId.isEmpty()) {
            throw new IOException(
                    "درخواست ثبت شد، اما شناسهٔ پردازش دریافت نشد.");
        }

        long deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS;
        String lastStatus = "";

        while (System.currentTimeMillis() < deadline) {
            JSONObject pollResponse = apiRequest(
                    "GET",
                    "/api/jobs/" + encodePathSegment(jobId)
                            + "?partial_from=0&partial_revision=0",
                    null,
                    25000);

            JSONObject job = pollResponse.optJSONObject("job");

            if (job == null) {
                throw new IOException(
                        "پاسخ وضعیت پردازش از سرور معتبر نبود.");
            }

            String state = job.optString("status", "")
                    .toLowerCase(Locale.ROOT);

            String serverMessage = job.optString("message", "").trim();

            if (!serverMessage.isEmpty()
                    && !serverMessage.equals(lastStatus)) {
                lastStatus = serverMessage;

                final String visibleStatus =
                        serverMessage.length() > 110
                                ? serverMessage.substring(0, 110)
                                : serverMessage;

                mainHandler.post(() -> setStatus(visibleStatus));
            }

            if ("done".equals(state)) {
                JSONObject completedChat = job.optJSONObject("chat");
                String answer = extractLastAssistantAnswer(completedChat);

                if (answer.isEmpty()) {
                    JSONObject refreshed = apiRequest(
                            "GET",
                            "/api/chats/" + encodePathSegment(chatId),
                            null,
                            25000);

                    answer = extractLastAssistantAnswer(
                            refreshed.optJSONObject("chat"));
                }

                if (answer.isEmpty()) {
                    answer = job.optString("partial_text", "").trim();
                }

                if (answer.isEmpty()) {
                    throw new IOException(
                            "پردازش تمام شد، اما متن پاسخ از سرور دریافت نشد.");
                }

                return answer;
            }

            if ("error".equals(state)) {
                String error = job.optString("error", "").trim();

                throw new IOException(
                        error.isEmpty()
                                ? "پردازش سؤال ناموفق بود."
                                : error);
            }

            if ("cancelled".equals(state)) {
                throw new IOException("پردازش توسط سرور متوقف شد.");
            }

            Thread.sleep(850L);
        }

        throw new SocketTimeoutException(
                "پاسخ بیش از زمان مجاز طول کشید. دوباره تلاش کن.");
    }

    private JSONObject findTextChat(JSONObject bootstrap) {
        JSONObject current = bootstrap.optJSONObject("current");

        if (current != null
                && "text".equalsIgnoreCase(
                        current.optString("type", "text"))) {
            return current;
        }

        JSONArray chats = bootstrap.optJSONArray("chats");

        if (chats != null) {
            for (int i = 0; i < chats.length(); i++) {
                JSONObject chat = chats.optJSONObject(i);

                if (chat != null
                        && "text".equalsIgnoreCase(
                                chat.optString("type", "text"))) {
                    return chat;
                }
            }
        }

        return null;
    }

    private String extractLastAssistantAnswer(JSONObject chat) {
        if (chat == null) return "";

        JSONArray messages = chat.optJSONArray("messages");

        if (messages == null) return "";

        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);

            if (message == null) continue;

            String role = message.optString("role", "");

            if ("ai".equalsIgnoreCase(role)
                    || "assistant".equalsIgnoreCase(role)) {
                String text = message.optString("text", "").trim();

                if (!text.isEmpty()) return text;
            }
        }

        return "";
    }

    private JSONObject apiRequest(
            String method,
            String path,
            JSONObject body,
            int readTimeoutMs
    ) throws Exception {
        URL url = new URL(baseUrl() + path);

        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException(
                    "اتصال امن به سرور پیکربندی نشده است.");
        }

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-store");
        connection.setRequestProperty(
                "X-Mini-App-Client-ID", getOrCreateClientId());

        try {
            if (body != null) {
                byte[] bytes = body.toString()
                        .getBytes(StandardCharsets.UTF_8);

                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();

                                200 && status < 300)
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            String responseText =
                    readBounded(stream, MAX_API_RESPONSE_BYTES);

            JSONObject response;

            try {
                Object parsed = new JSONTokener(responseText).nextValue();
                response = parsed instanceof JSONObject
                        ? (JSONObject) parsed
                        : new JSONObject();
            } catch (Exception e) {
                response = new JSONObject();
            }

            if (status < 200 || status >= 300
                    || !response.optBoolean("ok", true)) {
                String serverError =
                        response.optString("error", "").trim();

                if (status == 401 || status == 403) {
                    throw new IOException(
                            "سرور دسترسی دستیار را نپذیرفت.");
                }

                if (status == 429) {
                    throw new IOException(
                            "درخواست‌ها زیاد شده‌اند؛ کمی صبر کن.");
                }

                if (status >= 500) {
                    throw new IOException(
                            "سرور هوش مصنوعی خطا داد (" + status + ").");
                }

                throw new IOException(
                        serverError.isEmpty()
                                ? "درخواست ناموفق بود (" + status + ")."
                                : serverError);
            }

            return response;
        } finally {
            connection.disconnect();
        }
    }

    private String baseUrl() throws Exception {
        String configured = BuildConfig.HF_SPACE_URL == null
                ? ""
                : BuildConfig.HF_SPACE_URL.trim();

        while (configured.endsWith("/")) {
            configured = configured.substring(
                    0, configured.length() - 1);
        }

        if (configured.isEmpty()) {
            configured =
                    "https://mohamad1039-bot-gemini.hf.space";
        }

        URL url = new URL(configured);

        if (!"https".equalsIgnoreCase(url.getProtocol())
                || url.getHost() == null) {
            throw new IOException("نشانی HTTPS معتبر نیست.");
        }

        return configured;
    }

    private String getOrCreateClientId() {
        SharedPreferences preferences =
                getContext().getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);

        String existing =
                preferences.getString(CLIENT_ID_KEY, "");

        if (isValidClientId(existing)) return existing;

        String generated = "c" + UUID.randomUUID()
                .toString().replace("-", "")
                .toLowerCase(Locale.ROOT);

        preferences.edit()
                .putString(CLIENT_ID_KEY, generated)
                .putString(CLIENT_ID_SOURCE_KEY, "assistant")
                .apply();

        return generated;
    }

    private boolean isValidClientId(String value) {
        return value != null
                && (value.matches("c[a-fA-F0-9]{32}")
                || value.matches("[a-fA-F0-9]{32}"));
    }

    private String encodePathSegment(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20");
    }

    private String readBounded(InputStream stream, int limit)
            throws IOException {
        if (stream == null) return "";

        try (InputStream input = stream;
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int total = 0;
            int count;

            while ((count = input.read(buffer)) != -1) {
                total += count;

                if (total > limit) {
                    throw new IOException(
                            "پاسخ سرور بیش از حد مجاز است.");
                }

                output.write(buffer, 0, count);
            }

            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String friendlyError(Exception error) {
        String message = error == null
                ? ""
                : String.valueOf(error.getMessage());

        if (message.trim().isEmpty()) {
            return "ارتباط با دستیار برقرار نشد.";
        }

        if (error instanceof SocketTimeoutException
                || message.toLowerCase(Locale.ROOT).contains("timeout")) {
            return "پاسخ سرور طول کشید؛ دوباره تلاش کن.";
        }

        return message.length() > 240
                ? message.substring(0, 240)
                : message;
    }

    private void startSpeechRecognition() {
        if (busy) {
            setStatus("درخواست قبلی هنوز در حال پردازش است.");
            return;
        }

        if (getContext().checkSelfPermission(
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus("برای استفاده از میکروفن، مجوز لازم است.");
            openMainActivityForMicPermission();
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            setStatus("سرویس تشخیص گفتار در دسترس نیست.");
            return;
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer =
                        SpeechRecognizer.createSpeechRecognizer(getContext());

                speechRecognizer.setRecognitionListener(
                        new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        setStatus("گوش می‌دهم…");
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        setStatus("در حال شنیدن…");
                    }

                    @Override
                    public void onRmsChanged(float value) { }

                    @Override
                    public void onBufferReceived(byte[] buffer) { }

                    @Override
                    public void onEndOfSpeech() {
                        setStatus("در حال تبدیل گفتار به متن…");
                    }

                    @Override
                    public void onError(int error) {
                        setStatus(speechErrorMessage(error));
                    }

                    @Override
                    public void onResults(Bundle results) {
                        String text = firstRecognitionResult(results);

                        if (text.isEmpty()) {
                            setStatus("گفتاری تشخیص داده نشد.");
                        } else {
                            if (promptInput != null) {
                                promptInput.setText(text);
                            }
                            sendQuery(text, true);
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle results) {
                        String text = firstRecognitionResult(results);
                        if (!text.isEmpty()) {
                            setStatus("شنیده‌شده: " + text);
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) { }
                });
            }

            Intent intent = new Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
            intent.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            setStatus("شروع تشخیص گفتار ناموفق بود.");
        }
    }

    private String firstRecognitionResult(Bundle bundle) {
        if (bundle == null) return "";

        java.util.ArrayList<String> results =
                bundle.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);

        return results == null || results.isEmpty()
                || results.get(0) == null
                ? ""
                : results.get(0).trim();
    }

    private String speechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "مجوز میکروفن داده نشده است.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "خطای شبکه در تشخیص گفتار.";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "گفتاری تشخیص داده نشد؛ دوباره تلاش کن.";
            default:
                return "تشخیص گفتار ناموفق بود.";
        }
    }

    private void stopSpeechRecognition() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
            speechRecognizer = null;
        }
    }

    private void speakAnswer(String text) {
        if (text == null || text.trim().isEmpty()) {
            setStatus("پاسخی برای پخش وجود ندارد.");
            return;
        }

        if (textToSpeech == null || !ttsReady) {
            setStatus("موتور گفتار هنوز آماده نیست.");
            return;
        }

        String spoken = text.length() > 3500
                ? text.substring(0, 3500)
                : text;

        textToSpeech.speak(
                spoken,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ai_bale_answer");
    }

    private void requestAssistantRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roleManager =
                        getContext().getSystemService(RoleManager.class);

                if (roleManager != null
                        && roleManager.isRoleAvailable(
                                RoleManager.ROLE_ASSISTANT)) {
                    Intent intent = roleManager.createRequestRoleIntent(
                            RoleManager.ROLE_ASSISTANT);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                    return;
                }
            }

            Intent intent =
                    new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            setStatus("تنظیم دستیار از تنظیمات اندروید انجام می‌شود.");
        }
    }

    private void openMainActivity() {
        Intent intent = new Intent(getContext(), MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        getContext().startActivity(intent);
        finish();
    }

    private void openMainActivityForMicPermission() {
        Intent intent = new Intent(getContext(), MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(
                MainActivity.EXTRA_REQUEST_AUDIO_PERMISSION, true);
        getContext().startActivity(intent);
    }

    private void setStatus(String value) {
        mainHandler.post(() -> {
            if (statusView != null) {
                statusView.setText(value == null ? "" : value);
            }
        });
    }

    private void updateBusyUi(boolean isBusy) {
        mainHandler.post(() -> {
            if (sendButton != null) {
                sendButton.setEnabled(!isBusy);
                sendButton.setText(
                        isBusy ? "در حال پاسخ…" : "ارسال");
            }

            if (micButton != null) {
                micButton.setEnabled(!isBusy);
            }

            if (speakButton != null) {
                speakButton.setEnabled(!lastAnswer.isEmpty() && !isBusy);
            }
        });
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(getContext());
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        button.setTextColor(primary
                ? Color.WHITE
                : Color.rgb(40, 44, 60));
        button.setBackground(
                rounded(
                        primary ? Color.rgb(68, 91, 220)
                                : Color.rgb(239, 240, 246),
                        dp(12),
                        primary ? Color.rgb(68, 91, 220)
                                : Color.rgb(225, 226, 234)));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams weightedSmallButtonParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private GradientDrawable rounded(
            int fillColor, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(
                value * getContext().getResources()
                        .getDisplayMetrics().density);
    }
                    }
