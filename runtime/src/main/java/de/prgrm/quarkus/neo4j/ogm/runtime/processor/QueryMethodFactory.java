package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.*;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Queries;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Query;

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

    private static MethodSpec buildMethodForQuery(
            TypeElement entityType,
            Query q,
            boolean reactive,
            ProcessingEnvironment env) {

        String methodName = q.name();
        String cypherQuery = q.cypher()
                .stripIndent()
                .trim();

        ReturnType returnType = q.returnType();

        boolean transactional = false;
        try {
            transactional = (boolean) Query.class.getMethod("transactional").invoke(q);
        } catch (Exception ignored) {
        }

        List<String> paramNames = extractParamNames(cypherQuery);

        Map<String, TypeName> explicitTypes = new LinkedHashMap<>();
        for (Query.Param param : q.paramTypes()) {
            explicitTypes.put(param.name(), getTypeFromAnnotation(param));
        }

        boolean hasRet = hasReturn(cypherQuery);
        boolean hasWrite = hasWriteClause(cypherQuery);

        MethodSpec.Builder mb = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addStatement("String query = $L", toTextBlock(cypherQuery));

        for (String p : paramNames) {
            TypeName resolved = explicitTypes.getOrDefault(p, resolveParamType(entityType, p, env));
            mb.addParameter(resolved, p);
        }

        CodeBlock mapArgs = buildMapArgs(paramNames, explicitTypes, entityType, env);

        String repoCall;
        if (!transactional) {
            if (hasWrite && !hasRet) {
                repoCall = "execute";
                return buildWriteOnlyMethod(reactive, mb, repoCall, paramNames, mapArgs);
            } else if (hasWrite) {
                repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
            } else {
                repoCall = (returnType == ReturnType.LIST) ? "query" : "querySingle";
            }
        } else {
            repoCall = (returnType == ReturnType.LIST) ? "executeQuery" : "executeReturning";
        }

        if (returnType == ReturnType.VOID) {
            return buildWriteOnlyMethod(reactive, mb, "executeQuery", paramNames, mapArgs);
        }

        if (returnType == ReturnType.BOOLEAN
                || returnType == ReturnType.LONG
                || returnType == ReturnType.STRING
                || returnType == ReturnType.SCALAR) {

            repoCall = "queryScalar";
            return buildScalarMethod(reactive, mb, repoCall, returnType, paramNames, mapArgs);
        }

        if (returnType == ReturnType.LIST) {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(
                        ClassName.get("io.smallrye.mutiny", "Uni"),
                        ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType()))));
            } else {
                mb.returns(ParameterizedTypeName.get(ClassName.get(List.class), TypeName.get(entityType.asType())));
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
        if (paramNames.isEmpty()) {
            mb.addStatement("return $L(query, $T.of())", repoCall, mapClass);
        } else {
            mb.addStatement("return $L(query, $T.of(" + mapArgs + "))", repoCall, mapClass);
        }

        return mb.build();
    }

    private static MethodSpec buildWriteOnlyMethod(
            boolean reactive,
            MethodSpec.Builder mb,
            String repoCall,
            List<String> paramNames,
            CodeBlock mapArgs) {

        ClassName mapClass = ClassName.get("java.util", "Map");

        if (reactive) {
            mb.returns(ParameterizedTypeName.get(
                    ClassName.get("io.smallrye.mutiny", "Uni"),
                    ClassName.get(Void.class)));

            if (paramNames.isEmpty()) {
                mb.addStatement("return $L(query, $T.of())", repoCall, mapClass);
            } else {
                mb.addStatement("return $L(query, $T.of(" + mapArgs + "))", repoCall, mapClass);
            }
        } else {
            mb.returns(TypeName.VOID);

            if (paramNames.isEmpty()) {
                mb.addStatement("$L(query, $T.of())", repoCall, mapClass);
            } else {
                mb.addStatement("$L(query, $T.of(" + mapArgs + "))", repoCall, mapClass);
            }
        }
        return mb.build();
    }

    private static MethodSpec buildScalarMethod(
            boolean reactive,
            MethodSpec.Builder mb,
            String repoCall,
            ReturnType returnType,
            List<String> paramNames,
            CodeBlock mapArgs) {

        TypeName scalarType;

        switch (returnType) {
            case BOOLEAN -> scalarType = TypeName.BOOLEAN;
            case LONG -> scalarType = TypeName.LONG;
            case STRING -> scalarType = ClassName.get(String.class);
            default -> scalarType = TypeName.get(Object.class);
        }

        if (reactive) {
            mb.returns(ParameterizedTypeName.get(
                    ClassName.get("io.smallrye.mutiny", "Uni"),
                    scalarType.box()));
        } else {
            mb.returns(scalarType);
        }

        ClassName mapClass = ClassName.get("java.util", "Map");

        String mapperLambda = switch (returnType) {
            case BOOLEAN -> "r -> r.get(0).asBoolean()";
            case LONG -> "r -> r.get(0).asLong()";
            case STRING -> "r -> r.get(0).asString()";
            default -> "r -> r.get(0).asObject()";
        };

        if (paramNames.isEmpty()) {
            mb.addStatement("return $L(query, $T.of(), $L)", repoCall, mapClass, mapperLambda);
        } else {
            mb.addStatement("return $L(query, $T.of(" + mapArgs + "), $L)", repoCall, mapClass, mapperLambda);
        }

        return mb.build();
    }

    private static CodeBlock buildMapArgs(
            List<String> paramNames,
            Map<String, TypeName> explicitTypes,
            TypeElement entityType,
            ProcessingEnvironment env) {

        CodeBlock.Builder cb = CodeBlock.builder();
        boolean first = true;

        for (String p : paramNames) {
            if (!first)
                cb.add(", ");
            cb.add("$S, ", p);

            TypeName t = explicitTypes.get(p);
            VariableElement field = findField(entityType, p);

            if (t != null) {
                Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(t.toString());
                if (handler.isPresent()) {
                    cb.add(handler.get().generateParameterConversion(p));
                } else {
                    cb.add("$L", p);
                }
            } else if (field != null) {
                Optional<TypeHandler> handler = TypeHandlerRegistry.findHandler(field, env.getTypeUtils(),
                        env.getElementUtils());
                if (handler.isPresent()) {
                    cb.add(handler.get().generateParameterConversion(p));
                } else {
                    cb.add("$L", p);
                }
            } else {
                cb.add("$L", p);
            }

            first = false;
        }

        return cb.build();
    }

    private static VariableElement findField(TypeElement entityType, String paramName) {
        String pLower = paramName.toLowerCase(Locale.ROOT);
        for (VariableElement field : ElementFilter.fieldsIn(entityType.getEnclosedElements())) {
            if (field.getSimpleName().toString().toLowerCase(Locale.ROOT).equals(pLower)) {
                return field;
            }
        }
        return null;
    }

    private static TypeName resolveParamType(TypeElement entityType, String paramName, ProcessingEnvironment env) {
        String pLower = paramName.toLowerCase(Locale.ROOT);
        for (Element e : entityType.getEnclosedElements()) {
            String name = e.getSimpleName().toString().toLowerCase(Locale.ROOT);
            if (name.equals(pLower)) {
                return TypeName.get(e.asType());
            }
            if (e instanceof ExecutableElement exec && name.equals("get" + pLower)) {
                return TypeName.get(exec.getReturnType());
            }
        }
        return TypeName.get(Object.class);
    }

    private static TypeName getTypeFromAnnotation(Query.Param param) {
        try {
            return TypeName.get(param.type());
        } catch (MirroredTypeException mte) {
            return TypeName.get(mte.getTypeMirror());
        }
    }

    static boolean hasReturn(String cypher) {
        return cypher.toUpperCase(Locale.ROOT).matches(".*\\bRETURN\\b.*");
    }

    static boolean hasWriteClause(String cypher) {
        return cypher.toUpperCase(Locale.ROOT)
                .matches(".*\\b(CREATE|MERGE|SET|DELETE|DETACH|REMOVE)\\b.*");
    }

    static List<String> extractParamNames(String cypher) {
        Matcher matcher = PARAM_PATTERN.matcher(cypher);
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    private static String toTextBlock(String query) {
        String clean = query.stripIndent().trim();
        return "\"\"\"\n" + clean + "\"\"\"";
    }
}
