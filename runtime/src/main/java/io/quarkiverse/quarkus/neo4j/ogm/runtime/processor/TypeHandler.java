package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

public interface TypeHandler {
    default boolean supports(VariableElement field, Types types, Elements elements) {
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