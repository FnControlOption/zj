@file:Suppress("UnstableApiUsage")

package io.github.fncontroloption.zig

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.DefaultStubBuilder
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.LanguageStubDefinition
import com.intellij.psi.stubs.NamedStub
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StringStubIndexExtension
import com.intellij.psi.stubs.StubBase
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.stubs.StubElementFactory
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.stubs.StubRegistry
import com.intellij.psi.stubs.StubRegistryExtension
import com.intellij.psi.stubs.StubSerializer
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElementType
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFileElementType
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigVarDecl

class ZigStubDefinition : LanguageStubDefinition {
    override val builder = DefaultStubBuilder()
    override val stubVersion = 9
}

object ZigStubIndexKeys {
    val CONTAINER_FIELDS = StubIndexKey.createIndexKey<String, ZigContainerField>("zig.containerFields")
    val FN_DECLS = StubIndexKey.createIndexKey<String, ZigFnDecl>("zig.fnDecls")
    val VAR_DECLS = StubIndexKey.createIndexKey<String, ZigVarDecl>("zig.varDecls")
}

class ZigContainerFieldStubIndex : StringStubIndexExtension<ZigContainerField>() {
    override fun getVersion() = super.getVersion() + 1
    override fun getKey() = ZigStubIndexKeys.CONTAINER_FIELDS
}

class ZigFnDeclStubIndex : StringStubIndexExtension<ZigFnDecl>() {
    override fun getVersion() = super.getVersion() + 1
    override fun getKey() = ZigStubIndexKeys.FN_DECLS
}

class ZigVarDeclStubIndex : StringStubIndexExtension<ZigVarDecl>() {
    override fun getVersion() = super.getVersion() + 1
    override fun getKey() = ZigStubIndexKeys.VAR_DECLS
}

class ZigStubRegistryExtension : StubRegistryExtension {
    override fun register(registry: StubRegistry) {
        registry.registerStubSerializer(ZigFileElementType, ZigFileStubSerializer)

        registry.registerStubSerializer(ZigElementType.CONTAINER_FIELD, ZigContainerFieldStubSerializer)
        registry.registerStubFactory(ZigElementType.CONTAINER_FIELD, ZigContainerFieldStubFactory)

        registry.registerStubSerializer(ZigElementType.FN_DECL, ZigFnDeclStubSerializer)
        registry.registerStubFactory(ZigElementType.FN_DECL, ZigFnDeclStubFactory)

        registry.registerStubSerializer(ZigElementType.VAR_DECL, ZigVarDeclStubSerializer)
        registry.registerStubFactory(ZigElementType.VAR_DECL, ZigVarDeclStubFactory)
    }
}

// ---------- FILE ---------- //

class ZigFileStub(file: ZigFile?) : PsiFileStubImpl<ZigFile>(file)

object ZigFileStubSerializer : StubSerializer<ZigFileStub> {
    override fun getExternalId() = "zig.FILE"

    override fun serialize(stub: ZigFileStub, dataStream: StubOutputStream) {}

    override fun deserialize(dataStream: StubInputStream, parentStub: StubElement<*>?) =
        ZigFileStub(null)

    override fun indexStub(stub: ZigFileStub, sink: IndexSink) {}
}

// ---------- CONTAINER_FIELD ---------- //

class ZigContainerFieldStub(parent: StubElement<*>?, name: String?) :
    StubBase<ZigContainerField>(parent, ZigElementType.CONTAINER_FIELD), NamedStub<ZigContainerField> {
    private val myName = name
    override fun getName() = myName
}

object ZigContainerFieldStubSerializer : StubSerializer<ZigContainerFieldStub> {
    override fun getExternalId() = "zig.CONTAINER_FIELD"

    override fun serialize(stub: ZigContainerFieldStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
    }

    override fun deserialize(
        dataStream: StubInputStream,
        parentStub: StubElement<*>?
    ): ZigContainerFieldStub {
        val name = dataStream.readNameString()
        return ZigContainerFieldStub(parentStub, name)
    }

