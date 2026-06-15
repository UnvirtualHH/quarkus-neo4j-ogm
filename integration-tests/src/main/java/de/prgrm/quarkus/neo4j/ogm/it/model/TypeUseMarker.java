package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A type-use annotation (like {@code jakarta.validation.constraints.NotNull}) used purely to
 * reproduce issue #61: a {@code @Target(TYPE_USE)} annotation on a relationship field or a record
 * projection component must not break the annotation processor's code generation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE })
public @interface TypeUseMarker {
}
