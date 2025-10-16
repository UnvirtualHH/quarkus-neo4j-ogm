package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class LocalDateTimeTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return MapperUtil.isOfType(field, "java.time.LocalDateTime", types, elements);
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of(
                "$L.$L($L.get($S).asLocalDateTime());\n",
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
