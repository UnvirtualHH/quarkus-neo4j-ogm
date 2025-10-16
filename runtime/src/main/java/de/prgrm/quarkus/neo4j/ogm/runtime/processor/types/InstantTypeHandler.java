package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class InstantTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return MapperUtil.isOfType(field, "java.time.Instant", types, elements);
    }

    @Override
    public boolean supportsType(String fqcn) {
        return "java.time.Instant".equals(fqcn);
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

    @Override
    public CodeBlock generateParameterConversion(String sourceVar) {
        return CodeBlock.of("java.time.ZonedDateTime.ofInstant($L, java.time.ZoneOffset.UTC)", sourceVar);
    }
}
