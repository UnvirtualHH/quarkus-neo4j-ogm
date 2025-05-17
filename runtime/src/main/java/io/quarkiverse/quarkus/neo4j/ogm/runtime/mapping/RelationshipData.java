package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;

public class RelationshipData {
    private final String type;
    private final Direction direction;
    private Object targetId;
    private final Object targetEntity;

    public RelationshipData(String type, Direction direction, Object targetEntity) {
        this.type = type;
        this.direction = direction;
        this.targetEntity = targetEntity;
    }

    public String getType() {
        return type;
    }

    public Direction getDirection() {
        return direction;
    }

    public Object getTargetEntity() {
        return targetEntity;
    }

    public Object getTargetId() {
        return targetId;
    }

    public void setTargetId(Object targetId) {
        this.targetId = targetId;
    }
}
