package com.wannaverse.iac;

/**
 * A problem with the configuration file or with applying one entry of it. The message is shown to
 * the person who wrote the file, so it says what is wrong and how to fix it.
 */
public class IacException extends RuntimeException {
    public IacException(String message) {
        super(message);
    }

    public IacException(String message, Throwable cause) {
        super(message, cause);
    }
}
