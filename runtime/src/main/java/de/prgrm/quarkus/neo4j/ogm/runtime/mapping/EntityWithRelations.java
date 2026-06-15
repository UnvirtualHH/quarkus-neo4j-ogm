package de.prgrm.quarkus.neo4j.ogm.runtime.mapping;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class EntityWithRelations {
    private final Class<?> entityType;
    private final Map<String, Object> properties;
    private final List<RelationshipData> relationships;

    /**
     * All relationship "type_DIRECTION" keys declared as persistable (PERSIST_ONLY /
     * FETCH_AND_PERSIST) on the entity – independent of whether the corresponding field currently
     * holds any value. This lets the repository detach relationships whose collection/field was
     * cleared (see issue #69): an emptied relation produces no {@link RelationshipData}, so without
     * this set there would be no signal to delete the existing edges.
     */
    private final Set<String> persistableRelationshipKeys;

    public EntityWithRelations(Class<?> entityType,
            Map<String, Object> properties,
            List<RelationshipData> relationships) {
        this(entityType, properties, relationships, Set.of());
    }

    public EntityWithRelations(Class<?> entityType,
            Map<String, Object> properties,
            List<RelationshipData> relationships,
            Set<String> persistableRelationshipKeys) {
        this.entityType = entityType;
        this.properties = properties;
        this.relationships = relationships;
        this.persistableRelationshipKeys = (persistableRelationshipKeys != null) ? persistableRelationshipKeys : Set.of();
    }

    public Class<?> getEntityType() {
        return entityType;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public List<RelationshipData> getRelationships() {
        return relationships;
    }

    public Set<String> getPersistableRelationshipKeys() {
        return persistableRelationshipKeys;
    }
}
