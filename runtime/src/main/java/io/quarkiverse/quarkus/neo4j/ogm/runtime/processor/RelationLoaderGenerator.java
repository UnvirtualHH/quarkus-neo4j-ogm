package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;

/**
 * Code generator for relation loaders (imperative and reactive).
 *
 * Generates classes that handle recursive relationship loading using {@link RelationVisitor}.
 */
public class RelationLoaderGenerator {

    public void generateRelationLoader(String packageName, TypeElement entityType, String loaderClassName,
            ProcessingEnvironment processingEnv, GenerateRepository.RepositoryType repoType) {
        String qualifiedName = entityType.getQualifiedName().toString();

        // Check if entity has any relationships that should be fetched
        boolean hasRelationships = ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .anyMatch(f -> {
                    Relationship rel = f.getAnnotation(Relationship.class);
                    return rel != null && shouldFetchRelationship(rel);
                });

        if (!hasRelationships) {
            return;
        }

        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        TypeMirror listType = elements.getTypeElement("java.util.List").asType();

        TypeSpec.Builder classBuilder;
        boolean isReactive = repoType == GenerateRepository.RepositoryType.REACTIVE;

        if (isReactive) {
            classBuilder = buildReactiveClassBase(loaderClassName, qualifiedName);
            classBuilder.addMethod(buildReactiveLoaderWithDepth(entityType, qualifiedName, types, listType).build());
            classBuilder.addMethod(buildReactiveRecursiveLoader().build());
        } else {
            classBuilder = buildImperativeClassBase(loaderClassName, qualifiedName);
            classBuilder.addMethod(buildImperativeLoaderWithDepth(entityType, qualifiedName, types, listType).build());
            classBuilder.addMethod(buildImperativeRecursiveLoader().build());
        }

        try {
            JavaFile.builder(packageName, classBuilder.build())
                    .build()
                    .writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated relation loader: " + packageName + "." + loaderClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate relation loader: " + e.getMessage());
        }
    }

    private boolean shouldFetchRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.FETCH_ONLY
                || rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
    }

    // =====================================================================================
    // Reactive Loader
    // =====================================================================================

    private TypeSpec.Builder buildReactiveClassBase(String loaderClassName, String qualifiedName) {
        ClassName reactiveRegistryClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "ReactiveRepositoryRegistry");
        ClassName relationVisitorClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "RelationVisitor");

        return TypeSpec.classBuilder(loaderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(ReactiveRelationLoader.class), ClassName.bestGuess(qualifiedName)))
                .addField(reactiveRegistryClass, "reactiveRegistry", Modifier.PRIVATE, Modifier.FINAL)
                .addField(relationVisitorClass, "relationVisitor", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(reactiveRegistryClass, "reactiveRegistry")
                        .addParameter(relationVisitorClass, "relationVisitor")
                        .addStatement("this.reactiveRegistry = reactiveRegistry")
                        .addStatement("this.relationVisitor = relationVisitor")
                        .build())
                .addMethod(MethodSpec.methodBuilder("getNodeId")
                        .addModifiers(Modifier.PRIVATE)
                        .returns(Object.class)
                        .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                        .addStatement(
                                "return reactiveRegistry.getReactiveRepository($T.class).getEntityMapper().getNodeId(entity)",
                                ClassName.bestGuess(qualifiedName))
                        .build());
    }

    private MethodSpec.Builder buildReactiveLoaderWithDepth(TypeElement entityType, String qualifiedName,
            Types types, TypeMirror listType) {
        NodeEntity nodeAnnotation = entityType.getAnnotation(NodeEntity.class);
        String sourceLabel = (nodeAnnotation != null && !nodeAnnotation.label().isEmpty())
                ? nodeAnnotation.label()
                : entityType.getSimpleName().toString();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("loadRelations")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.bestGuess(qualifiedName)))
                .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                .addParameter(int.class, "currentDepth")
                .addStatement("Object id = getNodeId(entity)")
                // IMPORTANT: no reset() here; do it at repository entry point
                .beginControlFlow("if (id == null)")
                .addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"))
                .endControlFlow()
                .beginControlFlow("if (!relationVisitor.shouldVisit(entity, currentDepth))")
                .addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"))
                .endControlFlow()
                .addStatement("relationVisitor.markVisited(entity)");

        List<String> uniVars = new ArrayList<>();
        CodeBlock.Builder unis = CodeBlock.builder();
        CodeBlock.Builder mapping = CodeBlock.builder();
        boolean hasRelations = false;
        int index = 1;

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || !shouldFetchRelationship(rel)) {
                continue;
            }

            hasRelations = true;
            String fieldName = field.getSimpleName().toString();
            String setter = resolveSetterName(field);

            String fieldType = field.asType().toString();
            boolean isList = types.isAssignable(field.asType(), types.erasure(listType));

            String relatedType = isList
                    ? fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>'))
                    : fieldType;
            String relatedSimple = relatedType.contains(".")
                    ? relatedType.substring(relatedType.lastIndexOf('.') + 1)
                    : relatedType;

            String query = buildQuery(sourceLabel, rel.direction(), rel.type(), relatedSimple);
            String uniVar = "u" + index;
            uniVars.add(uniVar);

            CodeBlock mapOf = CodeBlock.of("$T.of($S, id)", ClassName.get(Map.class), "id");

            if (isList) {
                unis.addStatement(
                        "$T $L = !relationVisitor.shouldLoadRelationship(entity, $S, currentDepth) ? "
                                + "$T.createFrom().item(new $T<$T>()) : "
                                + "reactiveRegistry.getReactiveRepository($T.class).query($S, $L)"
                                + ".onItem().transformToUniAndMerge(item -> loadRelationRecursively(item, currentDepth + 1).map(loaded -> ($T) loaded))"
                                + ".collect().asList()",
                        ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.bestGuess(relatedType))),
                        uniVar,
                        fieldName,
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ArrayList.class,
                        ClassName.bestGuess(relatedType),
                        ClassName.bestGuess(relatedType),
                        query,
                        mapOf,
                        ClassName.bestGuess(relatedType));

                mapping.addStatement("entity.$L(tuple.getItem$L())", setter, index);
            } else {
                unis.addStatement(
                        "$T $L = !relationVisitor.shouldLoadRelationship(entity, $S, currentDepth) ? "
                                + "$T.createFrom().nullItem() : "
                                + "reactiveRegistry.getReactiveRepository($T.class).querySingle($S, $L)"
                                + ".flatMap(item -> item != null ? loadRelationRecursively(item, currentDepth + 1).map(loaded -> ($T) loaded) : $T.createFrom().nullItem())",
                        ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                                ClassName.bestGuess(relatedType)),
                        uniVar,
                        fieldName,
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.bestGuess(relatedType),
                        query,
                        mapOf,
                        ClassName.bestGuess(relatedType),
                        ClassName.get("io.smallrye.mutiny", "Uni"));

                mapping.addStatement("entity.$L(tuple.getItem$L())", setter, index);
            }

            index++;
        }

        if (!hasRelations) {
            builder.addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"));
            return builder;
        }

        builder.addCode(unis.build());

        if (uniVars.size() == 1) {
            String setterCode = mapping.build().toString().replace("tuple.getItem1()", "result");
            builder.addStatement("return $L.map(result -> {\n$L\n    return entity;\n})",
                    uniVars.get(0),
                    indent(setterCode, 1));
        } else {
            builder.addCode("return $T.combine().all().unis(", ClassName.get("io.smallrye.mutiny", "Uni"));
            builder.addCode(String.join(", ", uniVars));
            builder.addCode(").asTuple().map(tuple -> {\n");
            builder.addCode(indent(mapping.build().toString(), 1));
            builder.addCode("    return entity;\n");
            builder.addCode("});\n");
        }

        return builder;
    }

    private MethodSpec.Builder buildReactiveRecursiveLoader() {
        return MethodSpec.methodBuilder("loadRelationRecursively")
                .addModifiers(Modifier.PRIVATE)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.get(Object.class)))
                .addParameter(Object.class, "entity")
                .addParameter(int.class, "currentDepth")
                .addStatement("if (entity == null) return $T.createFrom().nullItem()",
                        ClassName.get("io.smallrye.mutiny", "Uni"))
                .beginControlFlow("if (!relationVisitor.shouldVisit(entity, currentDepth))")
                .addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"))
                .endControlFlow()
                .addStatement("relationVisitor.markVisited(entity)")
                .beginControlFlow("try")
                .addStatement("$T repository = ($T) reactiveRegistry.getReactiveRepository(entity.getClass())",
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (repository != null)")
                .addStatement("$T loader = ($T) repository.getRelationLoader()",
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "ReactiveRelationLoader"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "ReactiveRelationLoader"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (loader != null)")
                // FIX: advance depth when switching loaders
                .addStatement("return loader.loadRelations(entity, currentDepth + 1)")
                .endControlFlow()
                .endControlFlow()
                .nextControlFlow("catch (Exception e)")
                .addStatement("// Ignore errors in recursive loading")
                .endControlFlow()
                .addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"));
    }

    // =====================================================================================
    // Imperative Loader
    // =====================================================================================

    private TypeSpec.Builder buildImperativeClassBase(String loaderClassName, String qualifiedName) {
        ClassName repositoryRegistryClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "RepositoryRegistry");
        ClassName relationVisitorClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "RelationVisitor");

        return TypeSpec.classBuilder(loaderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(RelationLoader.class), ClassName.bestGuess(qualifiedName)))
                .addField(repositoryRegistryClass, "registry", Modifier.PRIVATE, Modifier.FINAL)
                .addField(relationVisitorClass, "relationVisitor", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(repositoryRegistryClass, "registry")
                        .addParameter(relationVisitorClass, "relationVisitor")
                        .addStatement("this.registry = registry")
                        .addStatement("this.relationVisitor = relationVisitor")
                        .build())
                .addMethod(MethodSpec.methodBuilder("getNodeId")
                        .addModifiers(Modifier.PRIVATE)
                        .returns(Object.class)
                        .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                        .addStatement("return registry.getRepository($T.class).getEntityMapper().getNodeId(entity)",
                                ClassName.bestGuess(qualifiedName))
                        .build());
    }

    private MethodSpec.Builder buildImperativeLoaderWithDepth(TypeElement entityType, String qualifiedName,
            Types types, TypeMirror listType) {
        NodeEntity nodeAnnotation = entityType.getAnnotation(NodeEntity.class);
        String sourceLabel = (nodeAnnotation != null && !nodeAnnotation.label().isEmpty())
                ? nodeAnnotation.label()
                : entityType.getSimpleName().toString();

        MethodSpec.Builder builder = MethodSpec.methodBuilder("loadRelations")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                .addParameter(int.class, "currentDepth")
                .addStatement("Object id = getNodeId(entity)")
                // IMPORTANT: no reset() here; do it at repository entry point
                .beginControlFlow("if (id == null)")
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("if (!relationVisitor.shouldVisit(entity, currentDepth))")
                .addStatement("return")
                .endControlFlow()
                .addStatement("relationVisitor.markVisited(entity)");

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || !shouldFetchRelationship(rel)) {
                continue;
            }

            String fieldName = field.getSimpleName().toString();
            String getter = resolveGetterName(field);
            String setter = resolveSetterName(field);
            String fieldType = field.asType().toString();
            boolean isList = types.isAssignable(field.asType(), types.erasure(listType));
            String relatedType = isList
                    ? fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>'))
                    : fieldType;
            String relatedSimple = relatedType.contains(".")
                    ? relatedType.substring(relatedType.lastIndexOf('.') + 1)
                    : relatedType;

            String query = buildQuery(sourceLabel, rel.direction(), rel.type(), relatedSimple);
            CodeBlock mapOf = CodeBlock.of("$T.of($S, id)", ClassName.get(Map.class), "id");

            builder.addComment("Loading relation $L (max depth: $L)", fieldName, rel.maxDepth());
            builder.beginControlFlow("if (!relationVisitor.shouldLoadRelationship(entity, $S, currentDepth))", fieldName);

            if (isList) {
                builder.addStatement("entity.$L(new $T<>())", setter, ArrayList.class);
            } else {
                builder.addStatement("entity.$L(null)", setter);
            }
            builder.nextControlFlow("else");

            builder.beginControlFlow("try");
            builder.addStatement("var repository = registry.getRepository($T.class)", ClassName.bestGuess(relatedType));
            builder.addStatement("String query = $S", query);

            if (isList) {
                builder.addStatement("var results = repository.query(query, $L)", mapOf);
                builder.addStatement("if (entity.$L() == null) entity.$L(new $T<>())", getter, setter, ArrayList.class);
                builder.addStatement("entity.$L().addAll(results)", getter);
                builder.beginControlFlow("for (var item : entity.$L())", getter);
                builder.addStatement("loadRelationRecursively(item, currentDepth + 1)");
                builder.endControlFlow();
            } else {
                builder.addStatement("var result = repository.querySingle(query, $L)", mapOf);
                builder.addStatement("entity.$L(result)", setter);
                builder.beginControlFlow("if (result != null)");
                builder.addStatement("loadRelationRecursively(result, currentDepth + 1)");
                builder.endControlFlow();
            }

            builder.nextControlFlow("catch (Exception e)")
                    .addStatement("throw new RuntimeException($S, e)",
                            String.format("Failed to load relation: %s", rel.type()))
                    .endControlFlow()
                    .endControlFlow();
        }

        return builder;
    }

    private MethodSpec.Builder buildImperativeRecursiveLoader() {
        return MethodSpec.methodBuilder("loadRelationRecursively")
                .addModifiers(Modifier.PRIVATE)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .returns(void.class)
                .addParameter(Object.class, "entity")
                .addParameter(int.class, "currentDepth")
                .beginControlFlow("if (entity == null)")
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("if (!relationVisitor.shouldVisit(entity, currentDepth))")
                .addStatement("return")
                .endControlFlow()
                .addStatement("relationVisitor.markVisited(entity)")
                .beginControlFlow("try")
                .addStatement("$T repository = ($T) registry.getRepository(entity.getClass())",
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (repository != null)")
                .addStatement("$T loader = ($T) repository.getRelationLoader()",
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "RelationLoader"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "RelationLoader"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (loader != null)")
                // FIX: advance depth when switching loaders
                .addStatement("loader.loadRelations(entity, currentDepth + 1)")
                .endControlFlow()
                .endControlFlow()
                .nextControlFlow("catch (Exception e)")
                .addStatement("// Ignore errors in recursive loading")
                .endControlFlow();
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private String buildQuery(String sourceLabel, Direction direction, String relationType, String targetLabel) {
        String left = direction == Direction.INCOMING ? "<-" : "-";
        String right = direction == Direction.OUTGOING ? "->" : "-";
        return String.format("MATCH (n:%s {id: $id})%s[:%s]%s(m:%s) RETURN m as node",
                sourceLabel, left, relationType, right, targetLabel);
    }

    private String resolveGetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String resolveSetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String indent(String code, int level) {
        String indent = "    ".repeat(level);
        return code.lines()
                .map(line -> indent + line)
                .reduce("", (a, b) -> a + b + "\n");
    }
}
