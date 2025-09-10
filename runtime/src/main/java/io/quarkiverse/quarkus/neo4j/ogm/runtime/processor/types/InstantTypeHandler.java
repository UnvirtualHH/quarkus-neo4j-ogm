package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class InstantTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("java.time.Instant");
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of(
                "if ($L.get($S) != null && !$L.get($S).isNull()) { " +
                        "  $L.$L($L.get($S).asZonedDateTime().toInstant()); " +
                        "} else { " +
                        "  $L.$L(null); " +
                        "} \n",
                valueSource, getPropertyName(field),
                valueSource, getPropertyName(field),
                targetVar, resolveSetterName(field), valueSource, getPropertyName(field),
                targetVar, resolveSetterName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        return CodeBlock.of(
                "if ($L.$L() != null) { " +
                        "  $L.put($S, java.time.ZonedDateTime.ofInstant($L.$L(), java.time.ZoneOffset.UTC)); " +
                        "} else { " +
                        "  $L.put($S, null); " +
                        "}\n",
                entityVar, resolveGetterName(field),
                mapVar, getPropertyName(field),
                entityVar, resolveGetterName(field),
                mapVar, getPropertyName(field));
    }
}
