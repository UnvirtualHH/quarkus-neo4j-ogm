package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.CodeBlock;

public interface TypeHandler {
    default boolean supports(VariableElement field) {
        return false;
    }

    default boolean supportsType(String fqcn) {
        return false;
    }

    CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource);

    CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar);

    default CodeBlock generateParameterConversion(String paramName) {
        return CodeBlock.of("$L", paramName);
    }
}