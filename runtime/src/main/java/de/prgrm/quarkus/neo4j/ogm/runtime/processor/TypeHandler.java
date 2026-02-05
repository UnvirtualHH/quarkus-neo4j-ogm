package de.prgrm.quarkus.neo4j.ogm.runtime.processor;

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

    /**
     * Generates code to apply post-load converters (for context-aware converters).
     * This is called after relationships have been loaded.
     *
     * @param field the field to generate post-load converter code for
     * @param entityVar the variable name of the entity
     * @return the code block, or null if this handler doesn't need post-load conversion
     */
    default CodeBlock generatePostLoadConverterCode(VariableElement field, String entityVar) {
        return null; // Most handlers don't need this
    }
}