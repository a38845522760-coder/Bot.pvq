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
 * Sends requests to the existing Hugging Face Mini App API
 * and displays the response inside VoiceInteractionSession.
 */
public class AssistantSession extends VoiceInteractionSession {

    private static final String PREFS_NAME = "ai_bale_assistant";
    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_ID_SOURCE_KEY = "client_id_source";

    private static final int MAX_API_RESPONSE_BYTES = 3 * 1024 * 1024;
    private static final long JOB_TIMEOUT_MS = 4 * 60 * 1000L;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final ExecutorService networkExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(
                        r,
                        "ai-bale-assistant-api"
                );
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
                        textToSpeech.setLanguage(
                                new Locale("fa", "IR")
                        );

                if (languageResult == TextToSpeech.LANG_MISSING_DATA
                        || languageResult
                        == TextToSpeech.LANG_NOT_SUPPORTED) {

                    textToSpeech.setLanguage(
                            new Locale("fa")
                    );
                }
            }
        });
    }

    @Override
    public View onCreateContentView() {
        Context context = getContext();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dp(16),
                dp(14),
                dp(16),
                dp(12)
        );
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        root.setBackground(
                rounded(
                        Color.rgb(250, 250, 253),
                        dp(24),
                        Color.rgb(225, 226, 234)
                )
        );

        root.setLayoutParams(
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

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
        promptInput.setHint(
                "پیامت را بنویس یا با میکروفن بگو…"
        );
        promptInput.setGravity(Gravity.TOP | Gravity.RIGHT);
        promptInput.setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
        );
        promptInput.setTextColor(Color.rgb(30, 32, 42));
        promptInput.setHintTextColor(
                Color.rgb(125, 128, 140)
        );

        promptInput.setBackground(
                rounded(
                        Color.WHITE,
                        dp(14),
                        Color.rgb(225, 226, 234)
                )
        );

        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);

        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.bottomMargin = dp(8);

        root.addView(promptInput, inputParams);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);

        micButton = actionButton("🎙 میکروفن", false);
        sendButton = actionButton("ارسال", true);

        actionRow.addView(
                micButton,
                weightedButtonParams()
        );

        actionRow.addView(
                sendButton,
                weightedButtonParams()
        );

        root.addView(actionRow, matchWrap());

        statusView = new TextView(context);
        statusView.setText("آماده‌ام؛ سؤال را بفرست.");
        statusView.setTextSize(12);
        statusView.setTextColor(Color.rgb(98, 102, 116));
        statusView.setGravity(Gravity.RIGHT);
        statusView.setPadding(
                dp(2),
                dp(7),
                dp(2),
                dp(5)
        );
        statusView.setTextDirection(
                View.TEXT_DIRECTION_FIRST_STRONG
        );

        root.addView(statusView, matchWrap());

        answerScroll = new ScrollView(context);
        answerScroll.setFillViewport(false);

        answerScroll.setBackground(
                rounded(
                        Color.WHITE,
                        dp(14),
                        Color.rgb(230, 231, 237)
                )
        );

        answerView = new TextView(context);
        answerView.setText(
                "پاسخ دستیار اینجا نمایش داده می‌شود."
        );
        answerView.setTextSize(14);
        answerView.setTextColor(Color.rgb(35, 38, 50));
        answerView.setGravity(Gravity.RIGHT);
        answerView.setTextDirection(
                View.TEXT_DIRECTION_FIRST_STRONG
        );
        answerView.setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
        );

        answerScroll.addView(
                answerView,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        LinearLayout.LayoutParams answerParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(150)
                );

        answerParams.topMargin = dp(2);

        root.addView(answerScroll, answerParams);

        LinearLayout utilityRow = new LinearLayout(context);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);

        speakButton = actionButton("🔊 شنیدن پاسخ", false);

        Button roleButton =
                actionButton("تنظیم دستیار", false);

        Button openButton =
                actionButton("برنامه کامل", false);

        utilityRow.addView(
                speakButton,
                weightedSmallButtonParams()
        );

        utilityRow.addView(
                roleButton,
                weightedSmallButtonParams()
        );

        utilityRow.addView(
                openButton,
                weightedSmallButtonParams()
        );

        LinearLayout.LayoutParams utilityParams = matchWrap();
        utilityParams.topMargin = dp(8);

        root.addView(utilityRow, utilityParams);

        Button closeButton = new Button(context);
        closeButton.setText("بستن");
        closeButton.setTextSize(12);

        LinearLayout.LayoutParams closeParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(38)
                );

        closeParams.topMargin = dp(3);

        root.addView(closeButton, closeParams);

        sendButton.setOnClickListener(
                v -> sendTypedQuery()
        );

        micButton.setOnClickListener(
                v -> startSpeechRecognition()
        );

        speakButton.setOnClickListener(
                v -> speakAnswer(lastAnswer)
        );

        roleButton.setOnClickListener(
                v -> requestAssistantRole()
        );

        openButton.setOnClickListener(
                v -> openMainActivity()
        );

        closeButton.setOnClickListener(
                v -> finish()
        );

        promptInput.setOnEditorActionListener(
                (view, actionId, event) -> {

                    boolean enterPressed =
                            event != null
                                    && event.getKeyCode()
                                    == KeyEvent.KEYCODE_ENTER
                                    && event.getAction()
                                    == KeyEvent.ACTION_DOWN;

                    if (actionId == EditorInfo.IME_ACTION_SEND
                            || enterPressed) {

                        sendTypedQuery();
                        return true;
                    }

                    return false;
                }
        );

        return root;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);

        if (statusView != null && !busy) {
            statusView.setText(
                    "آماده‌ام؛ سؤال را بفرست."
            );
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
        if (promptInput == null) {
            return;
        }

        String query =
                promptInput.getText().toString().trim();

        sendQuery(query, false);
    }

    private void sendQuery(
            String query,
            boolean speakWhenFinished
    ) {
        query = query == null ? "" : query.trim();

        if (query.isEmpty()) {
            setStatus(
                    "اول یک سؤال بنویس یا با میکروفن بگو."
            );
            return;
        }

        if (query.length() > 4000) {
            setStatus(
                    "پیام خیلی طولانی است؛ آن را کوتاه‌تر کن."
            );
            return;
        }

        if (busy) {
            setStatus(
                    "درخواست قبلی هنوز در حال پردازش است."
            );
            return;
        }

        final String submittedQuery = query;

        shouldSpeakCurrentAnswer = speakWhenFinished;
        busy = true;

        updateBusyUi(true);

        if (promptInput != null) {
            promptInput.setText("");
        }

        if (answerView != null) {
            answerView.setText("");
        }

        setStatus("در حال اتصال به دستیار…");

        try {
            networkExecutor.execute(() -> {
                try {
                    String answer =
                            askExistingMiniApp(submittedQuery);

                    mainHandler.post(() -> {
                        lastAnswer = answer;

                        if (answerView != null) {
                            answerView.setText(answer);
                        }

                        if (answerScroll != null) {
                            answerScroll.post(
                                    () -> answerScroll.fullScroll(
                                            View.FOCUS_DOWN
                                    )
                            );
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

        } catch (RuntimeException error) {
            busy = false;
            updateBusyUi(false);

            setStatus(
                    "اجرای درخواست ممکن نشد؛ دوباره تلاش کن."
            );
        }
    }

    private String askExistingMiniApp(
            String query
    ) throws Exception {

        JSONObject bootstrap = apiRequest(
                "GET",
                "/api/bootstrap",
                null,
                25000
        );

        JSONObject chat = findTextChat(bootstrap);

        if (chat == null
                || chat.optString("id", "").trim().isEmpty()) {

            JSONObject newChatPayload =
                    new JSONObject()
                            .put("type", "text")
                            .put(
                                    "title",
                                    "گفت‌وگوی دستیار AI Bale"
                            );

            JSONObject created = apiRequest(
                    "POST",
                    "/api/chats",
                    newChatPayload,
                    25000
            );

            chat = created.optJSONObject("chat");
        }

        if (chat == null) {
            throw new IOException(
                    "سرور نتوانست گفت‌وگوی دستیار را ایجاد یا بازیابی کند."
            );
        }

        String chatId =
                chat.optString("id", "").trim();

        if (chatId.isEmpty()) {
            throw new IOException(
                    "شناسهٔ گفت‌وگو از سرور دریافت نشد."
            );
        }

        JSONObject messagePayload =
                new JSONObject()
                        .put(
                                "request_id",
                                "assistant_"
                                        + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                        )
                        .put("text", query);

        JSONObject submitted = apiRequest(
                "POST",
                "/api/chats/"
                        + encodePathSegment(chatId)
                        + "/messages",
                messagePayload,
                30000
        );

        JSONObject submittedJob =
                submitted.optJSONObject("job");

        String jobId = submittedJob == null
                ? ""
                : submittedJob.optString("id", "").trim();

        if (jobId.isEmpty()) {
            throw new IOException(
                    "درخواست ثبت شد، اما شناسهٔ پردازش دریافت نشد."
            );
        }

        long deadline =
                System.currentTimeMillis() + JOB_TIMEOUT_MS;

        String lastStatus = "";

        while (System.currentTimeMillis() < deadline) {

            JSONObject pollResponse = apiRequest(
                    "GET",
                    "/api/jobs/"
                            + encodePathSegment(jobId)
                            + "?partial_from=0&partial_revision=0",
                    null,
                    25000
            );

            JSONObject job =
                    pollResponse.optJSONObject("job");

            if (job == null) {
                throw new IOException(
                        "پاسخ وضعیت پردازش از سرور معتبر نبود."
                );
            }

            String state = job.optString("status", "")
                    .toLowerCase(Locale.ROOT);

            String serverMessage =
                    job.optString("message", "").trim();

            if (!serverMessage.isEmpty()
                    && !serverMessage.equals(lastStatus)) {

                lastStatus = serverMessage;

                final String visibleStatus =
                        serverMessage.length() > 110
                                ? serverMessage.substring(0, 110)
                                : serverMessage;

                mainHandler.post(
                        () -> setStatus(visibleStatus)
                );
            }

            if ("done".equals(state)) {

                JSONObject completedChat =
                        job.optJSONObject("chat");

                String answer =
                        extractLastAssistantAnswer(completedChat);

                if (answer.isEmpty()) {

                    JSONObject refreshed = apiRequest(
                            "GET",
                            "/api/chats/"
                                    + encodePathSegment(chatId),
                            null,
                            25000
                    );

                    answer = extractLastAssistantAnswer(
                            refreshed.optJSONObject("chat")
                    );
                }

                if (answer.isEmpty()) {
                    answer = job.optString(
                            "partial_text",
                            ""
                    ).trim();
                }

                if (answer.isEmpty()) {
                    throw new IOException(
                            "پردازش تمام شد، اما متن پاسخ از سرور دریافت نشد."
                    );
                }

                return answer;
            }

            if ("error".equals(state)) {
                String error =
                        job.optString("error", "").trim();

                throw new IOException(
  
