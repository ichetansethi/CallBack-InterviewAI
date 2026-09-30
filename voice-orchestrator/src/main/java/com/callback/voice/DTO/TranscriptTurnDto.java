package com.callback.voice.DTO;

/** speaker is "CANDIDATE" or "COACH" exactly — the consumer rejects anything else. */
public record TranscriptTurnDto(String speaker, String text, String questionRationale, int turnIndex) {}
