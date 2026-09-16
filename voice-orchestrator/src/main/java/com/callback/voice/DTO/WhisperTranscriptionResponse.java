package com.callback.voice.DTO;

/** Mirrors whisper.cpp server's /inference JSON response shape: {"text": "..."}. */
public record WhisperTranscriptionResponse(String text) {}
