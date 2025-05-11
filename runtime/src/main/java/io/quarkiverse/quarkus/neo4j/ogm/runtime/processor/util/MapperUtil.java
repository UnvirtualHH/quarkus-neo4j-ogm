package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util;

import javax.lang.model.element.VariableElement;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Property;

public class MapperUtil {
    public static String capitalize(String input) {
        if (input == null || input.isEmpty())
            return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    public static String resolveSetterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    public static String resolveGetterName(VariableElement field) {
        String type = field.asType().toString();
        String base = capitalize(field.getSimpleName().toString());
        return ("boolean".equals(type)) ? "is" + base : "get" + base;
    }

    public static String getPropertyName(VariableElement field) {
        Property prop = field.getAnnotation(Property.class);
        NodeId nodeId = field.getAnnotation(NodeId.class);
        return (nodeId != null)
                ? field.getSimpleName().toString()
                : (prop != null && !prop.name().isEmpty()) ? prop.name() : field.getSimpleName().toString();
    }
}
