package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class CharTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("java.lang.Character") ||
                field.asType().toString().equals("char");
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of(
                "$L.$L($L.get($S).asString().charAt(0));\n",
                targetVar,
                resolveSetterName(field),
                valueSource,
                getPropertyName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        return CodeBlock.of(
                "$L.put($S, $L.$L());\n",
                mapVar,
                getPropertyName(field),
                entityVar,
                resolveGetterName(field));
    }
}
