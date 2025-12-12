package de.prgrm.quarkus.neo4j.ogm.runtime.repository.util;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a composable Cypher WHERE filter.
 *
 * Supports AND, OR, nested filters, and operators like:
 * =, <>, <, <=, >, >=, CONTAINS, STARTS WITH, ENDS WITH, IN, BETWEEN, IS NULL, IS NOT NULL
 *
 * String operators (EQ, NE, CONTAINS, STARTS_WITH, ENDS_WITH) are case-insensitive.
 */
public class Filter {

    private final List<Condition> conditions = new ArrayList<>();
    private final List<Filter> orGroups = new ArrayList<>();
    private final String logicalOperator;

    private Filter(String logicalOperator) {
        this.logicalOperator = logicalOperator;
    }

    public static Filter and() {
        return new Filter("AND");
    }

    public static Filter or() {
        return new Filter("OR");
    }

    public static Filter by(String property, Operator op, Object... values) {
        Filter f = new Filter("AND");
        f.conditions.add(new Condition(property, op, values));
        return f;
    }

    public Filter add(String property, Operator op, Object... values) {
        this.conditions.add(new Condition(property, op, values));
        return this;
    }

    public Filter or(Filter other) {
        this.orGroups.add(other);
        return this;
    }

    /**
     * Converts this filter to a Cypher WHERE clause and parameter map.
     */
    public CypherFragment toCypher(String alias) {
        AtomicInteger paramCounter = new AtomicInteger(0);
        String cypher = buildClause(alias, paramCounter);
        Map<String, Object> params = buildParams(paramCounter);
        if (!cypher.isBlank()) {
            cypher = "WHERE " + cypher;
        }
        return new CypherFragment(cypher, params);
    }

    private String buildClause(String alias, AtomicInteger paramCounter) {
        List<String> parts = new ArrayList<>();
        for (Condition c : conditions) {
            parts.add(c.toCypher(alias, paramCounter));
        }
        for (Filter group : orGroups) {
            parts.add("(" + group.buildClause(alias, paramCounter) + ")");
        }
        return String.join(" " + logicalOperator + " ", parts);
    }

    private Map<String, Object> buildParams(AtomicInteger counter) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Condition c : conditions) {
            params.putAll(c.toParams(counter));
        }
        for (Filter group : orGroups) {
            params.putAll(group.buildParams(counter));
        }
        return params;
    }

    /**
     * A single condition like toLower(n.name) CONTAINS toLower($param_1)
     */
    public record Condition(String property, Operator op, Object... values) {

        String toCypher(String alias, AtomicInteger counter) {
            String nodeProp = alias + "." + property;

            // Case-insensitive string operators
            boolean caseInsensitive = op == Operator.CONTAINS ||
                    op == Operator.STARTS_WITH ||
                    op == Operator.ENDS_WITH ||
                    op == Operator.EQ ||
                    op == Operator.NE;

            if (caseInsensitive) {
                return "toLower(" + nodeProp + ") "
                        + op.symbol + " toLower($" + paramName(counter, 0) + ")";
            }

            // Default operators
            return switch (op) {
                case IS_NULL -> nodeProp + " IS NULL";
                case IS_NOT_NULL -> nodeProp + " IS NOT NULL";
                case BETWEEN -> nodeProp + " >= $" + paramName(counter, 0)
                        + " AND " + nodeProp + " <= $" + paramName(counter, 1);
                case IN -> nodeProp + " IN $" + paramName(counter, 0);
                default -> nodeProp + " " + op.symbol + " $" + paramName(counter, 0);
            };
        }

        Map<String, Object> toParams(AtomicInteger counter) {
            Map<String, Object> map = new LinkedHashMap<>();
            switch (op) {
                case IS_NULL, IS_NOT_NULL -> {
                    /* no params */ }
                case BETWEEN -> {
                    map.put(paramName(counter, 0), values[0]);
                    map.put(paramName(counter, 1), values[1]);
                }
                default -> map.put(paramName(counter, 0), values[0]);
            }
            return map;
        }

        private String paramName(AtomicInteger counter, int offset) {
            return property + "_" + (counter.get() + offset);
        }
    }

    /**
     * Supported operators for Cypher WHERE clauses.
     */
    public enum Operator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        CONTAINS("CONTAINS"),
        STARTS_WITH("STARTS WITH"),
        ENDS_WITH("ENDS WITH"),
        IN("IN"),
        BETWEEN("BETWEEN"),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }
    }

    /**
     * Returned from toCypher() – both WHERE clause and param map.
     */
    public record CypherFragment(String clause, Map<String, Object> params) {
    }
}
