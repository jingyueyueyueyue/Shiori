package org.catsystem2.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** A real editor/PIN consumer. Recreating a view never authorizes or submits.
 * Process death discards the native session rather than replaying unknown posts. */
public final class TweetActivity extends Activity {
    private AndroidTweetHost.Editor editor;
    private EditText input;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String key = saved == null ? getIntent().getStringExtra("request") : saved.getString("request");
        editor = key == null ? null : AndroidTweetHost.EDITORS.get(key);
        if (editor == null || editor.completed) {
            TextView message = new TextView(this);
            message.setText("Tweet session ended. No authorization or post was replayed.");
            setContentView(message); finish(); return;
        }
        editor.activity = new WeakReference<>(this);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { editor.finish(false, ""); finish(); });
        }
        setTitle(editor.title);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView prompt = new TextView(this); prompt.setText(editor.prompt); layout.addView(prompt);
        input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(editor.limit)});
        input.setSaveEnabled(false); // only our bounded draft enters instance state
        input.setText(saved == null ? editor.draft : saved.getString("draft", editor.draft));
        input.setSelection(input.length());
        layout.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        Button accept = new Button(this); accept.setText(android.R.string.ok);
        accept.setOnClickListener(view -> { editor.finish(true, input.getText().toString()); finish(); });
        Button cancel = new Button(this); cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> { editor.finish(false, ""); finish(); });
        buttons.addView(accept); buttons.addView(cancel); layout.addView(buttons);
        if (editor.suffix != null) {
            TextView tags = new TextView(this); tags.setText(editor.suffix); layout.addView(tags);
            TextView counter = new TextView(this); layout.addView(counter);
            Runnable update = () -> {
                int count = AndroidTweetHost.nativeCount(input.getText().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    editor.suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                accept.setEnabled(count >= 0 && count <= 140);
                counter.setText(count < 0 ? "Text is not CP932 encodable or exceeds1023 bytes" : (140-count) + "/140");
            };
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable value) { update.run(); }
            });
            update.run();
            Button forget = new Button(this); forget.setText("Forget authorization");
            forget.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setMessage("Forget this application's saved authorization? No Tweet will be sent.")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { editor.forget(); finish(); })
                .setNegativeButton(android.R.string.cancel, null).show());
            layout.addView(forget);
        }
        setContentView(layout);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (editor != null && input != null) {
            editor.draft = input.getText().toString();
            state.putString("request", editor.key); state.putString("draft", editor.draft);
        }
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (editor != null) editor.finish(false, "");
        super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (editor != null && !isChangingConfigurations() && isFinishing()) editor.finish(false, "");
        super.onDestroy();
    }
}
