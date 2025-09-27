package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.repository.ReactiveRelationVisitor;
import io.smallrye.mutiny.Uni;

/**
 * Reactive loader interface for loading relations of an entity.
 *
 * @param <T> entity type
 */
public interface ReactiveRelationLoader<T> {

    /**
     * Load all relationships for the given entity with a fresh context.
     * Normally only used for simple cases – repositories should prefer
     * {@link #loadRelations(Object, int, ReactiveRelationVisitor.VisitorContext)}.
     *
     * @param entity The entity to load relationships for
     * @return A Uni that completes when all relationships are loaded
     */
    default Uni<T> loadRelations(T entity) {
        return loadRelations(entity, 0, new ReactiveRelationVisitor().newContext());
    }

    /**
     * Load relationships for the given entity up to the specified depth,
     * using the provided traversal context.
     *
     * @param entity The entity to load relationships for
     * @param currentDepth The current traversal depth
     * @param ctx Shared traversal context (must be the same for the whole operation)
     * @return A Uni that completes when all relationships are loaded
     */
    Uni<T> loadRelations(T entity, int currentDepth, ReactiveRelationVisitor.VisitorContext ctx);
}