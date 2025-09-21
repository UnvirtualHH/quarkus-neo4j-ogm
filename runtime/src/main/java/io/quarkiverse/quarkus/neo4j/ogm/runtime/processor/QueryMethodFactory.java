package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;

import com.palantir.javapoet.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Queries;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Query;

final class QueryMethodFactory {

    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    private QueryMethodFactory() {
    }

    static List<MethodSpec> buildQueryMethods(TypeElement entityType, boolean reactive, ProcessingEnvironment env) {
        List<MethodSpec> methods = new ArrayList<>();

        Queries queriesAnnotation = entityType.getAnnotation(Queries.class);
        if (queriesAnnotation == null) {
            return methods;
        }

        for (Query q : queriesAnnotation.value()) {
            methods.add(buildMethodForQuery(entityType, q, reactive, env));
        }

        return methods;
    }

    private static MethodSpec buildMethodForQuery(TypeElement entityType, Query q, boolean reactive,
            ProcessingEnvironment env) {
        String methodName = q.name();
        String cypherQuery = q.cypher();
        ReturnType returnType = q.returnType();

        boolean transactional = false;
        try {
            transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
        } catch (Exception ignored) {
        }

        List<String> paramNames = extractParamNames(cypherQuery);

        boolean hasRet = hasReturn(cypherQuery);
        boolean hasWrite = hasWriteClause(cypherQuery);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("String query = $S", cypherQuery);

        for (String p : paramNames) {
            TypeName resolved = resolveParamType(entityType, p, env);
            mb.addParameter(resolved, p);
        }

        CodeBlock mapArgs = buildMapArgs(paramNames);

        String repoCall;
        if (!transactional) {
            if (hasWrite && !hasRet) {
                repoCall = "execute";
                return buildWriteOnlyMethod(entityType, reactive, mb, repoCall, paramNames, mapArgs);
            } else if (hasWrite) {
                repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
            } else {
                repoCall = (returnType == ReturnType.LIST) ? "query" : "querySingle";
            }
        } else {
            repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
        }

        if (returnType == ReturnType.LIST) {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType()))));
            } else {
                mb.returns(ParameterizedTypeName.get(List.class));
            }
        } else {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        TypeName.get(entityType.asType())));
            } else {
                mb.returns(TypeName.get(entityType.asType()));
            }
        }

        ClassName mapClass = ClassName.get("java.util", "Map");
        ClassName hashMapClass = ClassName.get("java.util", "HashMap");

        if (paramNames.isEmpty()) {
            mb.addStatement("return $L(query, $T.of())", repoCall, mapClass);
        } else {
            mb.addStatement("return $L(query, new $T<>($T.of(" + mapArgs + ")))",
                    repoCall, hashMapClass, mapClass);
        }

        return mb.build();
    }

    private static MethodSpec buildWriteOnlyMethod(
            TypeElement entityType,
            boolean reactive,
            MethodSpec.Builder mb,
            String repoCall,
            List<String> paramNames,
            CodeBlock mapArgs) {

        ClassName mapClass = ClassName.get("java.util", "Map");
        ClassName hashMapClass = ClassName.get("java.util", "HashMap");

        if (reactive) {
            mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"), ClassName.get(Void.class)));
            if (paramNames.isEmpty()) {
                mb.addStatement("return $L(query, $T.of())", repoCall, mapClass);
            } else {
                mb.addStatement("return $L(query, new $T<>($T.of(" + mapArgs + ")))",
                        repoCall, hashMapClass, mapClass);
            }
        } else {
            mb.returns(TypeName.VOID);
            if (paramNames.isEmpty()) {
                mb.addStatement("$L(query, $T.of())", repoCall, mapClass);
            } else {
                mb.addStatement("$L(query, new $T<>(Map.of(" + mapArgs + ")))",
                        repoCall, hashMapClass);
            }
        }
        return mb.build();
    }

    private static List<String> extractParamNames(String cypher) {
        Matcher matcher = PARAM_PATTERN.matcher(cypher);
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    private static CodeBlock buildMapArgs(List<String> paramNames) {
        CodeBlock.Builder cb = CodeBlock.builder();
        boolean first = true;
        for (String p : paramNames) {
            if (!first)
                cb.add(", ");
            cb.add("$S, $L", p, p);
            first = false;
        }
        return cb.build();
    }

    private static TypeName resolveParamType(TypeElement entityType, String paramName, ProcessingEnvironment env) {
        for (Element e : entityType.getEnclosedElements()) {
            String name = e.getSimpleName().toString().toLowerCase(Locale.ROOT);
            if (name.equals(paramName.toLowerCase(Locale.ROOT))) {
                return TypeName.get(e.asType());
            }
            if (name.equals("get" + paramName.toLowerCase(Locale.ROOT))) {
                return TypeName.get(((ExecutableElement) e).getReturnType());
            }
        }
        return TypeName.get(Object.class);
    }

    static boolean hasReturn(String cypher) {
        return cypher.toUpperCase(Locale.ROOT).matches(".*\\bRETURN\\b.*");
    }

    static boolean hasWriteClause(String cypher) {
        return cypher.toUpperCase(Locale.ROOT)
                .matches(".*\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE)\\b.*");
    }
}
