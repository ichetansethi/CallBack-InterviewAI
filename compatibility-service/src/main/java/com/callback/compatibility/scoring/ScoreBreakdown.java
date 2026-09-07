package com.callback.compatibility.scoring;

import java.util.List;

public record ScoreBreakdown(
        int skillsOverlap, int experienceMatch,
        int keywordCoverage, int semanticSimilarity,
        List<TweakSuggestion> suggestions
) {}
