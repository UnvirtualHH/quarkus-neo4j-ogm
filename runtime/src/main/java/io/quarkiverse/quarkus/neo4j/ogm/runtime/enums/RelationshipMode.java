package io.quarkiverse.quarkus.neo4j.ogm.runtime.enums;

/**
 * Defines how relationships are handled during OGM operations
 */
public enum RelationshipMode {
    /**
     * Only fetch the relationship data, don't persist changes
     */
    FETCH_ONLY,

    /**
     * Only persist the relationship data, don't fetch automatically
     */
    PERSIST_ONLY,

    /**
     * Both fetch and persist the relationship data (default)
     */
    FETCH_AND_PERSIST,

    /**
     * Neither fetch nor persist - relationship is for design only
     */
    DESIGN_ONLY
}