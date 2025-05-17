package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import java.util.List;
import java.util.Map;

public class EntityWithRelations {
    private final Map<String, Object> properties;
    private final List<RelationshipData> relationships;

    public EntityWithRelations(Map<String, Object> properties, List<RelationshipData> relationships) {
        this.properties = properties;
        this.relationships = relationships;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public List<RelationshipData> getRelationships() {
        return relationships;
    }
}
