package com.callback.compatibility.scoring;

import java.util.List;

/** One JD requirement paired with the resume chunks retrieval found for it (possibly none/weak). */
public record RequirementEvidence(String requirement, List<String> evidenceChunks) {}
