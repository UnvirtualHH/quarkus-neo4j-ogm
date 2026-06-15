package de.prgrm.quarkus.neo4j.ogm.runtime.processor.util;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Property;

public class MapperUtil {

    /**
     * Matches a type-use annotation embedded in a {@link javax.lang.model.type.TypeMirror#toString()}
     * rendering, e.g. {@code @jakarta.validation.constraints.NotNull } in
     * {@code com.example.@jakarta.validation.constraints.NotNull Company}. Includes an optional
     * argument list and trailing whitespace so the surrounding type name is reassembled cleanly.
     */
    private static final java.util.regex.Pattern TYPE_USE_ANNOTATION = java.util.regex.Pattern
            .compile("@[\\w.]+(\\s*\\([^)]*\\))?\\s*");

    /**
     * Removes type-use annotations (e.g. {@code @NotNull}) from a type string so it can be fed to
     * {@code ClassName.bestGuess(...)} / {@code ClassName.get(...)}. Without this, fields or record
     * components annotated with a {@code @Target(TYPE_USE)} annotation produce strings like
     * {@code com.example.@jakarta.validation.constraints.NotNull Company}, which break code generation.
     */
    public static String stripAnnotations(String type) {
        if (type == null || type.indexOf('@') < 0) {
            return type;
        }
        return TYPE_USE_ANNOTATION.matcher(type).replaceAll("");
    }

    public static String capitalize(String input) {
        if (input == null || input.isEmpty())
            return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    public static String resolveSetterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    public static String resolveGetterName(VariableElement field) {
        String type = stripAnnotations(field.asType().toString());
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
        String rawType = stripAnnotations(field.asType().toString());

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
            return stripAnnotations(field.asType().toString()).equals(fqcn);
        }

        TypeElement te = elements.getTypeElement(fqcn);
        if (te == null) {
            return stripAnnotations(field.asType().toString()).equals(fqcn);
        }

        return types.isSameType(types.erasure(field.asType()), te.asType());
    }
}
