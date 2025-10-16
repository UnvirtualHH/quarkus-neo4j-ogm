package de.prgrm.quarkus.neo4j.ogm.runtime.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.EnumType;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.CLASS)
public @interface Enumerated {
    EnumType value() default EnumType.STRING;
}
