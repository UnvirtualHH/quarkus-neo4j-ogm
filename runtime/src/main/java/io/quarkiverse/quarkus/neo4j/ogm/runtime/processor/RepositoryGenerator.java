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

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Queries;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Query;

public class RepositoryGenerator {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$(\\w+)");

    public void generateRepository(
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

        // Build constructor with RelationVisitor injection - ALWAYS include RelationVisitor
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addParameter(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RepositoryRegistry"),
                        "registry")
                .addParameter(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RelationVisitor"),
                        "relationVisitor");

        if (hasRelationships) {
            String loaderClassName = entityType.getSimpleName() + "RelationLoader";
            constructorBuilder
                    .addParameter(ClassName.get(packageName, loaderClassName), "relationLoader")
                    .addStatement("super(driver, $S, entityMapper, registry, relationLoader, relationVisitor)", label);
        } else {
            // Even without relationships, we need to pass the RelationVisitor
            constructorBuilder.addStatement("super(driver, $S, entityMapper, registry, relationVisitor)", label);
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
                        .addStatement("registry.register($T.class, this)", entityType)
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                        TypeName.get(entityType.asType())));

        List<MethodSpec> generatedMethods = new ArrayList<>();

        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation != null) {
            for (Query q : queriesAnnotation.value()) {
                String methodName = q.name();
                String cypherQuery = q.cypher();
                ReturnType returnType = q.returnType();

                // Try to read boolean transactional() reflectively (keeps bw-compat if not present at runtime)
                boolean transactional = false;
                try {
                    transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
                } catch (Exception ignored) {
                    // default false
                }

                // Extract $param names in Cypher
                Matcher matcher = PARAM_PATTERN.matcher(cypherQuery);
                Set<String> paramNames = new LinkedHashSet<>();
                while (matcher.find()) {
                    paramNames.add(matcher.group(1));
                }

                // Guardrail: no write-tx list-return supported by current non-reactive API
                if (transactional && returnType == ReturnType.LIST) {
                    processingEnv.getMessager().printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "Cannot generate method '" + methodName + "': @Query(transactional=true) with returnType=LIST " +
                                    "is not supported because Repository exposes only executeSingle(...) and execute(...) (void). "
                                    +
                                    "Either change returnType to SINGLE or extend Repository with a write-tx list API.");
                    // Skip generating this method
                    continue;
                }

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("String query = $S", cypherQuery);

                // Generate method parameters as Object (allows non-string values)
                for (String paramName : paramNames) {
                    methodBuilder.addParameter(Object.class, paramName);
                }

                // Build Map.of("k1", v1, "k2", v2, ...)
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
                    // Read-only list
                    methodBuilder
                            .returns(ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType())));
                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return query(query)");
                    } else {
                        methodBuilder.addStatement("return query(query, $T.of(" + mapBuilder + "))",
                                concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                    }
                } else {
                    // SINGLE: use executeSingle for transactional, querySingle otherwise
                    methodBuilder.returns(TypeName.get(entityType.asType()));
                    String repoCall = transactional ? "executeSingle" : "querySingle";
                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return $L(query)", repoCall);
                    } else {
                        methodBuilder.addStatement("return $L(query, $T.of(" + mapBuilder + "))",
                                repoCall, concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                    }
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
                    "Generated repository: " + fullClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to generate repository: " + e.getMessage());
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
