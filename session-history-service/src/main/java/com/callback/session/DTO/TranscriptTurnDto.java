package com.callback.session.DTO;

public record TranscriptTurnDto(
        String speaker, String text, String questionRationale, int turnIndex
) {}
