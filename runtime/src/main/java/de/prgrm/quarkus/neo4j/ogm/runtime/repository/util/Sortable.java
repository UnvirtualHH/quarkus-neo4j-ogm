package de.prgrm.quarkus.neo4j.ogm.runtime.repository.util;

import java.util.List;

public record Sortable(List<Sort> orders) {
    public static Sortable by(Sort... sorts) {
        return new Sortable(List.of(sorts));
    }

    public String toCypher(String nodeAlias) {
        if (orders == null || orders.isEmpty())
            return "";
        String clause = orders.stream()
                // ORDER BY fields cannot be parameterized and may originate from request input,
                // so validate them against a strict allow-list to prevent Cypher injection.
                .map(s -> nodeAlias + "." + CypherIdentifier.requireValidProperty(s.property())
                        + (s.ascending() ? " ASC" : " DESC"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "ORDER BY " + clause;
    }
}