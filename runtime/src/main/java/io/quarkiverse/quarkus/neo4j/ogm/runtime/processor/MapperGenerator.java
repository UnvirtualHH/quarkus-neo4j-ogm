package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.config.FieldMappingStrategy;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Convert;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class MapperGenerator {

    private FieldMappingStrategy strategy;

    public MapperGenerator(FieldMappingStrategy strategy) {
        this.strategy = strategy;
    }

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
            if (!shouldIncludeField(field))
                continue;

            generateMapStatement(field, builder, env);
        }

        builder.addStatement("return instance");
        return builder.build();
    }

    private void generateMapStatement(VariableElement field, MethodSpec.Builder builder, ProcessingEnvironment env) {
        TypeHandlerRegistry.findHandler(field).ifPresent(handler -> {
            builder.addCode(handler.generateSetterCode(field, "instance", "nodeValue"));
        });
    }

    private ParameterizedTypeName stringObjectGenericType(final String simpleName) {
        return ParameterizedTypeName.get(
                ClassName.get("java.util", simpleName),
                ClassName.get(String.class),
                ClassName.get(Object.class));
    }

    private MethodSpec generateToDbMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toDb")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(stringObjectGenericType("Map"))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T params = new $T()", stringObjectGenericType("HashMap"), stringObjectGenericType("HashMap"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (!shouldIncludeField(field))
                continue;

            generateToDbStatement(field, builder, env);
        }

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship relationship = field.getAnnotation(Relationship.class);
            if (relationship != null) {
                String relationshipType = relationship.type();
                Direction direction = relationship.direction();
                String getterName = MapperUtil.resolveGetterName(field);
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
        TypeHandlerRegistry.findHandler(field).ifPresent(handler -> {
            builder.addCode(handler.generateToDbCode(field, "entity"));
        });
    }

    private MethodSpec generateGetNodeIdMethod(TypeElement entityType) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(NodeId.class) != null) {
                String getterName = MapperUtil.resolveGetterName(field);
                boolean isGenerated = field.getAnnotation(GeneratedValue.class) != null;
                boolean isUUID = field.asType().toString().equals("java.util.UUID");

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getNodeId")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ClassName.get("java.lang", "Object"))
                        .addParameter(TypeName.get(entityType.asType()), "entity");

                if (isGenerated && isUUID) {
                    methodBuilder.addStatement("return entity.$L() != null ? entity.$L().toString() : null", getterName,
                            getterName);
                } else {
                    methodBuilder.addStatement("return entity.$L()", getterName);
                }

                return methodBuilder.build();
            }
        }
        throw new IllegalStateException("No field annotated with @NodeId found in " + entityType.getSimpleName());
    }

    private boolean shouldIncludeField(VariableElement field) {
        if (strategy == FieldMappingStrategy.EXPLICIT) {
            return field.getAnnotation(Property.class) != null
                    || field.getAnnotation(NodeId.class) != null
                    || field.getAnnotation(Convert.class) != null
                    || field.getAnnotation(Enumerated.class) != null;
        } else {
            return field.getAnnotation(Transient.class) == null
                    && TypeHandlerRegistry.findHandler(field).isPresent();
        }
    }

}
