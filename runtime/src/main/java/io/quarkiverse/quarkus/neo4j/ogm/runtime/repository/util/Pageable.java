package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util;

public record Pageable(int page, int size) {
    public static Pageable of(int page, int size) {
        return new Pageable(page, size);
    }
}