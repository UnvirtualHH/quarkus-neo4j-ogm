package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.EnumType;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Enumerated;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class EnumTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.getAnnotation(Enumerated.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        ClassName enumType = ClassName.bestGuess(field.asType().toString());

        String accessor = enumerated.value() == EnumType.ORDINAL
                ? "values()[%s.get($S).asInt()]"
                : "valueOf(%s.get($S).asString())";

        return CodeBlock.of("$L.$L($T." + accessor + ");\n",
                targetVar,
                resolveSetterName(field),
                enumType,
                valueSource,
                getPropertyName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        String getter = resolveGetterName(field);
        String method = enumerated.value() == EnumType.ORDINAL ? "ordinal" : "name";

        return CodeBlock.of("if ($L.$L() != null) params.put($S, $L.$L().$L());\n",
                entityVar,
                getter,
                getPropertyName(field),
                entityVar,
                getter,
                method);
    }
}
