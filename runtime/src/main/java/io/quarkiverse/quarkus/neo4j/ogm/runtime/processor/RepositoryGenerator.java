package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Queries;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Query;

public class RepositoryGenerator {

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

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addStatement("super(driver, $S, entityMapper)", label)
                .build();

        TypeSpec.Builder repositoryClassBuilder = TypeSpec.classBuilder(repositoryClassName)
                .addAnnotation(ApplicationScoped.class)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(noArgsConstructor)
                .addMethod(constructor)
                .superclass(ParameterizedTypeName.get(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "Repository"),
                        TypeName.get(entityType.asType())));

        List<MethodSpec> generatedMethods = new ArrayList<>();

        // Optional custom queries via @Queries
        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation != null) {
            for (Query query : queriesAnnotation.value()) {
                String methodName = query.name();
                String cypherQuery = query.cypher();
                ReturnType returnType = query.returnType();

                MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(String.class, "param")
                        .addStatement("String query = $S", cypherQuery);

                if (returnType == ReturnType.LIST) {
                    methodBuilder
                            .returns(ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType())));
                    methodBuilder.addStatement("return query(query, $T.of($S, param))",
                            ClassName.get("java.util", "Map"), "param");
                } else {
                    methodBuilder.returns(TypeName.get(entityType.asType()));
                    methodBuilder.addStatement("return querySingle(query, $T.of($S, param))",
                            ClassName.get("java.util", "Map"), "param");
                }

                generatedMethods.add(methodBuilder.build());
            }
        }

        for (MethodSpec method : generatedMethods) {
            repositoryClassBuilder.addMethod(method);
        }

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
}
