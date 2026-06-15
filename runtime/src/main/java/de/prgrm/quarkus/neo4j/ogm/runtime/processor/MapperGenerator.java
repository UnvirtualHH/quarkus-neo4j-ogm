package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

        boolean hasContextAwareConverters = hasContextAwareConverters(entityType, processingEnv);

        MethodSpec mapFromValueMethod = generateMapFromValueMethod(entityType, processingEnv);
        MethodSpec mapMethod = generateMapMethod(entityType, processingEnv, hasContextAwareConverters);
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
                        .build());

        // Only add _rawValues field if entity has context-aware converters
        // Use IdentityHashMap to store raw values per entity instance (not shared across entities)
        if (hasContextAwareConverters) {
            mapperBuilder.addField(FieldSpec.builder(
                    ParameterizedTypeName.get(ClassName.get(Map.class),
                            TypeName.get(entityType.asType()),
                            ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class),
                                    ClassName.get(Object.class))),
                    "_rawValuesByEntity",
                    Modifier.PRIVATE, Modifier.FINAL)
                    // The mapper is an @ApplicationScoped singleton shared across request threads,
                    // so the staging map must be synchronized. Entries are removed again in
                    // applyPostLoadConverters() to avoid retaining entity references (memory leak).
                    .initializer("$T.synchronizedMap(new $T<>())",
                            java.util.Collections.class, java.util.IdentityHashMap.class)
                    .build());
        }

        mapperBuilder.addMethod(mapFromValueMethod)
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
    // mapFromValue()
    // ======================================================================

    private MethodSpec generateMapFromValueMethod(TypeElement entityType, ProcessingEnvironment env) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("mapFromValue")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("org.neo4j.driver", "Value"), "nodeValue");

        b.addStatement("$T instance = new $T()",
                TypeName.get(entityType.asType()),
                TypeName.get(entityType.asType()));

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
    // map()
    // ======================================================================

    private MethodSpec generateMapMethod(TypeElement entityType, ProcessingEnvironment env,
            boolean hasContextAwareConverters) {
        MethodSpec.Builder b = MethodSpec.methodBuilder("map")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.get(entityType.asType()))
                .addParameter(ClassName.get("org.neo4j.driver", "Record"), "record")
                .addParameter(String.class, "alias");

        b.addStatement("$T instance = mapFromValue(record.get(alias))",
                TypeName.get(entityType.asType()));

        // Map DESIGN_ONLY relationships from additional record columns
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || rel.mode() != RelationshipMode.DESIGN_ONLY)
                continue;

            String fieldName = field.getSimpleName().toString();
            String setter = MapperUtil.resolveSetterName(field);
            String targetType = MapperUtil.getFieldType(field);
            boolean isCollection = MapperUtil.stripAnnotations(field.asType().toString()).startsWith("java.util.List");
            ClassName targetClass = ClassName.bestGuess(targetType);

            b.beginControlFlow("if (!record.get($S).isNull())", fieldName);

            if (isCollection) {
                b.addStatement("$T<$T> _relMapper = registry.get($T.class)",
                        EntityMapper.class, targetClass, targetClass);
                b.addStatement("$T<$T> _relList = new $T<>()",
                        List.class, targetClass, java.util.ArrayList.class);
                b.beginControlFlow("for ($T _item : record.get($S).values())",
                        ClassName.get("org.neo4j.driver", "Value"), fieldName);
                b.beginControlFlow("if (!_item.isNull())");
                b.addStatement("_relList.add(_relMapper.mapFromValue(_item))");
                b.endControlFlow();
                b.endControlFlow();
                b.addStatement("instance.$L(_relList)", setter);
            } else {
                b.addStatement("$T<$T> _relMapper = registry.get($T.class)",
                        EntityMapper.class, targetClass, targetClass);
                b.addStatement("instance.$L(_relMapper.mapFromValue(record.get($S)))", setter, fieldName);
            }

            b.endControlFlow();
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
        boolean hasPersistableRelationships = ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .map(f -> f.getAnnotation(Relationship.class))
                .anyMatch(r -> r != null && shouldPersistRelationship(r));

        if (hasPersistableRelationships) {
            // Keys ("TYPE_DIRECTION") of relationship fields that are non-null on this instance.
            // A non-null field (even an empty collection) means the relationship is "managed" and
            // must be cleared before re-persisting, so emptying a collection detaches the existing
            // edges (issue #69). A null field is left untouched.
            b.addStatement("$T<String> _persistableKeys = new $T<>()", Set.class, java.util.HashSet.class);
        }

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || !shouldPersistRelationship(rel))
                continue;

            String getter = MapperUtil.resolveGetterName(field);
            String targetType = MapperUtil.getFieldType(field);
            boolean isCollection = MapperUtil.stripAnnotations(field.asType().toString()).startsWith("java.util.List");
            // Key includes the target label so that multiple relationships sharing the same type but
            // pointing to different node types (issue #60) are cleared independently. The '|'
            // delimiter is safe because Cypher identifiers only contain [A-Za-z0-9_].
            String relKey = rel.type() + "|" + rel.direction().name() + "|" + resolveTargetLabel(targetType, env);

            if (isCollection) {
                b.beginControlFlow("if (entity.$L() != null)", getter)
                        .addStatement("_persistableKeys.add($S)", relKey)
                        .beginControlFlow("for (var related : entity.$L())", getter)
                        .addCode(buildRelationshipAddCode(rel, targetType, "related"))
                        .endControlFlow()
                        .endControlFlow();
            } else {
                b.beginControlFlow("if (entity.$L() != null)", getter)
                        .addStatement("_persistableKeys.add($S)", relKey)
                        .addCode(buildRelationshipAddCode(rel, targetType, "entity." + getter + "()"))
                        .endControlFlow();
            }
        }

        b.addStatement("return new $T($T.class, properties, relationships, $L)",
                EntityWithRelations.class,
                TypeName.get(entityType.asType()),
                hasPersistableRelationships ? CodeBlock.of("_persistableKeys") : CodeBlock.of("$T.of()", Set.class));

        return b.build();
    }

    /**
     * Resolves the Neo4j label of a relationship target type: the {@code @NodeEntity(label = ...)}
     * value if present, otherwise the simple class name – mirroring {@code EntityMapperProcessor}.
     */
    private String resolveTargetLabel(String targetType, ProcessingEnvironment env) {
        TypeElement te = env.getElementUtils().getTypeElement(targetType);
        if (te != null) {
            NodeEntity nodeEntity = te.getAnnotation(NodeEntity.class);
            if (nodeEntity != null && !nodeEntity.label().isEmpty()) {
                return nodeEntity.label();
            }
            return te.getSimpleName().toString();
        }
        int lastDot = targetType.lastIndexOf('.');
        return (lastDot >= 0) ? targetType.substring(lastDot + 1) : targetType;
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
            String type = MapperUtil.stripAnnotations(field.asType().toString());

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
                .filter(f -> f.getAnnotation(Relationship.class) != null)
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

        // Group by relationship type: multiple fields may share the same type but point to
        // different target node types (issue #60). Within such a group we dispatch on the runtime
        // type of relatedEntity via instanceof (no reflection).
        Map<String, List<VariableElement>> byType = new java.util.LinkedHashMap<>();
        for (VariableElement field : relFields) {
            byType.computeIfAbsent(field.getAnnotation(Relationship.class).type(), k -> new java.util.ArrayList<>())
                    .add(field);
        }

        b.beginControlFlow("switch (relationType)");
        for (Map.Entry<String, List<VariableElement>> entry : byType.entrySet()) {
            List<VariableElement> fields = entry.getValue();
            b.beginControlFlow("case $S ->", entry.getKey());

            if (fields.size() == 1) {
                VariableElement field = fields.get(0);
                ClassName targetClass = ClassName.bestGuess(MapperUtil.getFieldType(field));
                emitRelationAssignment(b, field, CodeBlock.of("($T) relatedEntity", targetClass));
            } else {
                boolean firstBranch = true;
                for (VariableElement field : fields) {
                    ClassName targetClass = ClassName.bestGuess(MapperUtil.getFieldType(field));
                    if (firstBranch) {
                        b.beginControlFlow("if (relatedEntity instanceof $T related)", targetClass);
                        firstBranch = false;
                    } else {
                        b.nextControlFlow("else if (relatedEntity instanceof $T related)", targetClass);
                    }
                    emitRelationAssignment(b, field, CodeBlock.of("related"));
                }
                b.endControlFlow();
            }

            b.addStatement("break");
            b.endControlFlow();
        }
        b.beginControlFlow("default ->").addStatement("break").endControlFlow();
        b.endControlFlow();

        return b.build();
    }

    /**
     * Emits the assignment of a related entity to its field, handling both collection and
     * single-valued relationships. {@code relatedExpr} already evaluates to the correct target type.
     */
    private void emitRelationAssignment(MethodSpec.Builder b, VariableElement field, CodeBlock relatedExpr) {
        String setter = MapperUtil.resolveSetterName(field);
        String getter = MapperUtil.resolveGetterName(field);
        boolean isCollection = MapperUtil.stripAnnotations(field.asType().toString()).startsWith("java.util.List");

        if (isCollection) {
            b.beginControlFlow("if (entity.$L() == null)", getter)
                    .addStatement("entity.$L(new $T<>())", setter, java.util.ArrayList.class)
                    .endControlFlow()
                    .addStatement("entity.$L().add($L)", getter, relatedExpr);
        } else {
            b.addStatement("entity.$L($L)", setter, relatedExpr);
        }
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

        if (hasPostLoadConverters) {
            // Release the staged raw values for this entity so the singleton mapper does not
            // retain entity references indefinitely.
            b.addStatement("_rawValuesByEntity.remove(entity)");
        }

        // Only return the method if there are context-aware converters
        return hasPostLoadConverters ? b.build() : null;
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private boolean hasContextAwareConverters(TypeElement entityType, ProcessingEnvironment processingEnv) {
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getAnnotation(Convert.class) != null) {
                Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field,
                        processingEnv.getTypeUtils(), processingEnv.getElementUtils());
                if (handler.isPresent()
                        && handler.get() instanceof de.prgrm.quarkus.neo4j.ogm.runtime.processor.types.ConverterTypeHandler) {
                    de.prgrm.quarkus.neo4j.ogm.runtime.processor.types.ConverterTypeHandler converterHandler = (de.prgrm.quarkus.neo4j.ogm.runtime.processor.types.ConverterTypeHandler) handler
                            .get();
                    if (converterHandler.generatePostLoadConverterCode(field, "entity") != null) {
                        return true; // Found at least one context-aware converter
                    }
                }
            }
        }
        return false;
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
