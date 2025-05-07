package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Property;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship;

public class MapperGenerator {

    public void generateMapper(String packageName, TypeElement entityType, String mapperClassName,
            ProcessingEnvironment processingEnv) {

        String fullClassName = packageName + "." + mapperClassName;

        MethodSpec.Builder mapMethodBuilder = MethodSpec.methodBuilder("map")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("org.neo4j.driver", "Record"), "record")
                .addParameter(String.class, "alias")
                .addStatement("$T instance = new $T()", TypeName.get(entityType.asType()), TypeName.get(entityType.asType()))
                .addStatement("$T nodeValue = record.get(alias)", ClassName.get("org.neo4j.driver", "Value"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Property prop = field.getAnnotation(Property.class);
            if (prop == null)
                continue;

            String fieldName = field.getSimpleName().toString();
            String propertyName = prop.name().isEmpty() ? fieldName : prop.name();
            String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            String fieldType = field.asType().toString();

            switch (fieldType) {
                case "java.lang.String" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asString())", setterName, propertyName);
                case "int", "java.lang.Integer" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asInt())", setterName, propertyName);
                case "long", "java.lang.Long" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asLong())", setterName, propertyName);
                case "boolean", "java.lang.Boolean" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asBoolean())", setterName, propertyName);
                case "double", "java.lang.Double" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asDouble())", setterName, propertyName);
                case "float", "java.lang.Float" -> mapMethodBuilder
                        .addStatement("instance.$L((float) nodeValue.get($S).asDouble())", setterName, propertyName);
                case "java.time.LocalDate" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asLocalDate())", setterName, propertyName);
                case "java.time.LocalDateTime" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asLocalDateTime())", setterName, propertyName);
                case "java.time.OffsetDateTime" -> mapMethodBuilder
                        .addStatement("instance.$L(nodeValue.get($S).asOffsetDateTime())", setterName, propertyName);
                case "java.util.UUID" ->
                    mapMethodBuilder.addStatement("instance.$L($T.fromString(nodeValue.get($S).asString()))", setterName,
                            ClassName.get("java.util", "UUID"), propertyName);
                case "byte", "java.lang.Byte" ->
                    mapMethodBuilder.addStatement("instance.$L((byte) nodeValue.get($S).asInt())", setterName, propertyName);
                case "short", "java.lang.Short" ->
                    mapMethodBuilder.addStatement("instance.$L((short) nodeValue.get($S).asInt())", setterName, propertyName);
                case "char", "java.lang.Character" -> mapMethodBuilder
                        .addStatement("instance.$L(nodeValue.get($S).asString().charAt(0))", setterName, propertyName);
                case "java.util.List" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asList())", setterName, propertyName);
                case "java.util.Map" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asMap())", setterName, propertyName);
                case "org.neo4j.driver.types.Point" ->
                    mapMethodBuilder.addStatement("instance.$L(nodeValue.get($S).asPoint())", setterName, propertyName);
                case "io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint" -> mapMethodBuilder
                        .addStatement("$T point = nodeValue.get($S).asPoint()",
                                ClassName.get("org.neo4j.driver.types", "Point"), propertyName)
                        .addStatement(
                                "instance.$L(new io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint(point.y(), point.x()))",
                                setterName);
                default -> mapMethodBuilder.addStatement("instance.$L(($L) nodeValue.get($S).asObject())", setterName,
                        fieldType, propertyName);
            }
        }

        mapMethodBuilder.addStatement("return instance");
        MethodSpec mapMethod = mapMethodBuilder.build();
        MethodSpec getNodeIdMethod = generateGetNodeIdMethod(entityType);

        MethodSpec.Builder toDbParamsBuilder = MethodSpec.methodBuilder("toDb")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get("java.util", "Map"))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T<String, Object> params = new $T<>()",
                        ClassName.get("java.util", "Map"), ClassName.get("java.util", "HashMap"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Property prop = field.getAnnotation(Property.class);
            if (prop != null) {
                String fieldName = field.getSimpleName().toString();
                String propertyName = prop.name().isEmpty() ? fieldName : prop.name();
                String fieldType = field.asType().toString();
                String getterName = (fieldType.equals("boolean") || fieldType.equals("java.lang.Boolean"))
                        ? "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
                        : "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

                if (field.asType().getKind().isPrimitive()) {
                    if (fieldType.equals("int") || fieldType.equals("java.lang.Integer")) {
                        toDbParamsBuilder.addStatement("if (entity.$L() != 0) params.put($S, entity.$L())", getterName,
                                propertyName, getterName);
                    } else {
                        toDbParamsBuilder.addStatement("params.put($S, entity.$L())", propertyName, getterName);
                    }
                } else {
                    if (fieldType.equals("java.util.UUID")) {
                        toDbParamsBuilder.addStatement("if (entity.$L() != null) params.put($S, entity.$L().toString())",
                                getterName, propertyName, getterName);
                    } else {
                        toDbParamsBuilder.addStatement("if (entity.$L() != null) params.put($S, entity.$L())", getterName,
                                propertyName, getterName);
                    }
                }
            }
        }

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship relationship = field.getAnnotation(Relationship.class);
            if (relationship != null) {
                String fieldName = field.getSimpleName().toString();
                String relationshipType = relationship.type();
                Direction direction = relationship.direction();
                String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                String cypherDirection = (direction == Direction.OUTGOING) ? "->" : "<-";

                toDbParamsBuilder.addStatement(
                        "if (entity.$L() != null) { params.put($S, $S); }",
                        getterName,
                        relationshipType,
                        String.format("MATCH (n)-[r:%s]%s(target) MERGE r SET r = $props", relationshipType, cypherDirection));
            }
        }

        toDbParamsBuilder.addStatement("return params");
        MethodSpec toDbMethod = toDbParamsBuilder.build();

        TypeSpec mapperClass = TypeSpec.classBuilder(mapperClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ApplicationScoped.class)
                .addMethod(mapMethod)
                .addMethod(getNodeIdMethod)
                .addMethod(toDbMethod)
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "EntityMapper"),
                        TypeName.get(entityType.asType())))
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, mapperClass).build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated mapper: " + fullClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.toString());
        }
    }

    private MethodSpec generateGetNodeIdMethod(TypeElement entityType) {
        VariableElement idField = null;
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(NodeId.class) != null) {
                idField = field;
                break;
            }
        }

        if (idField == null) {
            throw new IllegalStateException("No field annotated with @NodeId found in " + entityType.getSimpleName());
        }

        String fieldName = idField.getSimpleName().toString();
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

        return MethodSpec.methodBuilder("getNodeId")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get("java.lang", "Object"))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("return entity.$L()", getterName)
                .build();
    }
}
