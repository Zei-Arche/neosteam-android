package org.neosteam.revival.gameplay;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class ChatSession {
    public enum Channel {
        LOCAL("Local"), PARTY("Party"), GUILD("Guild"), WHISPER("Whisper");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @FunctionalInterface
    public interface Sender {
        // Enqueue promptly and return whether accepted. A decoded inbound echo
        // or delivery result is independent of this local queue response.
        boolean enqueue(Outbound outbound);
    }

    public record Outbound(Channel channel, byte[] recipientCp949, byte[] messageCp949) {
        public Outbound {
            Objects.requireNonNull(channel, "channel");
            recipientCp949 = recipientCp949.clone();
            messageCp949 = messageCp949.clone();
        }

        @Override
        public byte[] recipientCp949() {
            return recipientCp949.clone();
        }

        @Override
        public byte[] messageCp949() {
            return messageCp949.clone();
        }
    }

    public record Message(String sender, String recipient, String text) {}

    // Display history is bounded per channel. The packet adapter supplies any
    // protocol-specific field size, route and permission checks.
    private static final int MAX_HISTORY = 100;

    static {
        System.loadLibrary("neosteam");
    }

    private final Sender sender;
    private final boolean practice;
    private final EnumMap<Channel, ArrayDeque<Message>> history = new EnumMap<>(Channel.class);

    public ChatSession(Sender sender) {
        this(Objects.requireNonNull(sender, "sender"), false);
    }

    private ChatSession(Sender sender, boolean practice) {
        this.sender = sender;
        this.practice = practice;
        for (Channel channel : Channel.values()) {
            history.put(channel, new ArrayDeque<>());
        }
    }

    public static ChatSession practice() {
        return new ChatSession(null, true);
    }

    public boolean isPractice() {
        return practice;
    }

    public boolean send(Channel channel, String recipient, String message) {
        Objects.requireNonNull(channel, "channel");
        validate(message, "Enter a message");
        if (channel == Channel.WHISPER) {
            validate(recipient, "Enter a whisper recipient");
        } else if (recipient != null && !recipient.isEmpty()) {
            throw new IllegalArgumentException("A recipient is only used for whispers");
        }
        String target = channel == Channel.WHISPER ? recipient : "";
        byte[] encodedRecipient = encodeField(target, "Recipient");
        byte[] encodedMessage = encodeField(message, "Message");
        if (practice) {
            receive(channel, "You", target, message);
            return true;
        }
        return sender.enqueue(new Outbound(channel, encodedRecipient, encodedMessage));
    }

    public synchronized void receive(Channel channel, String sender, String recipient, String message) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(message, "message");
        ArrayDeque<Message> messages = history.get(channel);
        if (messages.size() == MAX_HISTORY) {
            messages.removeFirst();
        }
        messages.addLast(new Message(sender, recipient == null ? "" : recipient, message));
    }

    public synchronized List<Message> messages(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        return Collections.unmodifiableList(new ArrayList<>(history.get(channel)));
    }

    private static void validate(String value, String missingMessage) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(missingMessage);
        }
        boolean hasText = false;
        for (int offset = 0; offset < value.length();) {
            int scalar = value.codePointAt(offset);
            if (Character.isISOControl(scalar) || Character.getType(scalar) == Character.FORMAT
                    || scalar == 0x2028 || scalar == 0x2029) {
                throw new IllegalArgumentException("Chat fields cannot contain control characters or line breaks");
            }
            if (!Character.isWhitespace(scalar) && !Character.isSpaceChar(scalar)) {
                hasText = true;
            }
            offset += Character.charCount(scalar);
        }
        if (!hasText) {
            throw new IllegalArgumentException(missingMessage);
        }
    }

    private static byte[] encodeField(String value, String label) {
        try {
            // JNI string access uses modified UTF-8. Pass strict UTF-8 bytes
            // explicitly so Unicode validation and byte offsets stay consistent.
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return encodeCp949Bytes(bytes);
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException(label + " contains invalid Unicode text", invalidUnicode);
        } catch (IllegalArgumentException legacyEncoding) {
            throw new IllegalArgumentException(label + ": " + legacyEncoding.getMessage(), legacyEncoding);
        }
    }

    private static native byte[] encodeCp949Bytes(byte[] utf8);
}
