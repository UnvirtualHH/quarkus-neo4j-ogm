package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

public interface RelationLoader<T> {
    /**
     * Load all relationships for the given entity
     * @param entity The entity to load relationships for
     */
    default void loadRelations(T entity) {
        loadRelations(entity, 0);
    }

    /**
     * Load relationships for the given entity up to the specified depth
     * @param entity The entity to load relationships for
     * @param currentDepth The current traversal depth
     */
    void loadRelations(T entity, int currentDepth);
}
