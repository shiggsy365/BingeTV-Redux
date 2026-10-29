package com.shiggsy365.bingetvredux.stremio;

/** An addon couldn't be reached, or answered with something that isn't usable. */
public class StremioException extends Exception {
    public StremioException(String message) {
        super(message);
    }

    public StremioException(String message, Throwable cause) {
        super(message, cause);
    }
}
