package de.prgrm.quarkus.neo4j.ogm.runtime.mapping;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;

public class RelationshipData {

    private final String type;
    private final Direction direction;
    private Object targetId;
    private final EntityWithRelations target;

    public RelationshipData(String type, Direction direction, EntityWithRelations target) {
        this.type = type;
        this.direction = direction;
        this.target = target;
    }

    public RelationshipData(String type, Direction direction, Object targetId, EntityWithRelations target) {
        this.type = type;
        this.direction = direction;
        this.targetId = targetId;
        this.target = target;
    }

    public String getType() {
        return type;
    }

    public Direction getDirection() {
        return direction;
    }

    public EntityWithRelations getTarget() {
        return target;
    }

    public Object getTargetId() {
        return targetId;
    }

    public void setTargetId(Object targetId) {
        this.targetId = targetId;
    }

}
