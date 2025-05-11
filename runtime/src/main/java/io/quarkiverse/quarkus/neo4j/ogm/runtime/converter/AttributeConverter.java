package io.quarkiverse.quarkus.neo4j.ogm.runtime.converter;

public interface AttributeConverter<EntityType, GraphType> {
    GraphType toGraphProperty(EntityType value);

    EntityType toEntityAttribute(GraphType value);
}