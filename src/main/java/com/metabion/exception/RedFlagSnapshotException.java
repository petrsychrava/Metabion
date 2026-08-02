package com.metabion.exception;

public class RedFlagSnapshotException extends IllegalStateException {

    public static final String MESSAGE = "Red-flag snapshot processing failed";

    public RedFlagSnapshotException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
