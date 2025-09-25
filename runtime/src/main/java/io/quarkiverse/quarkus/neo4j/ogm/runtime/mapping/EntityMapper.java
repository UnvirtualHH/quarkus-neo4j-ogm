package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

public interface EntityMapper<T> {
    /**
     * Maps a Neo4j record to the entity.
     *
     * @param record The Neo4j record retrieved from the database.
     * @param alias The alias for the node in the Cypher query.
     * @return The mapped entity of type T.
     */
    T map(org.neo4j.driver.Record record, String alias);

    /**
     * Converts the entity into a map of Cypher parameters.
     *
     * @param entity The entity to convert.
     * @return A map of parameters to be used in Cypher queries.
     */
    EntityWithRelations toDb(T entity);

    /**
     * Returns the node ID of the entity.
     *
     * @param entity The entity to get the node ID from.
     * @return The node ID of the entity.
     */
    Object getNodeId(T entity);

    String getNodeIdPropertyName();

    void setRelation(T entity, String relationType, Object relatedEntity);

    default Object convertValue(Object value) {
        return value;
    }
}
