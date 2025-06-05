package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

public interface RelationLoader<T> {
    void loadRelations(T entity);
}
