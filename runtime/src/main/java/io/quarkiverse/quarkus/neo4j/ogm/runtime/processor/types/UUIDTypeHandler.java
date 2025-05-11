package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class UUIDTypeHandler implements TypeHandler {
    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("java.util.UUID");
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of("$L.$L($T.fromString($L.get($S).asString()));\n",
                targetVar, resolveSetterName(field),
                ClassName.get("java.util", "UUID"),
                valueSource, getPropertyName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar) {
        return CodeBlock.of("if ($L.$L() != null) params.put($S, $L.$L().toString());\n",
                entityVar, resolveGetterName(field), getPropertyName(field), entityVar, resolveGetterName(field));
    }
}
