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
import com.palantir.javapoet.CodeBlock;
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

        // Build constructor with proper parameter order: loader vor visitor
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addParameter(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RepositoryRegistry"),
                        "registry");

        if (hasRelationships) {
            String loaderClassName = entityType.getSimpleName() + "RelationLoader";
            constructorBuilder.addParameter(ClassName.get(packageName, loaderClassName), "relationLoader");
        }

        constructorBuilder.addParameter(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RelationVisitor"),
                "relationVisitor");

        if (hasRelationships) {
            constructorBuilder.addStatement("super(driver, $S, entityMapper, registry, relationLoader, relationVisitor)",
                    label);
        } else {
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

                // Reflective override (falls Annotation es explizit vorgibt)
                boolean transactional = false;
                try {
                    transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
                } catch (Exception ignored) {
                }

                // Extract $param names
                Matcher matcher = PARAM_PATTERN.matcher(cypherQuery);
                List<String> paramNames = new ArrayList<>();
                while (matcher.find()) {
                    paramNames.add(matcher.group(1));
                }

                // Heuristik basierend auf Cypher
                boolean hasRet = hasReturn(cypherQuery);
                boolean hasWrite = hasWriteClause(cypherQuery);

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("String query = $S", cypherQuery);

                for (String paramName : paramNames) {
                    methodBuilder.addParameter(Object.class, paramName);
                }

                // Build CodeBlock für Map.of(...)
                CodeBlock.Builder mapArgs = CodeBlock.builder();
                boolean first = true;
                for (String paramName : paramNames) {
                    if (!first) {
                        mapArgs.add(", ");
                    }
                    mapArgs.add("$S, $L", paramName, paramName);
                    first = false;
                }

                // Repo call bestimmen
                String repoCall;
                if (!transactional) {
                    if (hasWrite && !hasRet) {
                        // write-only
                        repoCall = "execute";
                        methodBuilder.returns(TypeName.VOID);
                        if (paramNames.isEmpty()) {
                            methodBuilder.addStatement("$L(query, $T.of())", repoCall, ClassName.get(Map.class));
                        } else {
                            methodBuilder.addStatement("$L(query, $T.of(" + mapArgs.build() + "))",
                                    repoCall, ClassName.get(Map.class));
                        }
                        generatedMethods.add(methodBuilder.build());
                        continue;
                    } else if (hasWrite && hasRet) {
                        // mixed
                        repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
                    } else {
                        // pure read
                        repoCall = (returnType == ReturnType.LIST) ? "query" : "querySingle";
                    }
                } else {
                    // Annotation-Override
                    repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
                }

                if (returnType == ReturnType.LIST) {
                    methodBuilder
                            .returns(ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType())));
                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return $L(query, $T.of())", repoCall, ClassName.get(Map.class));
                    } else {
                        methodBuilder.addStatement("return $L(query, $T.of(" + mapArgs.build() + "))",
                                repoCall, ClassName.get(Map.class));
                    }
                } else {
                    methodBuilder.returns(TypeName.get(entityType.asType()));
                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return $L(query, $T.of())", repoCall, ClassName.get(Map.class));
                    } else {
                        methodBuilder.addStatement("return $L(query, $T.of(" + mapArgs.build() + "))",
                                repoCall, ClassName.get(Map.class));
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

    private static boolean hasReturn(String cypher) {
        return cypher.toUpperCase(Locale.ROOT).matches(".*\\bRETURN\\b.*");
    }

    private static boolean hasWriteClause(String cypher) {
        return cypher.toUpperCase(Locale.ROOT)
                .matches(".*\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE)\\b.*");
    }
}
