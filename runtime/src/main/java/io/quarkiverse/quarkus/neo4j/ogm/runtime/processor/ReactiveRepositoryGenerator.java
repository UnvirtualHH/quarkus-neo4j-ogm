package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import jakarta.annotation.PostConstruct;
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

        // Detect relationships on the entity
        boolean hasRelationships = entityType.getEnclosedElements().stream()
                .anyMatch(e -> e.getAnnotation(io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Relationship.class) != null);

        // Constructor: always inject RelationVisitor
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addAnnotation(Inject.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get("org.neo4j.driver", "Driver"), "driver")
                .addParameter(ClassName.get(packageName, mapperClassName), "entityMapper")
                .addParameter(
                        ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "ReactiveRepositoryRegistry"),
                        "reactiveRegistry")
                .addParameter(ClassName.get("io.quarkiverse.quarkus.neo4j.ogm.runtime.repository", "RelationVisitor"),
                        "relationVisitor");

        if (hasRelationships) {
            String loaderClassName = entityType.getSimpleName() + "ReactiveRelationLoader";
            ctor.addParameter(ClassName.get(packageName, loaderClassName), "relationLoader")
                    .addStatement("super(driver, $S, entityMapper, reactiveRegistry, relationLoader, relationVisitor)", label);
        } else {
            ctor.addStatement("super(driver, $S, entityMapper, reactiveRegistry, relationVisitor)", label);
        }

        TypeSpec.Builder repoClass = TypeSpec.classBuilder(repositoryClassName)
                .addAnnotation(ApplicationScoped.class)
                .addAnnotation(ClassName.get("io.quarkus.runtime", "Startup"))
                .addModifiers(Modifier.PUBLIC)
                .addMethod(noArgsConstructor)
                .addMethod(ctor.build())
                .addMethod(MethodSpec.methodBuilder("registerSelf")
                        .addAnnotation(PostConstruct.class)
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
                String cypher = q.cypher();
                ReturnType returnType = q.returnType();

                // Try to read boolean transactional() reflectively (keeps bw-compat if not present at runtime)
                boolean transactional = false;
                try {
                    transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
                } catch (Exception ignored) {
                    // default false
                }

                // Extract $param names in Cypher
                Matcher matcher = PARAM_PATTERN.matcher(cypher);
                Set<String> paramNames = new LinkedHashSet<>();
                while (matcher.find()) {
                    paramNames.add(matcher.group(1));
                }

                // Guardrail: no write-tx multi-return supported by current API
                if (transactional && returnType == ReturnType.LIST) {
                    processingEnv.getMessager().printMessage(
                            javax.tools.Diagnostic.Kind.ERROR,
                            "Cannot generate method '" + methodName + "': @Query(transactional=true) with returnType=LIST " +
                                    "is not supported because ReactiveRepository exposes only executeSingle(...) and execute(...) (void). "
                                    +
                                    "Either change returnType to SINGLE or extend ReactiveRepository with a write-tx list API.");
                    // Skip generating this method to avoid producing uncompilable code
                    continue;
                }

                MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("String query = $S", cypher);

                // Parameters as Object (allows non-string)
                for (String p : paramNames) {
                    mb.addParameter(Object.class, p);
                }

                // Build Map.of("k1", v1, "k2", v2, ...)
                StringBuilder mapFmt = new StringBuilder();
                List<Object> mapArgs = new ArrayList<>();
                for (String p : paramNames) {
                    if (!mapFmt.isEmpty())
                        mapFmt.append(", ");
                    mapFmt.append("$S, $L");
                    mapArgs.add(p);
                    mapArgs.add(p);
                }

                if (returnType == ReturnType.LIST) {
                    // read-only multi
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Multi"),
                            TypeName.get(entityType.asType())));
                    if (paramNames.isEmpty()) {
                        mb.addStatement("return query(query)");
                    } else {
                        mb.addStatement("return query(query, $T.of(" + mapFmt + "))",
                                concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                    }
                } else {
                    // SINGLE: use executeSingle for transactional, querySingle otherwise
                    mb.returns(ParameterizedTypeName.get(
                            ClassName.get("io.smallrye.mutiny", "Uni"),
                            TypeName.get(entityType.asType())));
                    String repoCall = transactional ? "executeSingle" : "querySingle";
                    if (paramNames.isEmpty()) {
                        mb.addStatement("return $L(query)", repoCall);
                    } else {
                        mb.addStatement("return $L(query, $T.of(" + mapFmt + "))",
                                repoCall, concatArrays(ClassName.get("java.util", "Map"), mapArgs));
                    }
                }

                generatedMethods.add(mb.build());
            }
        }

        // Add generated methods
        for (MethodSpec m : generatedMethods) {
            repoClass.addMethod(m);
        }

        // getEntityType()
        repoClass.addMethod(MethodSpec.methodBuilder("getEntityType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), TypeName.get(entityType.asType())))
                .addStatement("return $T.class", TypeName.get(entityType.asType()))
                .build());

        // Write file
        JavaFile javaFile = JavaFile.builder(packageName, repoClass.build()).build();
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
