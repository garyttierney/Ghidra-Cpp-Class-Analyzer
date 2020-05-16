package ghidra.program.database.data.rtti.manager;

import java.io.IOException;

import ghidra.app.cmd.data.rtti.ClassTypeInfo;
import ghidra.app.cmd.data.rtti.GnuVtable;
import ghidra.app.cmd.data.rtti.TypeInfo;
import ghidra.app.cmd.data.rtti.Vtable;
import ghidra.app.cmd.data.rtti.gcc.TypeInfoUtils;
import ghidra.app.cmd.data.rtti.gcc.UnresolvedClassTypeInfoException;
import ghidra.app.cmd.data.rtti.gcc.VtableUtils;
import ghidra.program.database.data.rtti.manager.caches.ArchivedRttiCachePair;
import ghidra.program.database.data.rtti.manager.recordmanagers.ArchiveRttiRecordManager;
import ghidra.program.database.data.rtti.typeinfo.ArchivedClassTypeInfo;
import ghidra.program.database.data.rtti.typeinfo.ClassTypeInfoDB;
import ghidra.program.database.data.rtti.typeinfo.GnuClassTypeInfoDB;
import ghidra.program.database.data.rtti.vtable.ArchivedGnuVtable;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.util.Msg;

import db.StringField;

abstract class ArchiveRttiRecordWorker extends
		AbstractRttiRecordWorker<ArchivedClassTypeInfo, ArchivedGnuVtable>
		implements ArchiveRttiRecordManager {

	private static final String MANGLED_TYPEINFO_PREFIX = "_ZTI";

	private final FileArchiveClassTypeInfoManager manager;

	ArchiveRttiRecordWorker(FileArchiveClassTypeInfoManager manager, RttiTablePair tables,
			ArchivedRttiCachePair caches) {
		super(tables, caches);
		this.manager = manager;
	}

	@Override
	public FileArchiveClassTypeInfoManager getManager() {
		return manager;
	}

	@Override
	long getTypeKey(ClassTypeInfo type) {
		if (type instanceof ArchivedClassTypeInfo) {
			return ((ArchivedClassTypeInfo) type).getKey();
		}
		return getTypeKey(TypeInfoUtils.getSymbolName(type));
	}

	@Override
	long getVtableKey(Vtable vtable) {
		return getVtableKey(VtableUtils.getSymbolName(vtable));
	}

	@Override
	ArchivedClassTypeInfo buildType(db.Record record) {
		return new ArchivedClassTypeInfo(this, record);
	}

	@Override
	ArchivedClassTypeInfo buildType(ClassTypeInfo type, db.Record record) {
		if (type instanceof GnuClassTypeInfoDB) {
			return new ArchivedClassTypeInfo(this, (GnuClassTypeInfoDB) type, record);
		}
		return null;
	}

	@Override
	public void dbError(IOException e) {
		Msg.showError(this, null, "IO ERROR", e.getMessage(), e);
	}

	@Override
	ArchivedGnuVtable buildVtable(db.Record record) {
		return new ArchivedGnuVtable(this, record);
	}

	@Override
	ArchivedGnuVtable buildVtable(Vtable vtable, db.Record record) {
		return new ArchivedGnuVtable(this, (GnuVtable) vtable, record);
	}

	public final long getTypeKey(String symbolName) {
		acquireLock();
		try {
			StringField field = new StringField(symbolName);
			long[] results = getTables().getTypeTable().findRecords(
				field, ArchivedClassTypeInfo.SchemaOrdinals.SYMBOL_NAME.ordinal());
			if (results.length == 1) {
				return results[0];
			}
		} catch (IOException e) {
			dbError(e);
		} finally {
			releaseLock();
		}
		return INVALID_KEY;
	}

	public final long getVtableKey(String symbolName) {
		acquireLock();
		try {
			StringField field = new StringField(symbolName);
			long[] results = getTables().getVtableTable().findRecords(
				field, ArchivedGnuVtable.SchemaOrdinals.SYMBOL_NAME.ordinal());
			if (results.length == 1) {
				return results[0];
			}
		} catch (IOException e) {
			dbError(e);
		} finally {
			releaseLock();
		}
		return INVALID_KEY;
	}

	ClassTypeInfoDB getType(GhidraClass gc) throws UnresolvedClassTypeInfoException {
		Program program = gc.getSymbol().getProgram();
		SymbolTable table = program.getSymbolTable();
		return table.getSymbols(TypeInfo.TYPENAME_SYMBOL_NAME, gc)
			.stream()
			.findFirst()
			.map(Symbol::getAddress)
			.map(a -> TypeInfoUtils.getTypeName(program, a))
			.map(this::getType)
			.orElseGet(() -> {
				return null;
			});
	}

	ClassTypeInfoDB getType(Function fun) throws UnresolvedClassTypeInfoException {
		Namespace ns = fun.getParentNamespace();
		if (ns instanceof GhidraClass) {
			return getType((GhidraClass) ns);
		}
		return null;
	}

	ClassTypeInfoDB getType(String name, Namespace namespace)
			throws UnresolvedClassTypeInfoException {
		Program program = namespace.getSymbol().getProgram();
		SymbolTable table = program.getSymbolTable();
		Symbol s = table.getClassSymbol(name, namespace);
		if (s != null && s.getClass() == GhidraClass.class) {
			return getType((GhidraClass) s.getObject());
		}
		return null;
	}

	ClassTypeInfoDB getType(String typeName) throws UnresolvedClassTypeInfoException {
		if (typeName.isBlank()) {
			return null;
		}
		if (typeName.startsWith(MANGLED_TYPEINFO_PREFIX)) {
			typeName = typeName.substring(MANGLED_TYPEINFO_PREFIX.length());
		}
		acquireLock();
		try {
			db.Field f = new StringField(typeName);
			long[] keys = getTables().getTypeTable().findRecords(
				f, ArchivedClassTypeInfo.SchemaOrdinals.TYPENAME.ordinal());
			if (keys.length == 1) {
				return getType(keys[0]);
			}
		} catch (IOException e) {
			dbError(e);
		} finally {
			releaseLock();
		}
		return null;
	}

}