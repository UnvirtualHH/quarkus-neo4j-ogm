package de.prgrm.quarkus.neo4j.ogm.runtime.enums;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import de.prgrm.quarkus.neo4j.ogm.runtime.converter.AttributeConverter;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Convert {
    Class<? extends AttributeConverter<?, ?>> value();
}
