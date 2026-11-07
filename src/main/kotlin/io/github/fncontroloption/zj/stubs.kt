package io.github.fncontroloption.zj

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.*

const val ZIG_STUB_VERSION = 12

class ZigStubDefinition : LanguageStubDefinition {
    override val builder = DefaultStubBuilder()
    override val stubVersion = ZIG_STUB_VERSION
}

object ZigStubIndexKeys {
    val CONTAINER_FIELDS = StubIndexKey.createIndexKey<String, ZigContainerFieldImpl>("zig.containerFields")
    val FN_DECLS = StubIndexKey.createIndexKey<String, ZigFnDecl>("zig.fnDecls")
    val VAR_DECLS = StubIndexKey.createIndexKey<String, ZigVarDecl>("zig.varDecls")
}

class ZigContainerFieldStubIndex : StringStubIndexExtension<ZigContainerFieldImpl>() {
    override fun getVersion() = super.getVersion() + ZIG_STUB_VERSION
    override fun getKey() = ZigStubIndexKeys.CONTAINER_FIELDS
}

class ZigFnDeclStubIndex : StringStubIndexExtension<ZigFnDecl>() {
    override fun getVersion() = super.getVersion() + ZIG_STUB_VERSION
    override fun getKey() = ZigStubIndexKeys.FN_DECLS
}

class ZigVarDeclStubIndex : StringStubIndexExtension<ZigVarDecl>() {
    override fun getVersion() = super.getVersion() + ZIG_STUB_VERSION
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

        registry.registerStubSerializer(ZigElementType.BUILTIN_CALL, ZigBuiltinCallStubSerializer)
        registry.registerStubFactory(ZigElementType.BUILTIN_CALL, ZigBuiltinCallStubFactory)
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
    StubBase<ZigContainerFieldImpl>(parent, ZigElementType.CONTAINER_FIELD), NamedStub<ZigContainerFieldImpl> {
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

object ZigContainerFieldStubFactory : StubElementFactory<ZigContainerFieldStub, ZigContainerFieldImpl> {
    override fun createStub(psi: ZigContainerFieldImpl, parentStub: StubElement<*>?) =
        ZigContainerFieldStub(parentStub, psi.name)

    override fun createPsi(stub: ZigContainerFieldStub) = ZigContainerFieldImpl(stub)
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
            && parseZigIdentifier(initializer.identifierElement) == name
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

// ---------- BUILTIN_CALL ---------- //

sealed class ZigBuiltinCallStub(parent: StubElement<*>?) :
    StubBase<ZigBuiltinCall>(parent, ZigElementType.BUILTIN_CALL)

class ZigImportStub(parent: StubElement<*>?, val target: String?) : ZigBuiltinCallStub(parent)

object ZigBuiltinCallStubSerializer : StubSerializer<ZigBuiltinCallStub> {
    override fun getExternalId() = "zig.BUILTIN_CALL"

    override fun serialize(stub: ZigBuiltinCallStub, dataStream: StubOutputStream) {
        when (stub) {
            is ZigImportStub -> {
                dataStream.writeUTFFast("@import")
                if (stub.target != null) {
                    dataStream.writeBoolean(true)
                    dataStream.writeUTFFast(stub.target)
                } else {
                    dataStream.writeBoolean(false)
                }
            }
        }
    }

    override fun deserialize(
        dataStream: StubInputStream,
        parentStub: StubElement<*>?
    ): ZigBuiltinCallStub {
        return when (dataStream.readUTFFast()) {
            "@import" -> {
                val target = when (dataStream.readBoolean()) {
                    true -> dataStream.readUTFFast()
                    else -> null
                }
                ZigImportStub(parentStub, target)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun indexStub(stub: ZigBuiltinCallStub, sink: IndexSink) {}
}

object ZigBuiltinCallStubFactory : StubElementFactory<ZigBuiltinCallStub, ZigBuiltinCall> {
    override fun shouldCreateStub(node: ASTNode): Boolean {
        val builtinElement = node.findChildByType(ZigTokenType.BUILTIN) ?: return false
        return when (builtinElement.text) {
            "@import" -> true
            else -> false
        }
    }

    override fun createStub(psi: ZigBuiltinCall, parentStub: StubElement<*>?): ZigBuiltinCallStub {
        return when (psi.builtinElement.text) {
            "@import" -> {
                val target = psi.arguments.firstOrNull() as? ZigStringExpression
                ZigImportStub(parentStub, target?.value)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun createPsi(stub: ZigBuiltinCallStub) = ZigBuiltinCall(stub)
}
