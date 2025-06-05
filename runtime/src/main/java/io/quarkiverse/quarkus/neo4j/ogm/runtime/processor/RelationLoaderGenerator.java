package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.ArrayList;
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
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.ReactiveRelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship;

public class RelationLoaderGenerator {

    public void generateRelationLoader(String packageName, TypeElement entityType, String loaderClassName,
            ProcessingEnvironment processingEnv, GenerateRepository.RepositoryType repoType) {
        String entityName = entityType.getSimpleName().toString();
        String qualifiedName = entityType.getQualifiedName().toString();

        boolean hasRelationships = ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .anyMatch(f -> f.getAnnotation(Relationship.class) != null);

        if (!hasRelationships)
            return;

        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        TypeMirror listType = elements.getTypeElement("java.util.List").asType();

        TypeSpec.Builder classBuilder;
        MethodSpec.Builder loadMethod;
        boolean isReactive = repoType == GenerateRepository.RepositoryType.REACTIVE;

        if (isReactive) {
            classBuilder = buildReactiveClassBase(packageName, loaderClassName, entityName, qualifiedName);
            loadMethod = buildReactiveLoader(entityType, qualifiedName, types, listType);
        } else {
            classBuilder = buildImperativeClassBase(packageName, loaderClassName, entityName, qualifiedName);
            loadMethod = buildImperativeLoader(entityType, qualifiedName, types, listType);
        }

        classBuilder.addMethod(loadMethod.build());

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

    private TypeSpec.Builder buildReactiveClassBase(String packageName, String loaderClassName, String entityName,
            String qualifiedName) {
        ClassName reactiveRegistryClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "ReactiveRepositoryRegistry");
        return TypeSpec.classBuilder(loaderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(ReactiveRelationLoader.class), ClassName.bestGuess(qualifiedName)))
                .addField(reactiveRegistryClass, "reactiveRegistry", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(reactiveRegistryClass, "reactiveRegistry")
                        .addStatement("this.reactiveRegistry = reactiveRegistry")
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

    private MethodSpec.Builder buildReactiveLoader(TypeElement entityType, String qualifiedName, Types types,
            TypeMirror listType) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("loadRelations")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"), ClassName.get(Void.class)))
                .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                .addStatement("Object id = getNodeId(entity)")
                .beginControlFlow("if (id == null)")
                .addStatement("return $T.createFrom().voidItem()", ClassName.get("io.smallrye.mutiny", "Uni"))
                .endControlFlow();

        CodeBlock.Builder uniChain = CodeBlock.builder();
        boolean first = true;

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null)
                continue;

            String getter = resolveGetterName(field);
            String setter = resolveSetterName(field);
            String fieldType = field.asType().toString();
            boolean isList = types.isAssignable(field.asType(), types.erasure(listType));
            String relatedType = isList
                    ? fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>'))
                    : fieldType;
            String relatedSimple = relatedType.contains(".") ? relatedType.substring(relatedType.lastIndexOf('.') + 1)
                    : relatedType;
            String query = buildQuery(rel.direction(), rel.type(), relatedSimple, !isList);

            if (!first)
                uniChain.add(".chain(() -> ");
            else
                uniChain.add("return ");

            uniChain.add("reactiveRegistry.getReactiveRepository($T.class).query($S, $T.of(\"id\", id))",
                    ClassName.bestGuess(relatedType), query, ClassName.get(Map.class));

            if (isList) {
                uniChain.add(
                        ".collect().asList().invoke(list -> { if (entity.$L() == null) entity.$L(new $T<>()); entity.$L().addAll(list); })",
                        getter, setter, ArrayList.class, getter);
            } else {
                uniChain.add(".collect().first().invoke(related -> entity.$L(related))", setter);
            }

            uniChain.add(".replaceWithVoid()");
            if (!first)
                uniChain.add(")");
            first = false;
        }

        if (first) {
            builder.addStatement("return $T.createFrom().voidItem()", ClassName.get("io.smallrye.mutiny", "Uni"));
        } else {
            builder.addCode(uniChain.build()).addStatement("");
        }

        return builder;
    }

    private TypeSpec.Builder buildImperativeClassBase(String packageName, String loaderClassName, String entityName,
            String qualifiedName) {
        ClassName repositoryRegistryClass = ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository",
                "RepositoryRegistry");
        return TypeSpec.classBuilder(loaderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addAnnotation(ClassName.get("jakarta.enterprise.context", "ApplicationScoped"))
                .addSuperinterface(ParameterizedTypeName.get(
                        ClassName.get(RelationLoader.class), ClassName.bestGuess(qualifiedName)))
                .addField(repositoryRegistryClass, "registry", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addAnnotation(ClassName.get("jakarta.inject", "Inject"))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(repositoryRegistryClass, "registry")
                        .addStatement("this.registry = registry")
                        .build())
                .addMethod(MethodSpec.methodBuilder("getNodeId")
                        .addModifiers(Modifier.PRIVATE)
                        .returns(Object.class)
                        .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                        .addStatement("return registry.getRepository($T.class).getEntityMapper().getNodeId(entity)",
                                ClassName.bestGuess(qualifiedName))
                        .build());
    }

    private MethodSpec.Builder buildImperativeLoader(TypeElement entityType, String qualifiedName, Types types,
            TypeMirror listType) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("loadRelations")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                .addStatement("Object id = getNodeId(entity)")
                .beginControlFlow("if (id == null)").addStatement("return").endControlFlow();

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null)
                continue;

            String getter = resolveGetterName(field);
            String setter = resolveSetterName(field);
            String fieldType = field.asType().toString();
            boolean isList = types.isAssignable(field.asType(), types.erasure(listType));
            String relatedType = isList
                    ? fieldType.substring(fieldType.indexOf('<') + 1, fieldType.lastIndexOf('>'))
                    : fieldType;
            String relatedSimple = relatedType.contains(".") ? relatedType.substring(relatedType.lastIndexOf('.') + 1)
                    : relatedType;
            String query = buildQuery(rel.direction(), rel.type(), relatedSimple, !isList);

            builder.addComment("Loading relation $L", field.getSimpleName());
            builder.beginControlFlow("try");
            builder.addStatement("var repository = registry.getRepository($T.class)", ClassName.bestGuess(relatedType));
            builder.addStatement("String query = $S", query);
            builder.addStatement("var results = repository.query(query, $T.of(\"id\", id))", Map.class);

            if (isList) {
                builder.addStatement("if (entity.$L() == null) entity.$L(new $T<>())", getter, setter, ArrayList.class);
                builder.addStatement("entity.$L().addAll(results)", getter);
            } else {
                builder.beginControlFlow("if (!results.isEmpty())")
                        .addStatement("entity.$L(results.get(0))", setter)
                        .endControlFlow();
            }

            builder.nextControlFlow("catch (Exception e)")
                    .addStatement("throw new RuntimeException(\"Failed to load relation: $L\", e)", rel.type())
                    .endControlFlow();
        }

        return builder;
    }

    private String buildQuery(Direction direction, String relationType, String label, boolean limit) {
        String left = direction == Direction.INCOMING ? "<-" : "-";
        String right = direction == Direction.OUTGOING ? "->" : "-";
        String base = String.format("MATCH (n {id: $id})%s[:%s]%s(m:%s) RETURN m as node", left, relationType, right,
                label);
        return limit ? base + " LIMIT 1" : base;
    }

    private String resolveGetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String resolveSetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
