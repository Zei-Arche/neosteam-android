package org.neosteam.revival.gameplay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.EnumMap;

public final class ChatPanel {
    private static final int PANEL = 0xff101b29;
    private static final int FIELD = 0xff16273a;
    private static final int TEXT = 0xffeff5fa;
    private static final int MUTED = 0xffbac8d5;
    private static final int BRASS = 0xfff4b66a;

    private final Context context;
    private final ChatSession session;
    private final LinearLayout root;
    private final EditText recipient;
    private final EditText message;
    private final TextView transcript;
    private final TextView feedback;
    private final ScrollView scroll;
    private final EnumMap<ChatSession.Channel, String> drafts = new EnumMap<>(ChatSession.Channel.class);
    private ChatSession.Channel channel = ChatSession.Channel.LOCAL;

    public ChatPanel(Context context, ChatSession session) {
        this.context = context;
        this.session = session;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackground(surface(PANEL));

        Spinner channels = new Spinner(context);
        channels.setContentDescription("Chat channel");
        channels.setMinimumHeight(dp(48));
        channels.setBackgroundTintList(ColorStateList.valueOf(BRASS));
        ArrayAdapter<ChatSession.Channel> adapter = new ArrayAdapter<>(context,
            android.R.layout.simple_spinner_item, ChatSession.Channel.values()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        channels.setAdapter(adapter);
        add(channels, 0);

        recipient = field("Whisper recipient");
        recipient.setVisibility(View.GONE);
        recipient.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        add(recipient, 6);

        scroll = new ScrollView(context);
        scroll.setBackground(surface(FIELD));
        scroll.setFillViewport(true);
        transcript = label("", TEXT, 15);
        transcript.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(transcript);
        LinearLayout.LayoutParams history = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        history.topMargin = dp(6);
        root.addView(scroll, history);

        LinearLayout compose = new LinearLayout(context);
        compose.setGravity(Gravity.CENTER_VERTICAL);
        message = field("Message");
        message.setImeOptions(EditorInfo.IME_ACTION_SEND);
        compose.addView(message, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = new Button(context);
        send.setText("Send");
        send.setTextColor(PANEL);
        send.setBackgroundTintList(ColorStateList.valueOf(BRASS));
        send.setMinHeight(dp(48));
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        sendSize.setMarginStart(dp(8));
        compose.addView(send, sendSize);
        add(compose, 8);

        feedback = label("", MUTED, 14);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(View.GONE);
        add(feedback, 4);

        channels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectChannel((ChatSession.Channel) parent.getItemAtPosition(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        send.setOnClickListener(view -> send());
        message.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) {
                return false;
            }
            send();
            return true;
        });
        refresh();
    }

    public View view() {
        return root;
    }

    // Call on the UI thread after delivering a decoded inbound chat message.
    public void refresh() {
        StringBuilder content = new StringBuilder();
        for (ChatSession.Message entry : session.messages(channel)) {
            if (content.length() != 0) {
                content.append('\n');
            }
            content.append(entry.sender());
            if (channel == ChatSession.Channel.WHISPER && !entry.recipient().isEmpty()) {
                content.append(" \u2192 ").append(entry.recipient());
            }
            content.append(": ").append(entry.text());
        }
        if (content.length() == 0) {
            transcript.setText("No messages");
        } else {
            transcript.setText(content);
            scroll.post(() -> scroll.scrollTo(0, transcript.getBottom()));
        }
    }

    private void selectChannel(ChatSession.Channel next) {
        if (next == channel) {
            return;
        }
        drafts.put(channel, message.getText().toString());
        channel = next;
        recipient.setVisibility(channel == ChatSession.Channel.WHISPER ? View.VISIBLE : View.GONE);
        String draft = drafts.get(channel);
        message.setText(draft == null ? "" : draft);
        message.setSelection(message.length());
        showFeedback("");
        refresh();
    }

    private void send() {
        String target = channel == ChatSession.Channel.WHISPER ? recipient.getText().toString() : "";
        try {
            if (!session.send(channel, target, message.getText().toString())) {
                showFeedback("Chat message could not be queued");
                return;
            }
            message.setText("");
            drafts.remove(channel);
            showFeedback(session.isPractice() ? "" : "Message queued");
            refresh();
        } catch (IllegalArgumentException | IllegalStateException error) {
            showFeedback(error.getMessage());
        }
    }

    private void showFeedback(String text) {
        feedback.setText(text);
        feedback.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView label(String text, int color, int size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private EditText field(String hint) {
        EditText view = new EditText(context);
        view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setHint(hint);
        view.setMinHeight(dp(48));
        view.setBackgroundTintList(ColorStateList.valueOf(BRASS));
        return view;
    }

    private GradientDrawable surface(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private void add(View view, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        root.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
