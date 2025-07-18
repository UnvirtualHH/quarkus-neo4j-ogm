package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import io.smallrye.mutiny.Uni;

/**
 * Reactive loader interface for loading relations of an entity.
 *
 * @param <T> entity type
 */
public interface ReactiveRelationLoader<T> {
    /**
     * Load all relationships for the given entity
     *
     * @param entity The entity to load relationships for
     * @return A Uni that completes when all relationships are loaded
     */
    default Uni<T> loadRelations(T entity) {
        return loadRelations(entity, 0);
    }

    /**
     * Load relationships for the given entity up to the specified depth
     *
     * @param entity The entity to load relationships for
     * @param currentDepth The current traversal depth
     * @return A Uni that completes when all relationships are loaded
     */
    Uni<T> loadRelations(T entity, int currentDepth);
}
