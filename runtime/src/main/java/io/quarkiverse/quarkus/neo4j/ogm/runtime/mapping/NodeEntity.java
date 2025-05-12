package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.config.FieldMappingStrategy;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NodeEntity {
    String label() default "";

    /**
     * Controls which fields should be mapped in the generated mapper.
     */
    FieldMappingStrategy fieldMappingStrategy() default FieldMappingStrategy.IMPLICIT;
}
