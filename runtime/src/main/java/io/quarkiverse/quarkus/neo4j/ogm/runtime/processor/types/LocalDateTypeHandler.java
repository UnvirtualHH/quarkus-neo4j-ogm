package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class LocalDateTypeHandler implements TypeHandler {
    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("java.time.LocalDate");
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of("$L.$L($L.get($S).asLocalDate());\n",
                targetVar, resolveSetterName(field), valueSource, getPropertyName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar) {
        return CodeBlock.of("params.put($S, $L.$L());\n",
                getPropertyName(field), entityVar, resolveGetterName(field));
    }
}
