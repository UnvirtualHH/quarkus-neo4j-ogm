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
        String property = getPropertyName(field);
        String setter = resolveSetterName(field);

        return CodeBlock.of(
                """
                        if ($L.get($S) != null && !$L.get($S).isNull()) {
                          var val = $L.get($S);
                          try {
                            if ("STRING".equals(val.type().name())) {
                              $L.$L(java.time.Instant.parse(val.asString()));
                            } else {
                              $L.$L(val.asZonedDateTime().toInstant());
                            }
                          } catch (Exception ex) {
                            throw new RuntimeException("Failed to parse Instant for property '" + $S + "': " + val + " (" + val.type().name() + ")", ex);
                          }
                        } else {
                          $L.$L(null);
                        }
                        """,
                valueSource, property,
                valueSource, property,
                valueSource, property,
                targetVar, setter,
                targetVar, setter,
                property,
                targetVar, setter);
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
