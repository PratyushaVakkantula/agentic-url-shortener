package com.agentic.shortener.service;

/**
 * Source of candidate short codes. An interface so collision handling can be tested with a
 * scripted generator (see ADR-0002 for why the production implementation is random).
 */
@FunctionalInterface
public interface ShortCodeGenerator {

    String next();
}
