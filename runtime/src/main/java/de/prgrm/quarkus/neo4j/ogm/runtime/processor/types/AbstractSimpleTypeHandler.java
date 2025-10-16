package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;

import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public abstract class AbstractSimpleTypeHandler implements TypeHandler {

    protected abstract String getSupportedType();

    protected abstract String readMethod();

    public boolean supports(VariableElement field, Types types, Elements elements) {
        TypeMirror supportedType = elements.getTypeElement(getSupportedType()).asType();
        return types.isSameType(types.erasure(field.asType()), supportedType);
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of("$L.$L($L.get($S).$L());\n",
                targetVar,
                resolveSetterName(field),
                valueSource,
                getPropertyName(field),
                readMethod());
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        return CodeBlock.of("$L.put($S, $L.$L());\n",
                mapVar,
                getPropertyName(field),
                entityVar,
                resolveGetterName(field));
    }
}
