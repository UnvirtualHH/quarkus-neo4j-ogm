package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.util.List;
import java.util.Optional;

import javax.lang.model.element.VariableElement;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types.*;

public class TypeHandlerRegistry {

    private static final List<TypeHandler> handlers = List.of(
            new StringTypeHandler(),
            new IntegerTypeHandler(),
            new BooleanTypeHandler(),
            new EnumTypeHandler(),
            new ConverterTypeHandler(),
            new CharTypeHandler(),
            new FloatTypeHandler(),
            new ListTypeHandler(),
            new MapTypeHandler(),
            new LocalDateTimeTypeHandler(),
            new InstantTypeHandler(),
            new LocalDateTypeHandler(),
            new UUIDTypeHandler(),
            new OffsetDateTimeTypeHandler(),
            new GeoPointTypeHandler(),
            new PointTypeHandler());

    public static Optional<TypeHandler> findHandler(VariableElement field) {
        return handlers.stream().filter(h -> h.supports(field)).findFirst();
    }
}
