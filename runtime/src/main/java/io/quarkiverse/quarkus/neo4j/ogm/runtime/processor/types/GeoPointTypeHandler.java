package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class GeoPointTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint");
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.builder()
                .addStatement("$T point = $L.get($S).asPoint()",
                        ClassName.get("org.neo4j.driver.types", "Point"),
                        valueSource,
                        getPropertyName(field))
                .addStatement("$L.$L(new io.quarkiverse.quarkus.neo4j.ogm.runtime.model.GeoPoint(point.y(), point.x()))",
                        targetVar,
                        resolveSetterName(field))
                .build();
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        String getter = resolveGetterName(field);
        String property = getPropertyName(field);

        return CodeBlock.builder()
                .beginControlFlow("if ($L.$L() != null)", entityVar, getter)
                .addStatement("var geo = $L.$L()", entityVar, getter)
                .addStatement(
                        "var point = org.neo4j.driver.internal.InternalPoint.of(4326, geo.longitude(), geo.latitude())")
                .addStatement("$L.put($S, point)", mapVar, property)
                .endControlFlow()
                .build();
    }
}
