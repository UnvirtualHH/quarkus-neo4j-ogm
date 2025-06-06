package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util;

import java.util.List;

public record Paged<T>(
        List<T> content,
        long totalElements,
        int page,
        int size) {
    public int totalPages() {
        return (int) Math.ceil((double) totalElements / size);
    }
}