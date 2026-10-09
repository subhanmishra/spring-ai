package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * What a question asks for - the "task" every metric is broken down by.
 *
 * <p>A closed set on purpose: it is a Prometheus tag, so it must not grow with traffic, and five is as many
 * as a dashboard row can compare. The {@link #word} is the classifier's one-word reply.
 */
public enum TaskType {

    /** "What property sets the management port?" - a specific setting, name or value. */
    CONFIG_LOOKUP("config"),

    /** "How do I build an executable jar?" - steps to accomplish something. */
    HOW_TO("howto"),

    /** "Why does my app fail to start with ...?" - diagnosing a failure. */
    TROUBLESHOOTING("troubleshoot"),

    /** "What does the starter parent provide?" - explaining what something is or why. */
    CONCEPTUAL("concept"),

    /** Greetings, chit-chat, and questions with nothing to do with the documents. */
    GENERAL("general");

    private final String word;

    TaskType(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }

    /** The tag value: lower case with hyphens, {@code config-lookup}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The type whose word starts the reply, or null. */
    public static @Nullable TaskType fromReply(@Nullable String reply) {
        if (reply == null) {
            return null;
        }
        String normalized = reply.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", " ").strip();
        for (TaskType type : values()) {
            if (normalized.startsWith(type.word)) {
                return type;
            }
        }
        return null;
    }

    /** Parses a stored tag back, or null. */
    public static @Nullable TaskType fromTag(@Nullable String tag) {
        if (tag == null) {
            return null;
        }
        for (TaskType type : values()) {
            if (type.tag().equals(tag)) {
                return type;
            }
        }
        return null;
    }
}
