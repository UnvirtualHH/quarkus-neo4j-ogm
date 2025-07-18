package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Relationship {
    String type();

    Direction direction() default Direction.OUTGOING;

    /**
     * Controls how the relationship is handled during operations
     */
    RelationshipMode mode() default RelationshipMode.FETCH_AND_PERSIST;

    /**
     * Maximum depth for loading relationships to prevent circular references
     * -1 means no limit (use with caution)
     * 0 means don't load relationships
     * 1+ means load up to that depth
     */
    int maxDepth() default 3;
}
