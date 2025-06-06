package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util;

public record Sort(String property, boolean ascending) {
    public static Sort asc(String property) {
        return new Sort(property, true);
    }

    public static Sort desc(String property) {
        return new Sort(property, false);
    }
}