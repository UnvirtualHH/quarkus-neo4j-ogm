package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Queries;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Query;

public class ReactiveRepositoryGenerator {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$(\\w+)");

    public void generateReactiveRepository(
            String packageName,
            TypeElement entityType,
            String repositoryClassName,
            String mapperClassName,
            String label,
            ProcessingEnvironment processingEnv) {

        String fullClassName = packageName + "." + repositoryClassName;

        MethodSpec noArgsConstructor = MethodSpec.constructorBuilder()
                .addStatement("super()")
                .addModifiers(Modifier.PUBLIC)
                .build();

        // Check if the entity has relationships
        boolean hasRelationships = entityType.getEnclosedElements().stream()
                .anyMatch(e -> e.getAnnotation(io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship.class) != null);

        // Build constructor with RelationLoader if needed
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addParameter(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepositoryRegistry"),
                        "reactiveRegistry");

        if (hasRelationships) {
            String loaderClassName = entityType.getSimpleName() + "RelationLoader";
            constructorBuilder
                    .addParameter(ClassName.get(packageName, loaderClassName), "relationLoader")
                    .addStatement("super(driver, $S, entityMapper, reactiveRegistry)", label)
                    .addStatement("this.relationLoader = relationLoader");
        } else {
            constructorBuilder.addStatement("super(driver, $S, entityMapper, reactiveRegistry)", label);
        }

        MethodSpec constructor = constructorBuilder.build();

        TypeSpec.Builder repositoryClassBuilder = TypeSpec.classBuilder(repositoryClassName)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addModifiers(Modifier.PUBLIC)
                .addMethod(noArgsConstructor)
                .addMethod(constructor)
                .addMethod(MethodSpec.methodBuilder("registerSelf")
                        .addAnnotation(ClassName.get("jakarta.annotation", "PostConstruct"))
                        .addModifiers(Modifier.PRIVATE)
                        .addStatement("reactiveRegistry.register($T.class, this)", entityType)
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                        TypeName.get(entityType.asType())));

        List<MethodSpec> generatedMethods = new ArrayList<>();

        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation != null) {
            for (Query query : queriesAnnotation.value()) {
                String methodName = query.name();
                String cypherQuery = query.cypher();
                ReturnType returnType = query.returnType();

                Matcher matcher = PARAM_PATTERN.matcher(cypherQuery);
                Set<String> paramNames = new LinkedHashSet<>();
                while (matcher.find()) {
                    paramNames.add(matcher.group(1));
                }

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("String query = $S", cypherQuery);

                for (String paramName : paramNames) {
                    methodBuilder.addParameter(String.class, paramName);
                }

                StringBuilder mapBuilder = new StringBuilder();
                List<Object> mapArgs = new ArrayList<>();
                for (String paramName : paramNames) {
                    if (!mapBuilder.isEmpty()) {
                        mapBuilder.append(", ");
                    }
                    mapBuilder.append("$S, $L");
                    mapArgs.add(paramName); // Key
                    mapArgs.add(paramName); // Value
                }

                if (returnType == ReturnType.LIST) {
                    methodBuilder.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Multi"),
                            TypeName.get(entityType.asType())));
                    methodBuilder.addStatement("return query(query, $T.of(" + mapBuilder + "))",
                            concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                } else {
                    methodBuilder.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Uni"),
                            TypeName.get(entityType.asType())));
                    methodBuilder.addStatement("return querySingle(query, $T.of(" + mapBuilder + "))",
                            concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                }

                generatedMethods.add(methodBuilder.build());
            }
        }

        for (MethodSpec method : generatedMethods) {
            repositoryClassBuilder.addMethod(method);
        }

        MethodSpec getEntityTypeMethod = MethodSpec.methodBuilder("getEntityType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), TypeName.get(entityType.asType())))
                .addStatement("return $T.class", TypeName.get(entityType.asType()))
                .build();

        repositoryClassBuilder.addMethod(getEntityTypeMethod);

        TypeSpec repositoryClass = repositoryClassBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, repositoryClass).build();

        try {
            javaFile.writeTo(processingEnv.getFiler());
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.NOTE,
                    "Generated reactive repository: " + fullClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to generate reactive repository: " + e.getMessage());
        }
    }

    private static Object[] concatArrays(Object first, List<Object> rest) {
        Object[] result = new Object[1 + rest.size()];
        result[0] = first;
        for (int i = 0; i < rest.size(); i++) {
            result[i + 1] = rest.get(i);
        }
        return result;
    }
}
