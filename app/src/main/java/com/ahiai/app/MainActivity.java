package com.ahiai.app;

import android.app.Activity;
import android.os.Bundle;
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
import android.content.Context;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout messages;
    private EditText input;
    private ScrollView scroll;
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private static final int VOICE_REQUEST = 41;
    private final int bg = Color.rgb(7, 12, 25);
    private final int panel = Color.rgb(15, 25, 47);
    private final int blue = Color.rgb(42, 153, 255);
    private final int white = Color.rgb(235, 243, 255);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        prefs = getSharedPreferences("ahiai_memory", MODE_PRIVATE);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("ru", "RU"));
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
        root.setPadding(18, 18, 18, 12);
        root.setBackgroundColor(bg);
        setContentView(root);

        TextView title = text("АХИ AI", 28, white);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, 52));
        TextView subtitle = text("Твой голосовой помощник", 13, blue);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, 28));

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
        input.setTextColor(white);
        input.setHintTextColor(Color.GRAY);
        input.setHint("Напиши сообщение...");
        input.setTextSize(15);
        input.setPadding(14, 8, 14, 8);
        input.setBackgroundColor(panel);
        bottom.addView(input, new LinearLayout.LayoutParams(0, 52, 1));

        Button mic = new Button(this); mic.setText("🎙");
        bottom.addView(mic, new LinearLayout.LayoutParams(54, 52));
        Button send = new Button(this); send.setText("➤");
        bottom.addView(send, new LinearLayout.LayoutParams(58, 52));
        root.addView(bottom);
        mic.setOnClickListener(v -> startVoice());
        send.setOnClickListener(v -> sendMessage());
        input.setOnEditorActionListener((v, action, event) -> { sendMessage(); return true; });
        addMessage("АХИ", "Я на связи, брат. Напиши мне или нажми на микрофон.", false, false);
    }

    private void addMessage(String who, String body, boolean user, boolean speak) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(14, 10, 14, 10);
        bubble.setBackgroundColor(user ? Color.rgb(17, 62, 105) : panel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            user ? (int)(getResources().getDisplayMetrics().widthPixels * .78) : (int)(getResources().getDisplayMetrics().widthPixels * .86),
            -2);
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

    private void sendMessage() {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return;
        input.setText("");
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(input.getWindowToken(), 0);
        addMessage("ТЫ", value, true, false);
        saveLine("Ты: " + value);
        String reply = "Я получил твоё сообщение: «" + value + "». Интерфейс и память работают. Подключение настоящей AI-модели — следующий этап.";
        addMessage("АХИ", reply, false, false);
        saveLine("АХИ: " + reply);
    }

    private void saveLine(String line) {
        String old = prefs.getString("history", "");
        String updated = old + line + "\n";
        String[] rows = updated.split("\n");
        StringBuilder last = new StringBuilder();
        int start = Math.max(0, rows.length - 80);
        for (int i = start; i < rows.length; i++) if (!rows[i].isEmpty()) last.append(rows[i]).append("\n");
        prefs.edit().putString("history", last.toString()).apply();
    }

    private void restoreMessages() {
        String history = prefs.getString("history", "");
        if (history.isEmpty()) return;
        messages.removeAllViews();
        for (String row : history.split("\n")) {
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
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
