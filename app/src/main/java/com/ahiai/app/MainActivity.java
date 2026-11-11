package com.ahiai.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.content.Context;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class MainActivity extends Activity {
    private LinearLayout messages;
    private EditText input;
    private ScrollView scroll;
    private SharedPreferences prefs;
    private SharedPreferences securePrefs;
    private TextToSpeech tts;
    private TextView status;
    private Button sendButton;
    private static final int VOICE_REQUEST = 41;
    private final int bg = Color.rgb(7, 12, 25);
    private final int panel = Color.rgb(15, 25, 47);
    private final int blue = Color.rgb(42, 153, 255);
    private final int white = Color.rgb(235, 243, 255);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
        prefs = getSharedPreferences("ahiai_memory", MODE_PRIVATE);
        try {
            MasterKey masterKey = new MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            securePrefs = EncryptedSharedPreferences.create(
                this, "ahiai_secure", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть защищённое хранилище", Toast.LENGTH_LONG).show();
        }
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ru", "RU"));
                String savedVoice = prefs.getString("tts_voice", "");
                if (!savedVoice.isEmpty() && tts.getVoices() != null) {
                    for (android.speech.tts.Voice voice : tts.getVoices()) {
                        if (savedVoice.equals(voice.getName())) { tts.setVoice(voice); break; }
                    }
                }
            }
        });
        buildUi();
        restoreMessages();
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        return v;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 12, 18, 12);
        root.setBackgroundColor(bg);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("АХИ AI", 27, white);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, 52, 1));
        Button settings = new Button(this);
        settings.setText("⚙");
        header.addView(settings, new LinearLayout.LayoutParams(56, 48));
        root.addView(header);
        settings.setOnClickListener(v -> showSettingsMenu());

        status = text("Твой голосовой помощник", 13, blue);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1, 26));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(2, 14, 2, 14);
        scroll.addView(messages);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
        sp.topMargin = 10; sp.bottomMargin = 12;
        root.addView(scroll, sp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setShowSoftInputOnFocus(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnClickListener(v -> showKeyboard());
        input.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                v.requestFocus();
                v.post(() -> {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
                });
            }
            return false;
        });
        input.setTextColor(white);
        input.setHintTextColor(Color.GRAY);
        input.setHint("Напиши сообщение...");
        input.setTextSize(15);
        input.setPadding(14, 8, 14, 8);
        input.setBackgroundColor(panel);
        bottom.addView(input, new LinearLayout.LayoutParams(0, 52, 1));
        input.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) showKeyboard(); });

        Button mic = new Button(this); mic.setText("🎙");
        bottom.addView(mic, new LinearLayout.LayoutParams(54, 52));
        sendButton = new Button(this); sendButton.setText("➤");
        bottom.addView(sendButton, new LinearLayout.LayoutParams(58, 52));
        root.addView(bottom);
        mic.setOnClickListener(v -> startVoice());
        sendButton.setOnClickListener(v -> sendMessage());
        input.setOnEditorActionListener((v, action, event) -> { sendMessage(); return true; });
        addMessage("АХИ", "Я на связи, брат. Нажми ⚙ и добавь свой ключ OpenRouter, затем можем общаться.", false, false);
    }

    private void showSettingsMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(24, 12, 24, 4);

        TextView intro = text("Управляй своим помощником АХИ", 14, blue);
        intro.setPadding(4, 4, 4, 14);
        menu.addView(intro);

        Button key = new Button(this);
        key.setText("🔑  Ключ и подключение OpenRouter");
        menu.addView(key, new LinearLayout.LayoutParams(-1, 54));

        Button voice = new Button(this);
        voice.setText("🎙  Голос АХИ");
        menu.addView(voice, new LinearLayout.LayoutParams(-1, 54));

        Button memory = new Button(this);
        memory.setText("🧹  Очистить историю переписки");
        menu.addView(memory, new LinearLayout.LayoutParams(-1, 54));

        Button about = new Button(this);
        about.setText("ℹ  О приложении");
        menu.addView(about, new LinearLayout.LayoutParams(-1, 54));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Настройки АХИ AI")
            .setView(menu)
            .setNegativeButton("Закрыть", null)
            .create();
        key.setOnClickListener(v -> { dialog.dismiss(); showKeyDialog(); });
        voice.setOnClickListener(v -> { dialog.dismiss(); showVoiceDialog(); });
        memory.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Очистить историю?")
            .setMessage("Все сохранённые сообщения будут удалены с этого телефона.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Очистить", (d, w) -> {
                prefs.edit().remove("history").apply();
                messages.removeAllViews();
                addMessage("АХИ", "История очищена. Я готов начать разговор заново.", false, false);
            }).show());
        about.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("АХИ AI")
            .setMessage("Голосовой AI-помощник. Ответы нейросети подключаются через OpenRouter. История хранится на телефоне.")
            .setPositiveButton("Понятно", null).show());
        dialog.show();
    }

    private void showVoiceDialog() {
        if (tts == null || tts.getVoices() == null) {
            Toast.makeText(this, "Список голосов ещё загружается. Попробуй чуть позже.", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<android.speech.tts.Voice> russianVoices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (android.speech.tts.Voice voice : tts.getVoices()) {
            if (voice.getLocale() != null && "ru".equals(voice.getLocale().getLanguage())) {
                russianVoices.add(voice);
                labels.add(voice.getName() + (voice.isNetworkConnectionRequired() ? " · онлайн" : " · офлайн"));
            }
        }
        if (russianVoices.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Голоса АХИ")
                .setMessage("На телефоне пока нет установленных русских голосов. Их можно добавить в системных настройках преобразования текста в речь.")
                .setPositiveButton("Понятно", null).show();
            return;
        }
        String[] choices = labels.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Выбери русский голос АХИ")
            .setItems(choices, (d, which) -> {
                android.speech.tts.Voice selected = russianVoices.get(which);
                int result = tts.setVoice(selected);
                if (result == TextToSpeech.SUCCESS) {
                    prefs.edit().putString("tts_voice", selected.getName()).apply();
                    Toast.makeText(this, "Голос сохранён", Toast.LENGTH_SHORT).show();
                    tts.speak("Привет, я АХИ. Теперь этот голос выбран.", TextToSpeech.QUEUE_FLUSH, null, "ahiai_voice_test");
                } else {
                    Toast.makeText(this, "Не удалось выбрать этот голос", Toast.LENGTH_LONG).show();
                }
            }).setNegativeButton("Отмена", null).show();
    }

    private void showKeyDialog() {
        if (securePrefs == null) {
            Toast.makeText(this, "Защищённое хранилище недоступно", Toast.LENGTH_LONG).show();
            return;
        }
        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setText(securePrefs.getString("openrouter_key", ""));
        keyInput.setHint("sk-or-...");
        keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setPadding(24, 16, 24, 16);
        new AlertDialog.Builder(this)
            .setTitle("Ключ OpenRouter")
            .setMessage("Вставь ключ только здесь. Он сохранится в защищённом хранилище телефона.")
            .setView(keyInput)
            .setPositiveButton("Сохранить", (dialog, which) -> {
                String key = keyInput.getText().toString().trim();
                if (key.isEmpty()) {
                    securePrefs.edit().remove("openrouter_key").apply();
                    status.setText("Ключ удалён");
                } else if (key.startsWith("sk-or-")) {
                    securePrefs.edit().putString("openrouter_key", key).apply();
                    status.setText("Ключ сохранён · OpenRouter");
                    Toast.makeText(this, "Ключ сохранён на телефоне", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Проверь формат ключа OpenRouter", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить ключ", (dialog, which) -> {
                securePrefs.edit().remove("openrouter_key").apply();
                status.setText("Ключ не установлен");
            })
            .show();
    }

    private void addMessage(String who, String body, boolean user, boolean speak) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(14, 10, 14, 10);
        bubble.setBackgroundColor(user ? Color.rgb(17, 62, 105) : panel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            user ? (int)(getResources().getDisplayMetrics().widthPixels * .78) : (int)(getResources().getDisplayMetrics().widthPixels * .86), -2);
        lp.gravity = user ? Gravity.RIGHT : Gravity.LEFT;
        lp.bottomMargin = 10;
        TextView label = text(who, 11, blue);
        TextView content = text(body, 16, white);
        content.setPadding(0, 4, 0, 0);
        bubble.addView(label); bubble.addView(content);
        messages.addView(bubble, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        if (speak && tts != null) tts.speak(body, TextToSpeech.QUEUE_FLUSH, null, "ahiai_reply");
    }

    private void showKeyboard() {
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_FORCED);
        }, 150);
    }

    private void sendMessage() {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return;
        String key = securePrefs == null ? null : securePrefs.getString("openrouter_key", null);
        input.setText("");
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(input.getWindowToken(), 0);
        addMessage("ТЫ", value, true, false);
        saveLine("Ты: " + value);
        if (key == null || key.isEmpty()) {
            String reply = "Чтобы я отвечал нейросетью, сначала нажми ⚙ и сохрани ключ OpenRouter.";
            addMessage("АХИ", reply, false, false);
            saveLine("АХИ: " + reply);
            return;
        }
        sendButton.setEnabled(false);
        status.setText("АХИ думает…");
        network.execute(() -> requestOpenRouter(key));
    }

    private void requestOpenRouter(String key) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(45000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("X-Title", "Ahi AI");
            JSONObject body = new JSONObject();
            body.put("model", "openrouter/free");
            body.put("temperature", 0.7);
            body.put("max_tokens", 700);
            JSONArray chat = new JSONArray();
            chat.put(new JSONObject().put("role", "system").put("content",
                "Ты АХИ AI — полезный русскоязычный помощник. Всегда отвечай строго по-русски, естественно, конкретно и по существу. Сначала выполни просьбу или ответь на вопрос пользователя. Не отвечай фразами «что хотел спросить?», «чем могу помочь?» или другими пустыми встречными вопросами, если пользователь уже задал вопрос или дал задачу. Не переспрашивай, если смысл понятен; если действительно не хватает важной информации, задай один короткий уточняющий вопрос. Не повторяй приветствие в каждом сообщении. Не выдумывай факты и честно говори, если чего-то не знаешь. Тон дружелюбный и спокойный."));
            String[] rows = prefs.getString("history", "").split("\\n");
            int first = Math.max(0, rows.length - 12);
            for (int i = first; i < rows.length; i++) {
                String row = rows[i];
                if (row.startsWith("Ты: ")) chat.put(new JSONObject().put("role", "user").put("content", row.substring(4)));
                else if (row.startsWith("АХИ: ")) chat.put(new JSONObject().put("role", "assistant").put("content", row.substring(5)));
            }
            body.put("messages", chat);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                stream.close();
            }
            String raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                String message = "Ошибка OpenRouter (" + code + "). Проверь ключ, доступность бесплатной модели и лимит аккаунта.";
                try {
                    JSONObject err = new JSONObject(raw).optJSONObject("error");
                    if (err != null && err.has("message")) message += "\\n" + err.optString("message");
                } catch (Exception ignored) {}
                showReply(message, false);
                return;
            }
            JSONObject result = new JSONObject(raw);
            JSONArray choices = result.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new Exception("Пустой ответ модели");
            String answer = choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            if (answer.isEmpty()) answer = "Модель вернула пустой ответ. Попробуй ещё раз.";
            showReply(answer, true);
        } catch (Exception e) {
            showReply("Не удалось связаться с нейросетью. Проверь мобильный интернет и попробуй ещё раз.", false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showReply(String reply, boolean success) {
        mainHandler.post(() -> {
            addMessage("АХИ", reply, false, success);
            saveLine("АХИ: " + reply);
            status.setText(success ? "На связи · OpenRouter" : "Нет ответа от нейросети");
            sendButton.setEnabled(true);
        });
    }

    private void saveLine(String line) {
        String old = prefs.getString("history", "");
        String updated = old + line + "\\n";
        String[] rows = updated.split("\\n");
        StringBuilder last = new StringBuilder();
        int start = Math.max(0, rows.length - 80);
        for (int i = start; i < rows.length; i++) if (!rows[i].isEmpty()) last.append(rows[i]).append("\\n");
        prefs.edit().putString("history", last.toString()).apply();
    }

    private void restoreMessages() {
        String history = prefs.getString("history", "");
        if (history.isEmpty()) return;
        messages.removeAllViews();
        for (String row : history.split("\\n")) {
            if (row.startsWith("Ты: ")) addMessage("ТЫ", row.substring(4), true, false);
            else if (row.startsWith("АХИ: ")) addMessage("АХИ", row.substring(5), false, false);
        }
    }

    private void startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори, я слушаю");
        try { startActivityForResult(intent, VOICE_REQUEST); }
        catch (Exception e) { Toast.makeText(this, "Не удалось открыть распознавание речи", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) { input.setText(results.get(0)); sendMessage(); }
        }
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
