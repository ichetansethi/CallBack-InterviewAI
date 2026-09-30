package com.callback.session.DTO;

import com.callback.session.model.Speaker;

public record TranscriptTurnResponse(int turnIndex, Speaker speaker, String text, String questionRationale) {}
