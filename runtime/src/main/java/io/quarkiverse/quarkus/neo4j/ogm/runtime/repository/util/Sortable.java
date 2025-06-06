package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.util;

import java.util.List;

public record Sortable(List<Sort> orders) {
    public static Sortable by(Sort... sorts) {
        return new Sortable(List.of(sorts));
    }

    public String toCypher(String nodeAlias) {
        if (orders == null || orders.isEmpty())
            return "";
        String clause = orders.stream()
                .map(s -> nodeAlias + "." + s.property() + (s.ascending() ? " ASC" : " DESC"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "ORDER BY " + clause;
    }
}