package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import jakarta.enterprise.context.ApplicationScoped;

import com.palantir.javapoet.*;

import de.prgrm.quarkus.neo4j.ogm.runtime.config.FieldMappingStrategy;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Convert;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class MapperGenerator {

    private final FieldMappingStrategy strategy;

    public MapperGenerator(FieldMappingStrategy strategy) {
        this.strategy = strategy;
    }

    // ======================================================================
    // Entry
    // ======================================================================

    public void generateMapper(
            String packageName,
            TypeElement entityType,
            String mapperClassName,
            ProcessingEnvironment processingEnv) {

        TypeHandlerRegistry.init(processingEnv);

        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv);
        MethodSpec toDbMethod = generateToDbMethod(entityType, processingEnv);
        MethodSpec getNodeIdMethod = generateGetNodeIdMethod(entityType);
        MethodSpec getNodeIdPropertyNameMethod = generateGetNodeIdPropertyName(entityType);
        MethodSpec setRelationMethod = generateSetRelationMethod(entityType);
        MethodSpec registerSelfMethod = generateRegisterSelfMethod(entityType);
        MethodSpec applyPostLoadConvertersMethod = generateApplyPostLoadConvertersMethod(entityType, processingEnv);

        TypeSpec.Builder mapperBuilder = TypeSpec.classBuilder(mapperClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(EntityMapper.class),
                        TypeName.get(entityType.asType())))
                .addField(FieldSpec.builder(
                        ClassName.get(EntityMapperRegistry.class),
                        "registry",
                        Modifier.PRIVATE)
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .build())
                .addMethod(mapMethod)
                .addMethod(toDbMethod)
                .addMethod(getNodeIdMethod)
                .addMethod(getNodeIdPropertyNameMethod)
                .addMethod(setRelationMethod)
                .addMethod(registerSelfMethod);

        // Only add applyPostLoadConverters if it's not empty
        if (applyPostLoadConvertersMethod != null) {
            mapperBuilder.addMethod(applyPostLoadConvertersMethod);
        }

        TypeSpec mapperClass = mapperBuilder.build();

        try {
            JavaFile.builder(packageName, mapperClass)
                    .build()
                    .writeTo(processingEnv.getFiler());

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Generated mapper: " + packageName + "." + mapperClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    e.toString());
        }
    }

    // ======================================================================
    // map()
    // ======================================================================

    private MethodSpec generateMapMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("map")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("org.neo4j.driver", "Record"), "record")
                .addParameter(String.class, "alias")
                .addStatement("$T instance = new $T()",
                        TypeName.get(entityType.asType()),
                        TypeName.get(entityType.asType()))
                .addStatement("$T nodeValue = record.get(alias)",
                        ClassName.get("org.neo4j.driver", "Value"));

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (!shouldIncludeField(field, env))
                continue;

            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field, env.getTypeUtils(), env.getElementUtils());

            if (handler.isPresent()) {
                String name = field.getSimpleName().toString();
                b.beginControlFlow(
                        "if (nodeValue.get($S) != null && !nodeValue.get($S).isNull())",
                        name, name);
                b.addCode(handler.get().generateSetterCode(field, "instance", "nodeValue"));
                b.endControlFlow();
            }
        }

        b.addStatement("return instance");
        return b.build();
    }

    // ======================================================================
    // toDb()
    // ======================================================================

    private MethodSpec generateToDbMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("toDb")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(EntityWithRelations.class))
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addStatement("$T<String,Object> properties = new $T<>()",
                        Map.class, java.util.HashMap.class)
                .addStatement("$T<$T> relationships = new $T<>()",
                        List.class, RelationshipData.class, java.util.ArrayList.class);

        // Properties
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (!shouldIncludeField(field, env))
                continue;

            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field, env.getTypeUtils(), env.getElementUtils());

            handler.ifPresent(h -> b.addCode(h.generateToDbCode(field, "entity", "properties")));
        }

        // Relationships
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || !shouldPersistRelationship(rel))
                continue;

            String getter = MapperUtil.resolveGetterName(field);
            String targetType = MapperUtil.getFieldType(field);
            boolean isCollection = field.asType().toString().startsWith("java.util.List");

            if (isCollection) {
                b.beginControlFlow("if (entity.$L() != null)", getter)
                        .beginControlFlow("for (var related : entity.$L())", getter)
                        .addCode(buildRelationshipAddCode(rel, targetType, "related"))
                        .endControlFlow()
                        .endControlFlow();
            } else {
                b.beginControlFlow("if (entity.$L() != null)", getter)
                        .addCode(buildRelationshipAddCode(rel, targetType, "entity." + getter + "()"))
                        .endControlFlow();
            }
        }

        b.addStatement("return new $T($T.class, properties, relationships)",
                EntityWithRelations.class,
                TypeName.get(entityType.asType()));

        return b.build();
    }

    private CodeBlock buildRelationshipAddCode(
            Relationship rel,
            String targetType,
            String accessExpr) {

        CodeBlock.Builder cb = CodeBlock.builder();

        cb.addStatement(
                "$T relatedWithRels = null",
                EntityWithRelations.class);

        cb.beginControlFlow(
                "if ($T.$L == $T.PERSIST_ONLY || $T.$L == $T.FETCH_AND_PERSIST)",
                RelationshipMode.class,
                rel.mode().name(),
                RelationshipMode.class,
                RelationshipMode.class,
                rel.mode().name(),
                RelationshipMode.class);

        cb.addStatement(
                "relatedWithRels = registry.get($T.class).toDb($L)",
                ClassName.bestGuess(targetType),
                accessExpr);

        cb.endControlFlow();

        cb.addStatement(
                "Object targetId = registry.get($T.class).getNodeId($L)",
                ClassName.bestGuess(targetType),
                accessExpr);

        cb.addStatement(
                "relationships.add(new $T($S, $T.$L, $T.$L, targetId, relatedWithRels))",
                RelationshipData.class,
                rel.type(),
                Direction.class,
                rel.direction().name(),
                RelationshipMode.class,
                rel.mode().name());

        return cb.build();
    }

    // ======================================================================
    // NodeId
    // ======================================================================

    private MethodSpec generateGetNodeIdMethod(TypeElement entityType) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(NodeId.class) == null)
                continue;

            String getter = MapperUtil.resolveGetterName(field);
            String type = field.asType().toString();

            MethodSpec.Builder b = MethodSpec.methodBuilder("getNodeId")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(Object.class)
                    .addParameter(TypeName.get(entityType.asType()), "entity");

            if (type.equals("java.util.UUID")) {
                b.addStatement("return entity.$L() != null ? entity.$L().toString() : null", getter, getter);
            } else {
                b.addStatement("return entity.$L()", getter);
            }
            return b.build();
        }

        throw new IllegalStateException("No @NodeId field in " + entityType.getSimpleName());
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
        throw new IllegalStateException("No @NodeId field in " + entityType.getSimpleName());
    }

    // ======================================================================
    // setRelation (fetch side)
    // ======================================================================

    private MethodSpec generateSetRelationMethod(TypeElement entityType) {
        List<VariableElement> relFields = ElementFilter.fieldsIn(entityType.getEnclosedElements())
                .stream()
                .filter(f -> {
                    Relationship r = f.getAnnotation(Relationship.class);
                    return r != null && shouldFetchRelationship(r);
                })
                .toList();

        MethodSpec.Builder b = MethodSpec.methodBuilder("setRelation")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(entityType.asType()), "entity")
                .addParameter(String.class, "relationType")
                .addParameter(Object.class, "relatedEntity");

        if (relFields.isEmpty()) {
            b.addStatement("// no relations");
            return b.build();
        }

        b.beginControlFlow("switch (relationType)");
        for (VariableElement field : relFields) {
            Relationship rel = field.getAnnotation(Relationship.class);
            String setter = MapperUtil.resolveSetterName(field);
            String getter = MapperUtil.resolveGetterName(field);
            boolean isCollection = field.asType().toString().startsWith("java.util.List");
            String targetType = MapperUtil.getFieldType(field);

            b.beginControlFlow("case $S ->", rel.type());
            if (isCollection) {
                b.beginControlFlow("if (entity.$L() == null)", getter)
                        .addStatement("entity.$L(new $T<>())", setter, java.util.ArrayList.class)
                        .endControlFlow()
                        .addStatement("entity.$L().add(($T) relatedEntity)", getter, ClassName.bestGuess(targetType));
            } else {
                b.addStatement("entity.$L(($T) relatedEntity)", setter, ClassName.bestGuess(targetType));
            }
            b.addStatement("break");
            b.endControlFlow();
        }
        b.beginControlFlow("default ->").addStatement("break").endControlFlow();
        b.endControlFlow();

        return b.build();
    }

    private MethodSpec generateRegisterSelfMethod(TypeElement entityType) {
        return MethodSpec.methodBuilder("registerSelf")
                .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                .addStatement("registry.registerSelf($T.class, this)", TypeName.get(entityType.asType()))
                .build();
    }

    // ======================================================================
    // applyPostLoadConverters()
    // ======================================================================

    private MethodSpec generateApplyPostLoadConvertersMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("applyPostLoadConverters")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.get(entityType.asType()), "entity");

        boolean hasPostLoadConverters = false;

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (!shouldIncludeField(field, env))
                continue;

            Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field, env.getTypeUtils(), env.getElementUtils());

            if (handler.isPresent()) {
                CodeBlock postLoadCode = handler.get().generatePostLoadConverterCode(field, "entity");
                if (postLoadCode != null) {
                    b.addCode(postLoadCode);
                    hasPostLoadConverters = true;
                }
            }
        }

        // Only return the method if there are context-aware converters
        return hasPostLoadConverters ? b.build() : null;
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private boolean shouldFetchRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.FETCH_ONLY
                || rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
    }

    private boolean shouldPersistRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.PERSIST_ONLY
                || rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
    }

    private boolean shouldIncludeField(VariableElement field, ProcessingEnvironment env) {
        if (strategy == FieldMappingStrategy.EXPLICIT) {
            return field.getAnnotation(Property.class) != null
                    || field.getAnnotation(NodeId.class) != null
                    || field.getAnnotation(Convert.class) != null
                    || field.getAnnotation(Enumerated.class) != null;
        }
        return field.getAnnotation(Transient.class) == null
                && TypeHandlerRegistry.findHandler(
                        field,
                        env.getTypeUtils(),
                        env.getElementUtils()).isPresent();
    }
}
