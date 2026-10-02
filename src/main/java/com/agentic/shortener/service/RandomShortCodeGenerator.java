package com.agentic.shortener.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Uniformly random base62 codes from a CSPRNG. Unpredictable, so links cannot be enumerated
 * (NFR-2). Uniqueness is not guaranteed here; the database constraint plus bounded retries
 * in {@link ShortLinkService} handle the (rare) collision.
 */
@Component
public class RandomShortCodeGenerator implements ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public RandomShortCodeGenerator(ShortenerProperties properties) {
        this.length = properties.codeLength();
    }

    @Override
    public String next() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is unbiased, unlike random.nextInt() % 62.
            code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(code);
    }
}
