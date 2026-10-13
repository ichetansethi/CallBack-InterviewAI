package com.callback.compatibility.model;

public enum AnalysisStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED;

    /** A terminal analysis never changes again; a redelivered event for it is skipped. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
