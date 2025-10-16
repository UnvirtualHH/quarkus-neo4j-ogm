package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.EnumType;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Enumerated;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class EnumTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return field.getAnnotation(Enumerated.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        ClassName enumType = ClassName.bestGuess(field.asType().toString());

        if (enumerated.value() == EnumType.ORDINAL) {
            return CodeBlock.of(
                    "$L.$L($T.values()[$L.get($S).asInt()]);\n",
                    targetVar,
                    resolveSetterName(field),
                    enumType,
                    valueSource,
                    getPropertyName(field));
        } else {
            return CodeBlock.of(
                    "$L.$L($T.valueOf($L.get($S).asString()));\n",
                    targetVar,
                    resolveSetterName(field),
                    enumType,
                    valueSource,
                    getPropertyName(field));
        }
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        String getter = resolveGetterName(field);
        String method = enumerated.value() == EnumType.ORDINAL ? "ordinal" : "name";

        return CodeBlock.of(
                "if ($L.$L() != null) $L.put($S, $L.$L().$L());\n",
                entityVar,
                getter,
                mapVar,
                getPropertyName(field),
                entityVar,
                getter,
                method);
    }
}
