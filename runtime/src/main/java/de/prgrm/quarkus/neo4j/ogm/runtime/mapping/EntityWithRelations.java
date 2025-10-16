package de.prgrm.quarkus.neo4j.ogm.runtime.mapping;

import java.util.List;
import java.util.Map;

public class EntityWithRelations {
    private final Class<?> entityType;
    private final Map<String, Object> properties;
    private final List<RelationshipData> relationships;

    public EntityWithRelations(Class<?> entityType,
            Map<String, Object> properties,
            List<RelationshipData> relationships) {
        this.entityType = entityType;
        this.properties = properties;
        this.relationships = relationships;
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
}
