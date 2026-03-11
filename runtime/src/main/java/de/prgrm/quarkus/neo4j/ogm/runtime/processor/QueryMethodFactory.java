package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

import com.palantir.javapoet.*;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Queries;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Query;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

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

        // --- Projection support: check for resultClass ---
        TypeMirror resultClassMirror = getResultClassMirror(q);
        boolean isProjection = resultClassMirror != null
                && resultClassMirror.getKind() != TypeKind.VOID;

        if (isProjection) {
            return buildProjectionMethod(reactive, mb, returnType, resultClassMirror,
                    hasWrite, hasRet, transactional, paramNames, mapArgs, env);
        }

        // --- Existing entity/scalar paths ---
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

    // ========================= Projection Methods =========================

    private static MethodSpec buildProjectionMethod(
            boolean reactive,
            MethodSpec.Builder mb,
            ReturnType returnType,
            TypeMirror resultClassMirror,
            boolean hasWrite,
            boolean hasRet,
            boolean transactional,
            List<String> paramNames,
            CodeBlock mapArgs,
            ProcessingEnvironment env) {

        TypeElement resultType = (TypeElement) env.getTypeUtils().asElement(resultClassMirror);
        ClassName resultClassName = ClassName.get(resultType);

        // Determine which repository method to call
        String repoCall;
        boolean isList = (returnType == ReturnType.LIST);
        if (transactional || hasWrite) {
            repoCall = isList ? "executeScalarList" : "executeScalar";
        } else {
            repoCall = isList ? "queryScalarList" : "queryScalar";
        }

        // Set return type
        if (isList) {
            TypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), resultClassName);
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"), listType));
            } else {
                mb.returns(listType);
            }
        } else {
            if (reactive) {
                mb.returns(ParameterizedTypeName.get(ClassName.get("io.smallrye.mutiny", "Uni"), resultClassName));
            } else {
                mb.returns(resultClassName);
            }
        }

        // Build the mapping lambda
        CodeBlock mapperLambda;
        if (resultType.getKind() == ElementKind.RECORD) {
            mapperLambda = buildRecordMapperLambda(resultType, resultClassName, env);
        } else {
            mapperLambda = buildDtoMapperLambda(resultType, resultClassName, env);
        }

        // Build the return statement
        ClassName mapClass = ClassName.get("java.util", "Map");
        if (paramNames.isEmpty()) {
            mb.addStatement("return $L(query, $T.of(), $L)", repoCall, mapClass, mapperLambda);
        } else {
            mb.addStatement("return $L(query, $T.of(" + mapArgs + "), $L)", repoCall, mapClass, mapperLambda);
        }

        return mb.build();
    }

    private static CodeBlock buildRecordMapperLambda(
            TypeElement recordType,
            ClassName resultClassName,
            ProcessingEnvironment env) {

        List<? extends RecordComponentElement> components = recordType.getRecordComponents();

        CodeBlock.Builder lambda = CodeBlock.builder();
        lambda.add("r -> new $T(", resultClassName);

        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                lambda.add(", ");
            }
            RecordComponentElement comp = components.get(i);
            String name = comp.getSimpleName().toString();
            TypeMirror type = comp.asType();
            lambda.add(generateValueExtraction(name, type));
        }

        lambda.add(")");
        return lambda.build();
    }

    private static CodeBlock buildDtoMapperLambda(
            TypeElement dtoType,
            ClassName resultClassName,
            ProcessingEnvironment env) {

        List<VariableElement> fields = ElementFilter.fieldsIn(dtoType.getEnclosedElements())
                .stream()
                .filter(f -> !f.getModifiers().contains(Modifier.STATIC))
                .toList();

        CodeBlock.Builder lambda = CodeBlock.builder();
        lambda.add("r -> {\n");
        lambda.indent();
        lambda.addStatement("$T _result = new $T()", resultClassName, resultClassName);

        for (VariableElement field : fields) {
            String name = field.getSimpleName().toString();
            String setter = "set" + MapperUtil.capitalize(name);
            TypeMirror type = field.asType();
            CodeBlock extraction = generateValueExtraction(name, type);

            if (type.getKind().isPrimitive()) {
                lambda.addStatement("_result.$L($L)", setter, extraction);
            } else {
                lambda.addStatement("if (!r.get($S).isNull()) _result.$L($L)", name, setter, extraction);
            }
        }

        lambda.addStatement("return _result");
        lambda.unindent();
        lambda.add("}");
        return lambda.build();
    }

    private static CodeBlock generateValueExtraction(String columnName, TypeMirror type) {
        String fqcn = type.toString();

        // Primitives — no null check needed
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT -> CodeBlock.of("r.get($S).asInt()", columnName);
                case LONG -> CodeBlock.of("r.get($S).asLong()", columnName);
                case BOOLEAN -> CodeBlock.of("r.get($S).asBoolean()", columnName);
                case DOUBLE -> CodeBlock.of("r.get($S).asDouble()", columnName);
                case FLOAT -> CodeBlock.of("(float) r.get($S).asDouble()", columnName);
                case SHORT -> CodeBlock.of("(short) r.get($S).asInt()", columnName);
                case BYTE -> CodeBlock.of("(byte) r.get($S).asInt()", columnName);
                case CHAR -> CodeBlock.of("r.get($S).asString().charAt(0)", columnName);
                default -> CodeBlock.of("r.get($S).asObject()", columnName);
            };
        }

        // Object types — wrap with null check
        CodeBlock conversion = switch (fqcn) {
            case "java.lang.String" ->
                CodeBlock.of("r.get($S).asString()", columnName);
            case "java.lang.Integer" ->
                CodeBlock.of("r.get($S).asInt()", columnName);
            case "java.lang.Long" ->
                CodeBlock.of("r.get($S).asLong()", columnName);
            case "java.lang.Boolean" ->
                CodeBlock.of("r.get($S).asBoolean()", columnName);
            case "java.lang.Double" ->
                CodeBlock.of("r.get($S).asDouble()", columnName);
            case "java.lang.Float" ->
                CodeBlock.of("(float) r.get($S).asDouble()", columnName);
            case "java.util.UUID" ->
                CodeBlock.of("$T.fromString(r.get($S).asString())", java.util.UUID.class, columnName);
            case "java.time.LocalDate" ->
                CodeBlock.of("r.get($S).asLocalDate()", columnName);
            case "java.time.LocalDateTime" ->
                CodeBlock.of("r.get($S).asLocalDateTime()", columnName);
            case "java.time.Instant" ->
                CodeBlock.of("r.get($S).asZonedDateTime().toInstant()", columnName);
            default ->
                CodeBlock.of("($T) r.get($S).asObject()", ClassName.bestGuess(fqcn), columnName);
        };

        return CodeBlock.of("r.get($S).isNull() ? null : $L", columnName, conversion);
    }

    private static TypeMirror getResultClassMirror(Query q) {
        try {
            q.resultClass();
            return null;
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
    }

    // ========================= Existing Methods =========================

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
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            seen.add(matcher.group(1));
        }
        return new ArrayList<>(seen);
    }

    private static String toTextBlock(String query) {
        String clean = query.stripIndent().trim();
        return "\"\"\"\n" + clean + "\"\"\"";
    }
}
