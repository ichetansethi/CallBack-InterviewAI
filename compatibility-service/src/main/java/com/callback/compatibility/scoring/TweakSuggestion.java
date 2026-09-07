package com.callback.compatibility.scoring;

/**
 * One actionable resume tweak, grounded in a specific JD requirement that retrieval found weak
 * or missing evidence for.
 */
public record TweakSuggestion(String jdRequirement, String suggestion) {}
