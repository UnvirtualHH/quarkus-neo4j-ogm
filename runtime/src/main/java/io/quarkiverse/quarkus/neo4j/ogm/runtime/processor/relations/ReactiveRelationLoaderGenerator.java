package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.relations;

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
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.NodeEntity;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.ReactiveRelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship;

public class ReactiveRelationLoaderGenerator extends AbstractRelationLoaderGenerator {

    @Override
    public void generateRelationLoader(
            String packageName,
            TypeElement entityType,
            String loaderClassName,
            ProcessingEnvironment processingEnv) {

        boolean hasRelationships = ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .anyMatch(f -> f.getAnnotation(Relationship.class) != null
                        && shouldFetchRelationship(f.getAnnotation(Relationship.class)));

        if (!hasRelationships) {
            return;
        }

        Types types = processingEnv.getTypeUtils();
        TypeMirror listType = processingEnv.getElementUtils()
                .getTypeElement("java.util.List").asType();

        TypeSpec.Builder classBuilder = buildReactiveClassBase(loaderClassName, entityType.getQualifiedName().toString());

        classBuilder.addMethod(buildReactiveLoaderWithDepth(
                entityType,
                entityType.getQualifiedName().toString(),
                types,
                listType).build());

        classBuilder.addMethod(buildReactiveRecursiveLoader().build());

        try {
            JavaFile.builder(packageName, classBuilder.build())
                    .build()
                    .writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated reactive relation loader: " + packageName + "." + loaderClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate reactive relation loader: " + e.getMessage());
        }
    }

    private TypeSpec.Builder buildReactiveClassBase(String loaderClassName, String qualifiedName) {
        ClassName reactiveRegistryClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "ReactiveRepositoryRegistry");
        ClassName relationVisitorClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "ReactiveRelationVisitor");
        ClassName visitorContextClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "ReactiveRelationVisitor.VisitorContext");

        return TypeSpec.classBuilder(loaderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(ReactiveRelationLoader.class), ClassName.bestGuess(qualifiedName)))
                .addField(reactiveRegistryClass, "reactiveRegistry", Modifier.PRIVATE, Modifier.FINAL)
                .addField(relationVisitorClass, "relationVisitor", Modifier.PRIVATE, Modifier.FINAL)
                .addField(visitorContextClass, "visitorContext", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(reactiveRegistryClass, "reactiveRegistry")
                        .addParameter(relationVisitorClass, "relationVisitor")
                        .addStatement("this.reactiveRegistry = reactiveRegistry")
                        .addStatement("this.relationVisitor = relationVisitor")
                        .addStatement("this.visitorContext = relationVisitor.newContext()")
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
                .beginControlFlow("if (id == null)")
                .addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"))
                .endControlFlow();

        builder.addCode("return relationVisitor.shouldVisit(entity, currentDepth, visitorContext)"
                + ".flatMap(shouldVisit -> {"
                + " if (!shouldVisit) return $T.createFrom().item(entity);"
                + " return relationVisitor.markVisited(entity, visitorContext)"
                + "   .flatMap(ignore -> {\n", ClassName.get("io.smallrye.mutiny", "Uni"));

        builder.addCode(buildReactiveFieldLoadBody(entityType, qualifiedName, types, listType, sourceLabel));

        builder.addCode("\n  });\n});\n");

        return builder;
    }

    private CodeBlock buildReactiveFieldLoadBody(TypeElement entityType, String qualifiedName,
            Types types, TypeMirror listType, String sourceLabel) {
        List<VariableElement> relationFields = new ArrayList<>();
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel != null && shouldFetchRelationship(rel)) {
                relationFields.add(field);
            }
        }

        CodeBlock.Builder block = CodeBlock.builder();

        if (relationFields.isEmpty()) {
            block.addStatement("return $T.createFrom().item(entity)", ClassName.get("io.smallrye.mutiny", "Uni"));
            return block.build();
        }

        List<String> uniVars = new ArrayList<>();
        int idx = 1;

        for (VariableElement field : relationFields) {
            final String fieldName = field.getSimpleName().toString();

            final String fieldType = field.asType().toString();
            final boolean isList = types.isAssignable(field.asType(), types.erasure(listType));
            final String relatedType = isList
                    ? fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>'))
                    : fieldType;
            final String relatedSimple = relatedType.contains(".")
                    ? relatedType.substring(relatedType.lastIndexOf('.') + 1)
                    : relatedType;

            final Relationship relAnn = field.getAnnotation(Relationship.class);
            final String query = buildQuery(sourceLabel, relAnn.direction(), relAnn.type(), relatedSimple);

            final String uniVar = "u" + (idx++);
            uniVars.add(uniVar);

            if (isList) {
                block.addStatement(
                        "$T $L = relationVisitor.shouldLoadRelationship(entity, $S, currentDepth, visitorContext)"
                                + ".flatMap(shouldLoad -> shouldLoad"
                                + " ? reactiveRegistry.getReactiveRepository($T.class).query($S, $T.of($S, id))"
                                + "     .onItem().transformToUniAndMerge(item -> loadRelationRecursively(item, currentDepth + 1).map(loaded -> ($T) loaded))"
                                + "     .collect().asList()"
                                + " : $T.createFrom().item(new $T<$T>()))",
                        ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.bestGuess(relatedType))),
                        uniVar,
                        fieldName,
                        ClassName.bestGuess(relatedType),
                        query,
                        ClassName.get(Map.class), "id",
                        ClassName.bestGuess(relatedType),
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ArrayList.class,
                        ClassName.bestGuess(relatedType));
            } else {
                block.addStatement(
                        "$T $L = relationVisitor.shouldLoadRelationship(entity, $S, currentDepth, visitorContext)"
                                + ".flatMap(shouldLoad -> {"
                                + " if (!shouldLoad) return $T.createFrom().nullItem();"
                                + " return reactiveRegistry.getReactiveRepository($T.class).querySingle($S, $T.of($S, id))"
                                + "   .flatMap(item -> item != null"
                                + "       ? loadRelationRecursively(item, currentDepth + 1).map(loaded -> ($T) loaded)"
                                + "       : $T.createFrom().nullItem());"
                                + " })",
                        ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                                ClassName.bestGuess(relatedType)),
                        uniVar,
                        fieldName,
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.bestGuess(relatedType),
                        query,
                        ClassName.get(Map.class), "id",
                        ClassName.bestGuess(relatedType),
                        ClassName.get("io.smallrye.mutiny", "Uni"));
            }
        }

        if (uniVars.size() == 1) {
            String u = uniVars.getFirst();
            VariableElement field = relationFields.getFirst();
            String setter = resolveSetterName(field);
            block.addStatement("return $L.map(result -> { entity.$L(result); return entity; })", u, setter);
        } else {
            block.add("return $T.combine().all().unis(", ClassName.get("io.smallrye.mutiny", "Uni"));
            block.add(String.join(", ", uniVars));
            block.add(").asTuple().map(tuple -> {\n");
            int i = 1;
            for (VariableElement field : relationFields) {
                String setter = resolveSetterName(field);
                block.addStatement("  entity.$L(tuple.getItem$L())", setter, i++);
            }
            block.addStatement("  return entity");
            block.add("});");
        }

        return block.build();
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
                .addStatement("return relationVisitor.shouldVisit(entity, currentDepth, visitorContext)"
                        + ".flatMap(shouldVisit -> {"
                        + " if (!shouldVisit) return $T.createFrom().item(entity);"
                        + " return relationVisitor.markVisited(entity, visitorContext)"
                        + "   .flatMap(ignore -> {"
                        + "     $T<$T> repository = ($T<$T>) reactiveRegistry.getReactiveRepository(entity.getClass());"
                        + "     if (repository != null && repository.getRelationLoader() != null) {"
                        + "        $T<$T> loader = ($T<$T>) repository.getRelationLoader();"
                        + "        return loader.loadRelations(entity, currentDepth + 1);"
                        + "     }"
                        + "     return $T.createFrom().item(entity);"
                        + "   });"
                        + " })",
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                        ClassName.get(Object.class),
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                        ClassName.get(Object.class),
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "ReactiveRelationLoader"),
                        ClassName.get(Object.class),
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping", "ReactiveRelationLoader"),
                        ClassName.get(Object.class),
                        ClassName.get("io.smallrye.mutiny", "Uni"));
    }
}
