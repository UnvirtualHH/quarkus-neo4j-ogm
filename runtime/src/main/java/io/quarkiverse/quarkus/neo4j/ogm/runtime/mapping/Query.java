package io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.ReturnType;

@Retention(RetentionPolicy.CLASS) //
@Target(ElementType.TYPE)
public @interface Query {
    String name();

    String cypher();

    ReturnType returnType() default ReturnType.SINGLE;

    boolean transactional() default false;
}
