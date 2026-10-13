package com.callback.compatibility.DTO;

import com.callback.compatibility.model.AnalysisStatus;

import java.util.UUID;

/** Returned with 202 by POST /compatibility/analyze; poll GET /compatibility/{analysisId} for the result. */
public record AnalysisAcceptedResponse(UUID analysisId, AnalysisStatus status) {}
