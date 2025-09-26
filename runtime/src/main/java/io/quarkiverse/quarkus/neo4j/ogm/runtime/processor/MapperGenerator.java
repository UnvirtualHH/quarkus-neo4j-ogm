package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.config.FieldMappingStrategy;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Convert;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class MapperGenerator {

    private final FieldMappingStrategy strategy;

    public MapperGenerator(FieldMappingStrategy strategy) {
        this.strategy = strategy;
    }

    public void generateMapper(String packageName, TypeElement entityType, String mapperClassName,
            ProcessingEnvironment processingEnv) {
        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
        MethodSpec toDbMethod = generateToDbMethod(entityType, processingEnv);
        MethodSpec getNodeIdMethod = generateGetNodeIdMethod(entityType);
        MethodSpec getNodeIdPropertyNameMethod = generateGetNodeIdPropertyName(entityType);

        TypeSpec mapperClass = TypeSpec.classBuilder(mapperClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "EntityMapper"),
                        TypeName.get(entityType.asType())))
                .addField(FieldSpec.builder(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "EntityMapperRegistry"),
                        "registry",
                        Modifier.PRIVATE)
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .build())
                .addMethod(mapMethod)
                .addMethod(toDbMethod)
                .addMethod(getNodeIdMethod)
                .addMethod(getNodeIdPropertyNameMethod)
                .addMethod(generateSetRelationMethod(entityType))
                .addMethod(generateRegisterSelfMethod(entityType))
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

    private boolean shouldFetchRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.FETCH_ONLY ||
                rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
    }

    private boolean shouldPersistRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.PERSIST_ONLY ||
                rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
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
            generateMapStatement(field, builder);
        }

        builder.addStatement("return instance");
        return builder.build();
    }

    private void generateMapStatement(VariableElement field, MethodSpec.Builder builder) {
        TypeHandlerRegistry.findHandler(field)
                .ifPresent(handler -> builder.addCode(handler.generateSetterCode(field, "instance", "nodeValue")));
    }

    private MethodSpec generateToDbMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toDb")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "EntityWithRelations"))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T properties = new $T<>()",
                        ParameterizedTypeName.get(Map.class, String.class, Object.class),
                        ClassName.get("java.util", "HashMap"))
                .addStatement("$T relationships = new $T<>()",
                        ParameterizedTypeName.get(
                                ClassName.get(List.class),
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "RelationshipData")),
                        ClassName.get("java.util", "ArrayList"));

        // Properties
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (!shouldIncludeField(field))
                continue;
            TypeHandlerRegistry.findHandler(field)
                    .ifPresent(handler -> builder.addCode(handler.generateToDbCode(field, "entity", "properties")));
        }

        // Relationships (persist only)
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship relationship = field.getAnnotation(Relationship.class);
            if (relationship != null && shouldPersistRelationship(relationship)) {
                String getterName = MapperUtil.resolveGetterName(field);
                String relType = relationship.type();
                Direction direction = relationship.direction();
                String targetTypeName = MapperUtil.getFieldType(field);
                boolean isCollection = field.asType().toString().startsWith("java.util.List");

                if (isCollection) {
                    builder.beginControlFlow("if (entity.$L() != null)", getterName)
                            .beginControlFlow("for (var related : entity.$L())", getterName)
                            .addStatement(
                                    "$T relatedWithRels = registry.get($T.class).toDb(related)",
                                    ClassName.get(EntityWithRelations.class),
                                    ClassName.bestGuess(targetTypeName))
                            .addStatement(
                                    "Object targetId = registry.get($T.class).getNodeId(related)",
                                    ClassName.bestGuess(targetTypeName))
                            .addStatement(
                                    "relationships.add(new $T($S, $T.$L, targetId.toString(), relatedWithRels))",
                                    ClassName.get(RelationshipData.class),
                                    relType,
                                    ClassName.get(Direction.class),
                                    direction.name())
                            .endControlFlow()
                            .endControlFlow();
                } else {
                    builder.beginControlFlow("if (entity.$L() != null)", getterName)
                            .addStatement(
                                    "$T relatedWithRels = registry.get($T.class).toDb(entity.$L())",
                                    ClassName.get(EntityWithRelations.class),
                                    ClassName.bestGuess(targetTypeName),
                                    getterName)
                            .addStatement(
                                    "Object targetId = registry.get($T.class).getNodeId(entity.$L())",
                                    ClassName.bestGuess(targetTypeName),
                                    getterName)
                            .addStatement(
                                    "relationships.add(new $T($S, $T.$L, targetId.toString(), relatedWithRels))",
                                    ClassName.get(RelationshipData.class),
                                    relType,
                                    ClassName.get(Direction.class),
                                    direction.name())
                            .endControlFlow();
                }

            }
        }

        builder.addStatement("return new $T($T.class, properties, relationships)",
                ClassName.get(EntityWithRelations.class),
                TypeName.get(entityType.asType()));

        return builder.build();
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
                        .returns(Object.class)
                        .addParameter(TypeName.get(entityType.asType()), "entity");

                if (isGenerated && isUUID) {
                    methodBuilder.addStatement("return entity.$L() != null ? entity.$L().toString() : null",
                            getterName, getterName);
                } else {
                    methodBuilder.addStatement("return entity.$L()", getterName);
                }
                return methodBuilder.build();
            }
        }
        throw new IllegalStateException("No field annotated with @NodeId in " + entityType.getSimpleName());
    }

    private MethodSpec generateGetNodeIdPropertyName(TypeElement entityType) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(NodeId.class) != null) {
                return MethodSpec.methodBuilder("getNodeIdPropertyName")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(String.class)
                        .addStatement("return $S", field.getSimpleName().toString())
                        .build();
            }
        }
        throw new IllegalStateException("No field annotated with @NodeId in " + entityType.getSimpleName());
    }

    private MethodSpec generateSetRelationMethod(TypeElement entityType) {
        List<VariableElement> relationshipFields = ElementFilter.fieldsIn(entityType.getEnclosedElements())
                .stream()
                .filter(f -> {
                    Relationship rel = f.getAnnotation(Relationship.class);
                    return rel != null && shouldFetchRelationship(rel);
                })
                .toList();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("setRelation")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addParameter(String.class, "relationType")
                .addParameter(Object.class, "relatedEntity");

        if (relationshipFields.isEmpty()) {
            builder.addStatement("// no fetchable relationships");
            return builder.build();
        }

        builder.beginControlFlow("switch (relationType)");
        for (VariableElement field : relationshipFields) {
            Relationship rel = field.getAnnotation(Relationship.class);
            String relType = rel.type();
            String setterName = MapperUtil.resolveSetterName(field);
            String getterName = MapperUtil.resolveGetterName(field);
            boolean isCollection = field.asType().toString().startsWith("java.util.List");
            String targetTypeName = MapperUtil.getFieldType(field);

            builder.beginControlFlow("case $S ->", relType);
            if (isCollection) {
                builder.beginControlFlow("if (entity.$L() == null)", getterName)
                        .addStatement("entity.$L(new $T<>())", setterName, ClassName.get("java.util", "ArrayList"))
                        .endControlFlow()
                        .addStatement("entity.$L().add(($T) relatedEntity)",
                                getterName, ClassName.bestGuess(targetTypeName));
            } else {
                builder.addStatement("entity.$L(($T) relatedEntity)",
                        setterName, ClassName.bestGuess(targetTypeName));
            }
            builder.addStatement("break");
            builder.endControlFlow();
        }
        builder.beginControlFlow("default ->")
                .addStatement("// ignore unknown relation")
                .addStatement("break")
                .endControlFlow();
        builder.endControlFlow();

        return builder.build();
    }

    private MethodSpec generateRegisterSelfMethod(TypeElement entityType) {
        return MethodSpec.methodBuilder("registerSelf")
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addStatement("registry.registerSelf($T.class, this)", TypeName.get(entityType.asType()))
                .build();
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
