package com.callback.voice.tts;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Splits an already-complete string into sentences, for TTS pipelining when no streaming source is available. */
public final class SentenceSplitter {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.?!])\\s+");

    private SentenceSplitter() {
    }

    public static List<String> split(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(SENTENCE_BOUNDARY.split(trimmed))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

}
