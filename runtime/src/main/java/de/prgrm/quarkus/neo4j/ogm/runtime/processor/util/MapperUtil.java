package de.prgrm.quarkus.neo4j.ogm.runtime.processor.util;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Property;

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

    public static String getFieldType(VariableElement field) {
        String rawType = field.asType().toString();

        if (rawType.startsWith("java.util.List") || rawType.startsWith("java.util.Set")) {
            int start = rawType.indexOf('<');
            int end = rawType.indexOf('>');
            if (start != -1 && end != -1 && end > start) {
                return rawType.substring(start + 1, end);
            }
        }

        return rawType;
    }

    public static boolean isOfType(VariableElement field, String fqcn, Types types, Elements elements) {
        if (field.asType().getKind().isPrimitive()) {
            return field.asType().toString().equals(fqcn);
        }

        TypeElement te = elements.getTypeElement(fqcn);
        if (te == null) {
            return field.asType().toString().equals(fqcn);
        }

        return types.isSameType(types.erasure(field.asType()), te.asType());
    }
}
