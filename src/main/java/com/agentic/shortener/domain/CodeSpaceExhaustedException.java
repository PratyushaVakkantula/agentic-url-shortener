package com.agentic.shortener.domain;

/**
 * Could not find a free code within the retry budget. With a 62^7 code space this should
 * be practically impossible; if it happens, it signals a broken generator or a nearly full
 * code space, and it must be loud (5xx + alert), not silently retried forever.
 */
public class CodeSpaceExhaustedException extends ShortenerException {

    public CodeSpaceExhaustedException(int attempts) {
        super("CODE_GENERATION_FAILED", "Could not allocate a unique short code after " + attempts + " attempts.");
    }
}
