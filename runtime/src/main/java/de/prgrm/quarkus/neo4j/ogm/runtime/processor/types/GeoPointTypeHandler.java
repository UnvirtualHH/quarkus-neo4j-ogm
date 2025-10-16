package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil;

public class GeoPointTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        return MapperUtil.isOfType(field, "de.prgrm.quarkus.neo4j.ogm.runtime.model.GeoPoint", types, elements);
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.builder()
                .addStatement("$T point = $L.get($S).asPoint()",
                        ClassName.get("org.neo4j.driver.types", "Point"),
                        valueSource,
                        getPropertyName(field))
                .addStatement("$L.$L(new de.prgrm.quarkus.neo4j.ogm.runtime.model.GeoPoint(point.y(), point.x()))",
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
