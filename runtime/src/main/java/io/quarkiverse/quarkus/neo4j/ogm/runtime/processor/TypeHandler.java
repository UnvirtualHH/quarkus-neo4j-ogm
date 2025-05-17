package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.CodeBlock;

public interface TypeHandler {
    boolean supports(VariableElement field);

    CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource);

    CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar);
}