    override fun indexStub(stub: ZigContainerFieldStub, sink: IndexSink) {
        val name = stub.name
        if (name != null) {
            sink.occurrence(ZigStubIndexKeys.CONTAINER_FIELDS, name)
        }
    }
}

object ZigContainerFieldStubFactory : StubElementFactory<ZigContainerFieldStub, ZigContainerField> {
    override fun createStub(psi: ZigContainerField, parentStub: StubElement<*>?) =
        ZigContainerFieldStub(parentStub, psi.name)

    override fun createPsi(stub: ZigContainerFieldStub) = ZigContainerField(stub)
}

// ---------- FN_DECL ---------- //

class ZigFnDeclStub(parent: StubElement<*>?, name: String?) :
    StubBase<ZigFnDecl>(parent, ZigElementType.FN_DECL), NamedStub<ZigFnDecl> {
    private val myName = name
    override fun getName() = myName
}

object ZigFnDeclStubSerializer : StubSerializer<ZigFnDeclStub> {
    override fun getExternalId() = "zig.FN_DECL"

    override fun serialize(stub: ZigFnDeclStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
    }

    override fun deserialize(
        dataStream: StubInputStream,
        parentStub: StubElement<*>?
    ): ZigFnDeclStub {
        val name = dataStream.readNameString()
        return ZigFnDeclStub(parentStub, name)
    }

    override fun indexStub(stub: ZigFnDeclStub, sink: IndexSink) {
        val name = stub.name
        if (name != null) {
            sink.occurrence(ZigStubIndexKeys.FN_DECLS, name)
        }
    }
}

object ZigFnDeclStubFactory : StubElementFactory<ZigFnDeclStub, ZigFnDecl> {
    override fun createStub(psi: ZigFnDecl, parentStub: StubElement<*>?) =
        ZigFnDeclStub(parentStub, psi.name)

    override fun createPsi(stub: ZigFnDeclStub) = ZigFnDecl(stub)
}

// ---------- VAR_DECL ---------- //

class ZigVarDeclStub(parent: StubElement<*>?, name: String?) :
    StubBase<ZigVarDecl>(parent, ZigElementType.VAR_DECL), NamedStub<ZigVarDecl> {
    private val myName = name
    override fun getName() = myName
}

object ZigVarDeclStubSerializer : StubSerializer<ZigVarDeclStub> {
    override fun getExternalId() = "zig.VAR_DECL"

    override fun serialize(stub: ZigVarDeclStub, dataStream: StubOutputStream) {
        dataStream.writeName(stub.name)
    }

    override fun deserialize(
        dataStream: StubInputStream,
        parentStub: StubElement<*>?
    ): ZigVarDeclStub {
        val name = dataStream.readNameString()
        return ZigVarDeclStub(parentStub, name)
    }

    override fun indexStub(stub: ZigVarDeclStub, sink: IndexSink) {
        val name = stub.name
        if (name != null) {
            sink.occurrence(ZigStubIndexKeys.VAR_DECLS, name)
        }
    }
}

object ZigVarDeclStubFactory : StubElementFactory<ZigVarDeclStub, ZigVarDecl> {
    override fun createStub(psi: ZigVarDecl, parentStub: StubElement<*>?) =
        ZigVarDeclStub(parentStub, psi.name)

    override fun createPsi(stub: ZigVarDeclStub) = ZigVarDecl(stub)

    override fun shouldCreateStub(node: ASTNode): Boolean {
        val element = node.psi as ZigVarDecl
        val name = element.name ?: return false
        val initializer = element.initializer
        if (element.mutability == ZigMutability.CONST
            && initializer is ZigFieldAccess
            && initializer.identifierElement?.text == name
        ) {
            return false
        }
        if (element.isPub) {
            return true
        }
        if (initializer is ZigBuiltinCall && initializer.builtinElement.text == "@import") {
            return false
        }
        return !element.isLocal
    }
}
