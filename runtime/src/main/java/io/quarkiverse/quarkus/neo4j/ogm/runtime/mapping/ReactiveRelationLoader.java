package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import io.smallrye.mutiny.Uni;

/**
 * Reactive loader interface for loading relations of an entity.
 *
 * @param <T> entity type
 */
public interface ReactiveRelationLoader<T> {
    Uni<T> loadRelations(T entity);
}
