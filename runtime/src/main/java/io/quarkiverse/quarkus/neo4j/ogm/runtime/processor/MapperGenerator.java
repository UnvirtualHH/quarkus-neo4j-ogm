package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Convert;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.EnumType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;

public class MapperGenerator {

    public void generateMapper(String packageName, TypeElement entityType, String mapperClassName,
            ProcessingEnvironment processingEnv) {

        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
        MethodSpec toDbMethod = generateToDbMethod(entityType, processingEnv);
        MethodSpec getNodeIdMethod = generateGetNodeIdMethod(entityType);

        TypeSpec mapperClass = TypeSpec.classBuilder(mapperClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ApplicationScoped.class)
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "EntityMapper"),
                        TypeName.get(entityType.asType())))
                .addMethod(mapMethod)
                .addMethod(toDbMethod)
                .addMethod(getNodeIdMethod)
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, mapperClass).build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated mapper: " + packageName + "." + mapperClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.toString());
        }
    }

    private MethodSpec generateMapMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("map")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("org.neo4j.driver", "Record"), "record")
                .addParameter(String.class, "alias")
                .addStatement("$T instance = new $T()", TypeName.get(entityType.asType()), TypeName.get(entityType.asType()))
                .addStatement("$T nodeValue = record.get(alias)", ClassName.get("org.neo4j.driver", "Value"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Property prop = field.getAnnotation(Property.class);
            Enumerated enumerated = field.getAnnotation(Enumerated.class);
            NodeId nodeId = field.getAnnotation(NodeId.class);
            Convert convert = field.getAnnotation(Convert.class);
            if (prop == null && enumerated == null && nodeId == null && convert == null)
                continue;

            generateMapStatement(field, builder, env);
        }

        builder.addStatement("return instance");
        return builder.build();
    }

    private void generateMapStatement(VariableElement field, MethodSpec.Builder builder, ProcessingEnvironment env) {
        String fieldName = field.getSimpleName().toString();
        String setterName = resolveSetterName(field);
        Property prop = field.getAnnotation(Property.class);
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        NodeId nodeId = field.getAnnotation(NodeId.class);
        Convert convert = field.getAnnotation(Convert.class);

        String propertyName = (nodeId != null)
                ? fieldName
                : (prop != null && !prop.name().isEmpty()) ? prop.name() : fieldName;

        String fieldType = field.asType().toString();

        if (enumerated != null) {
            Element typeElem = env.getTypeUtils().asElement(field.asType());
            if (typeElem instanceof TypeElement te) {
                if (enumerated.value() == EnumType.ORDINAL) {
                    builder.addStatement("instance.$L($T.values()[nodeValue.get($S).asInt()])",
                            setterName, ClassName.get(te), propertyName);
                } else {
                    builder.addStatement("instance.$L($T.valueOf(nodeValue.get($S).asString()))",
                            setterName, ClassName.get(te), propertyName);
                }
                return;
            }
        }

        if (convert != null) {
            TypeMirror converterType;
            try {
                convert.value();
                throw new IllegalStateException("Expected MirroredTypeException");
            } catch (MirroredTypeException mte) {
                converterType = mte.getTypeMirror();
            }

            TypeName converterClass = TypeName.get(converterType);
            String converterVar = field.getSimpleName().toString() + "Converter";

            builder.addStatement("$T $L = new $T()", converterClass, converterVar, converterClass);
            builder.addStatement("instance.$L($L.toEntityAttribute(nodeValue.get($S).asString()))",
                    resolveSetterName(field), converterVar, propertyName);
            return;
        }

        switch (fieldType) {
            case "java.lang.String" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asString())", setterName, propertyName);
            case "java.lang.Integer", "int" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asInt())", setterName, propertyName);
            case "java.lang.Long", "long" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asLong())", setterName, propertyName);
            case "java.lang.Boolean", "boolean" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asBoolean())", setterName, propertyName);
            case "java.lang.Double", "double" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asDouble())", setterName, propertyName);
            case "java.lang.Float", "float" ->
                builder.addStatement("instance.$L((float) nodeValue.get($S).asDouble())", setterName, propertyName);
            case "java.time.Instant" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asZonedDateTime().toInstant())", setterName, propertyName);
            case "java.time.LocalDate" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asLocalDate())", setterName, propertyName);
            case "java.time.LocalDateTime" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asLocalDateTime())", setterName, propertyName);
            case "java.time.OffsetDateTime" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asOffsetDateTime())", setterName, propertyName);
            case "java.util.UUID" ->
                builder.addStatement("instance.$L($T.fromString(nodeValue.get($S).asString()))", setterName,
                        ClassName.get("java.util", "UUID"), propertyName);
            case "byte", "java.lang.Byte" ->
                builder.addStatement("instance.$L((byte) nodeValue.get($S).asInt())", setterName, propertyName);
            case "short", "java.lang.Short" ->
                builder.addStatement("instance.$L((short) nodeValue.get($S).asInt())", setterName, propertyName);
            case "char", "java.lang.Character" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asString().charAt(0))", setterName, propertyName);
            case "java.util.List" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asList())", setterName, propertyName);
            case "java.util.Map" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asMap())", setterName, propertyName);
            case "org.neo4j.driver.types.Point" ->
                builder.addStatement("instance.$L(nodeValue.get($S).asPoint())", setterName, propertyName);
            case "io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint" ->
                builder.addStatement("$T point = nodeValue.get($S).asPoint()",
                        ClassName.get("org.neo4j.driver.types", "Point"), propertyName)
                        .addStatement(
                                "instance.$L(new io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint(point.y(), point.x()))",
                                setterName);
            default ->
                builder.addStatement("instance.$L(($L) nodeValue.get($S).asObject())", setterName, fieldType, propertyName);
        }
    }

    private String resolveSetterName(VariableElement field) {
        return "set" + capitalize(field.getSimpleName().toString());
    }

    private String resolveGetterName(VariableElement field) {
        String type = field.asType().toString();
        String baseName = capitalize(field.getSimpleName().toString());
        return ("boolean".equals(type)) ? "is" + baseName : "get" + baseName;
    }

    private String capitalize(String input) {
        if (input == null || input.isEmpty())
            return input;
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }

    private MethodSpec generateToDbMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toDb")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get("java.util", "Map"))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T<String, Object> params = new $T<>()", ClassName.get("java.util", "Map"),
                        ClassName.get("java.util", "HashMap"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Property prop = field.getAnnotation(Property.class);
            Enumerated enumerated = field.getAnnotation(Enumerated.class);
            NodeId nodeId = field.getAnnotation(NodeId.class);
            Convert convert = field.getAnnotation(Convert.class);
            if (prop == null && enumerated == null && nodeId == null && convert == null)
                continue;

            generateToDbStatement(field, builder, env);
        }

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship relationship = field.getAnnotation(Relationship.class);
            if (relationship != null) {
                String relationshipType = relationship.type();
                Direction direction = relationship.direction();
                String getterName = resolveGetterName(field);
                String cypherDirection = (direction == Direction.OUTGOING) ? "->" : "<-";

                builder.addStatement("if (entity.$L() != null) { params.put($S, $S); }",
                        getterName,
                        relationshipType,
                        String.format("MATCH (n)-[r:%s]%s(target) MERGE r SET r = $props", relationshipType, cypherDirection));
            }
        }

        builder.addStatement("return params");
        return builder.build();
    }

    private void generateToDbStatement(VariableElement field, MethodSpec.Builder builder, ProcessingEnvironment env) {
        String fieldName = field.getSimpleName().toString();
        Property prop = field.getAnnotation(Property.class);
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        NodeId nodeId = field.getAnnotation(NodeId.class);
        Convert convert = field.getAnnotation(Convert.class);
        String propertyName = (nodeId != null) ? fieldName : (prop != null && !prop.name().isEmpty()) ? prop.name() : fieldName;

        String fieldType = field.asType().toString();
        String getterName = resolveGetterName(field);

        if (enumerated != null) {
            String method = enumerated.value() == EnumType.ORDINAL ? "ordinal" : "name";
            builder.addStatement("if (entity.$L() != null) params.put($S, entity.$L().$L())",
                    getterName, propertyName, getterName, method);
            return;
        }

        if (convert != null) {

            TypeMirror converterType;
            try {
                convert.value();
                throw new IllegalStateException("Expected MirroredTypeException");
            } catch (MirroredTypeException mte) {
                converterType = mte.getTypeMirror();
            }

            TypeName converterClass = TypeName.get(converterType);
            String converterVar = field.getSimpleName().toString() + "Converter";

            builder.addStatement("$T $L = new $T()", converterClass, converterVar, converterClass);
            builder.addStatement("params.put($S, $L.toGraphProperty(entity.$L()))", propertyName, converterVar, getterName);
            return;
        }

        if (field.asType().getKind().isPrimitive()) {
            if (fieldType.equals("int") || fieldType.equals("java.lang.Integer")) {
                builder.addStatement("if (entity.$L() != 0) params.put($S, entity.$L())", getterName, propertyName, getterName);
            } else {
                builder.addStatement("params.put($S, entity.$L())", propertyName, getterName);
            }
        } else {
            if (fieldType.equals("java.util.UUID")) {
                builder.addStatement("if (entity.$L() != null) params.put($S, entity.$L().toString())", getterName,
                        propertyName, getterName);
            } else {
                builder.addStatement("if (entity.$L() != null) params.put($S, entity.$L())",
                        getterName, propertyName, getterName);
            }
        }
    }

    private MethodSpec generateGetNodeIdMethod(TypeElement entityType) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(NodeId.class) != null) {
                String fieldName = field.getSimpleName().toString();
                String getterName = "get" + capitalize(fieldName);
                return MethodSpec.methodBuilder("getNodeId")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ClassName.get("java.lang", "Object"))
                        .addParameter(TypeName.get(entityType.asType()), "entity")
                        .addStatement("return entity.$L()", getterName)
                        .build();
            }
        }
        throw new IllegalStateException("No field annotated with @NodeId found in " + entityType.getSimpleName());
    }

}
