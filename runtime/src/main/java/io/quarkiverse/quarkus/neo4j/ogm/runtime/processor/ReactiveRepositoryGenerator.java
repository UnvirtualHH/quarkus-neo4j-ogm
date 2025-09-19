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

        // Build constructor
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addParameter(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepositoryRegistry"),
                        "reactiveRegistry");

        if (hasRelationships) {
            String loaderClassName = entityType.getSimpleName() + "ReactiveRelationLoader";
            constructorBuilder.addParameter(ClassName.get(packageName, loaderClassName), "relationLoader");
        }

        constructorBuilder.addParameter(
                ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RelationVisitor"),
                "relationVisitor");

        if (hasRelationships) {
            constructorBuilder
                    .addStatement("super(driver, $S, entityMapper, reactiveRegistry, relationLoader, relationVisitor)", label);
        } else {
            constructorBuilder.addStatement("super(driver, $S, entityMapper, reactiveRegistry, relationVisitor)", label);
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
                        .addStatement("reactiveRegistry.register($T.class, this)", entityType)
                        .build())
                .superclass(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepository"),
                        TypeName.get(entityType.asType())));

        List<MethodSpec> generatedMethods = new ArrayList<>();

        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation != null) {
            for (Query q : queriesAnnotation.value()) {
                String methodName = q.name();
                String cypherQuery = q.cypher();
                ReturnType returnType = q.returnType();

                boolean transactional = false;
                try {
                    transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
                } catch (Exception ignored) {
                }

                // Extract $param names
                Matcher matcher = PARAM_PATTERN.matcher(cypherQuery);
                Set<String> paramNames = new LinkedHashSet<>();
                while (matcher.find()) {
                    paramNames.add(matcher.group(1));
                }

                // Guardrail
                if (transactional && returnType == ReturnType.LIST) {
                    processingEnv.getMessager().printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "Cannot generate reactive method '" + methodName +
                                    "': @Query(transactional=true) with returnType=LIST not supported.");
                    continue;
                }

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("String query = $S", cypherQuery);

                for (String paramName : paramNames) {
                    methodBuilder.addParameter(Object.class, paramName);
                }

                if (returnType == ReturnType.LIST) {
                    methodBuilder
                            .returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                                    ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType()))));

                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return queryList(query)");
                    } else {
                        StringBuilder mapConstruction = new StringBuilder();
                        mapConstruction.append("$T<String, Object> params = new $T<>();");
                        for (String paramName : paramNames) {
                            mapConstruction.append("\nparams.put($S, $L);");
                        }
                        Object[] args = concatMapArgs(paramNames.toArray(new String[0]));
                        methodBuilder.addStatement(mapConstruction.toString(), args);
                        methodBuilder.addStatement("return queryList(query, params)");
                    }
                } else {
                    methodBuilder.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"),
                            TypeName.get(entityType.asType())));
                    String repoCall = transactional ? "executeSingle" : "querySingle";
                    if (paramNames.isEmpty()) {
                        methodBuilder.addStatement("return $L(query)", repoCall);
                    } else {
                        StringBuilder mapConstruction = new StringBuilder();
                        mapConstruction.append("$T<String, Object> params = new $T<>();");
                        for (String paramName : paramNames) {
                            mapConstruction.append("\nparams.put($S, $L);");
                        }
                        Object[] args = concatMapArgs(paramNames.toArray(new String[0]));
                        methodBuilder.addStatement(mapConstruction.toString(), args);
                        methodBuilder.addStatement("return $L(query, params)", repoCall);
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
                    "Generated reactive repository: " + fullClassName);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Failed to generate reactive repository: " + e.getMessage());
        }
    }

    private static Object[] concatMapArgs(String[] paramNames) {
        List<Object> args = new ArrayList<>();
        args.add(ClassName.get("java.util", "Map"));
        args.add(ClassName.get("java.util", "HashMap"));
        for (String paramName : paramNames) {
            args.add(paramName);
            args.add(paramName);
        }
        return args.toArray();
    }
}
