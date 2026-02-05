package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

import java.util.List;
import java.util.Optional;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.types.*;

public class TypeHandlerRegistry {

    private static final List<TypeHandler> handlers = List.of(
            // ConverterTypeHandler must be first to check for @Convert annotation
            // before other type-based handlers
            new ConverterTypeHandler(),
            new StringTypeHandler(),
            new IntegerTypeHandler(),
            new BooleanTypeHandler(),
            new EnumTypeHandler(),
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
            new PointTypeHandler(),
            new SetTypeHandler(),
            new ByteArrayTypeHandler());

    private static Types types;
    private static Elements elements;

    public static void init(ProcessingEnvironment env) {
        types = env.getTypeUtils();
        elements = env.getElementUtils();
    }

    public static Optional<TypeHandler> findHandler(VariableElement field, Types types, Elements elements) {
        return handlers.stream().filter(h -> h.supports(field, types, elements)).findFirst();
    }

    public static Optional<TypeHandler> findHandler(String fqcn) {
        return handlers.stream().filter(h -> h.supportsType(fqcn)).findFirst();
    }
}
