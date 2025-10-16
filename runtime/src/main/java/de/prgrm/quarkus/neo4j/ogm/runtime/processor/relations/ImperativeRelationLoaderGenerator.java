package de.prgrm.quarkus.neo4j.ogm.runtime.processor.relations;

import java.io.IOException;
import java.util.ArrayList;
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

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeEntity;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Relationship;

public class ImperativeRelationLoaderGenerator extends AbstractRelationLoaderGenerator {

    @Override
    public void generateRelationLoader(
            String packageName,
            TypeElement entityType,
            String loaderClassName,
            ProcessingEnvironment processingEnv) {

        boolean hasRelationships = ElementFilter.fieldsIn(entityType.getEnclosedElements()).stream()
                .anyMatch(f -> f.getAnnotation(Relationship.class) != null
                        && shouldFetchRelationship(f.getAnnotation(Relationship.class)));

        Types types = processingEnv.getTypeUtils();
        TypeMirror listType = processingEnv.getElementUtils()
                .getTypeElement("java.util.List").asType();

        TypeSpec.Builder classBuilder = buildImperativeClassBase(loaderClassName, entityType.getQualifiedName().toString());

        classBuilder.addMethod(buildImperativeLoaderWithDepth(
                entityType,
                entityType.getQualifiedName().toString(),
                types,
                listType).build());

        classBuilder.addMethod(buildImperativeRecursiveLoader().build());

        try {
            JavaFile.builder(packageName, classBuilder.build())
                    .build()
                    .writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "Generated imperative relation loader: " + packageName + "." + loaderClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate imperative relation loader: " + e.getMessage());
        }
    }

    private TypeSpec.Builder buildImperativeClassBase(String loaderClassName, String qualifiedName) {
        ClassName repositoryRegistryClass = ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.repository",
                "RepositoryRegistry");
        ClassName relationVisitorClass = ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.repository",
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

        String sourceLabel;
        if (nodeAnnotation != null && !nodeAnnotation.label().isEmpty()) {
            sourceLabel = nodeAnnotation.label();
        } else {
            sourceLabel = entityType.getSimpleName().toString();
        }

        MethodSpec.Builder builder = MethodSpec.methodBuilder("loadRelations")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(ClassName.bestGuess(qualifiedName), "entity")
                .addParameter(int.class, "currentDepth")
                .addStatement("Object id = getNodeId(entity)")
                .beginControlFlow("if (id == null)")
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("if (!relationVisitor.shouldVisit(entity, currentDepth))")
                .addStatement("return")
                .endControlFlow()
                .addStatement("relationVisitor.markVisited(entity)");
        ;

        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            Relationship rel = field.getAnnotation(Relationship.class);
            if (rel == null || !shouldFetchRelationship(rel))
                continue;

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
                builder.addStatement("var results = repository.query(query, $T.of($S, id))", Map.class, "id");
                builder.addStatement("if (entity.$L() == null) entity.$L(new $T<>())", getter, setter, ArrayList.class);
                builder.addStatement("entity.$L().addAll(results)", getter);
                builder.beginControlFlow("for (var item : entity.$L())", getter);
                builder.addStatement("loadRelationRecursively(item, currentDepth + 1)");
                builder.endControlFlow();
            } else {
                builder.addStatement("var result = repository.query(query, $T.of($S, id)).stream().findFirst().orElse(null)",
                        Map.class, "id");
                builder.addStatement("entity.$L(result)", setter);
                builder.beginControlFlow("if (result != null)");
                builder.addStatement("loadRelationRecursively(result, currentDepth + 1)");
                builder.endControlFlow();
            }

            builder.nextControlFlow("catch (Exception e)")
                    .addStatement("throw new RuntimeException($S, e)", "Failed to load relation: " + rel.type())
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
                                ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (repository != null)")
                .addStatement("$T loader = ($T) repository.getRelationLoader()",
                        ParameterizedTypeName.get(
                                ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.mapping", "RelationLoader"),
                                ClassName.get(Object.class)),
                        ParameterizedTypeName.get(
                                ClassName.get("de.prgrm.quarkus.neo4j.ogm.runtime.mapping", "RelationLoader"),
                                ClassName.get(Object.class)))
                .beginControlFlow("if (loader != null)")
                .addStatement("loader.loadRelations(entity, currentDepth + 1)")
                .endControlFlow()
                .endControlFlow()
                .nextControlFlow("catch (Exception e)")
                .addStatement("// Ignore errors in recursive loading")
                .endControlFlow();
    }
}
