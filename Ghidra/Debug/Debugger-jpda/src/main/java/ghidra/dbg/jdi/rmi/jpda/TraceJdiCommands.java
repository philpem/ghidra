/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.dbg.jdi.rmi.jpda;

import java.io.IOException;
import java.lang.ProcessHandle.Info;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import com.sun.jdi.*;
import com.sun.jdi.Method;
import com.sun.jdi.Value;
import com.sun.jdi.event.Event;
import com.sun.jdi.request.*;

import ghidra.app.plugin.core.debug.client.tracermi.*;
import ghidra.dbg.jdi.manager.impl.JdiManagerImpl;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.*;
import ghidra.program.model.lang.Language;
import ghidra.rmi.trace.TraceRmi.*;
import ghidra.util.Msg;

/*
 * Some notes:
 * 		ghidraTracePutX:  batch wrapper around putXxxx
 * 		putX:  generally creates the object and calls putXDetails
 * 		putXDetails:  assumes the object already exists
 * 		putXContainer:  creates one or more objects
 * 		xProxy:  container for exactly one X
 */

class State {

	RmiClient client;
	public RmiTrace trace;
	RmiTransaction tx;

	public RmiClient requireClient() {
		if (client == null) {
			throw new RuntimeException("Not connected");
		}
		return client;
	}

	public void requireNoClient() {
		if (client != null) {
			client = null;
			throw new RuntimeException("Already connected");
		}
	}

	public void resetClient() {
		client = null;
		resetTrace();
	}

	public RmiTrace requireTrace() {
		if (trace == null) {
			throw new RuntimeException("No trace started");
		}
		return trace;
	}

	public void requireNoTrace() {
		if (trace != null) {
			throw new RuntimeException("Trace already started");
		}
	}

	public void resetTrace() {
		trace = null;
		resetTx();
	}

	public RmiTransaction requireTx() {
		if (tx == null) {
			throw new RuntimeException("No transaction");
		}
		return tx;
	}

	public void requireNoTx() {
		if (tx != null) {
			throw new RuntimeException("Transaction already started");
		}
	}

	public void resetTx() {
		tx = null;
	}

}

public class TraceJdiCommands {

	private TraceJdiManager manager;
	private JdiManagerImpl jdi;

	public State state;
	private String[] regNames = { "PC", "return_address" };
	public long MAX_REFS = 100;

//	protected static final TargetStepKindSet SUPPORTED_KINDS = TargetStepKindSet.of( //
//		TargetStepKind.FINISH, //
//		TargetStepKind.LINE, //
//		TargetStepKind.OVER, //
//		TargetStepKind.OVER_LINE, //
//		TargetStepKind.RETURN, //
//		TargetStepKind.UNTIL, //
//		TargetStepKind.EXTENDED);

	public TraceJdiCommands(TraceJdiManager manager) {
		this.manager = manager;
		this.jdi = manager.getJdi();
		state = new State();
	}

	public void ghidraTraceConnect(String address) {
		state.requireNoClient();
		String[] addr = address.split(":");
		if (addr.length != 2) {
			throw new RuntimeException("Address must be in the form 'host:port'");
		}
		try {
			SocketChannel channel =
				SocketChannel.open(new InetSocketAddress(addr[0], Integer.parseInt(addr[1])));
			state.client = new RmiClient(channel, "jdi");
			state.client.setRegistry(manager.remoteMethodRegistry);
			state.client.negotiate("Connect");
			Msg.info(this, "Connected to " + state.client.getDescription() + " at " + address);
		}
		catch (NumberFormatException e) {
			throw new RuntimeException("Port must be numeric");
		}
		catch (IOException e) {
			throw new RuntimeException("Error connecting to " + address + ": " + e);
		}
	}

	public void ghidraTraceListen(String address) {
		// TODO: UNTESTED
		state.requireNoClient();
		String host = "0.0.0.0";
		int port = 0;
		if (address != null) {
			String[] parts = address.split(":");
			if (parts.length == 1) {
				port = Integer.parseInt(parts[0]);
			}
			else {
				host = parts[0];
				port = Integer.parseInt(parts[1]);
			}
		}
		try {
			InetSocketAddress socketAddress = new InetSocketAddress(host, port);
			ServerSocketChannel channel = ServerSocketChannel.open();
			channel.bind(socketAddress);
			Selector selector = Selector.open();
			while (true) {
				selector.select();
				Set<SelectionKey> selKeys = selector.selectedKeys();
				Iterator<SelectionKey> keyIterator = selKeys.iterator();

				while (keyIterator.hasNext()) {
					SelectionKey key = keyIterator.next();
					if (key.isAcceptable()) {
						SocketChannel client = channel.accept();
						state.client = new RmiClient(client, "jdi");
						state.client.setRegistry(manager.remoteMethodRegistry);
						client.configureBlocking(false);
						Msg.info(this, "Connected from " + state.client.getDescription() + " at " +
							client.getLocalAddress());
					}
				}
				keyIterator.remove();
			}
		}
		catch (NumberFormatException e) {
			throw new RuntimeException("Port must be numeric");
		}
		catch (IOException e) {
			throw new RuntimeException("Error connecting to " + address + ": " + e);
		}
	}

	public void ghidraTraceDisconnect() {
		state.requireClient().close();
		state.resetClient();
	}

	private String computeName() {
		VirtualMachine currentVM = manager.getJdi().getCurrentVM();
		if (currentVM != null) {
			Optional<String> command = currentVM.process().info().command();
			if (command.isPresent()) {
				return "jdi/" + command;
			}
		}
		return "jdi/noname";
	}

	public void startTrace(String name) {
		TraceJdiArch arch = manager.getArch();
		LanguageID language = arch.computeGhidraLanguage();
		CompilerSpecID compiler = arch.computeGhidraCompiler(language);
		state.trace = state.client.createTrace(name, language, compiler);

		state.trace.memoryMapper = arch.computeMemoryMapper();
		state.trace.registerMapper = arch.computeRegisterMapper();

		try (RmiTransaction tx = state.trace.startTx("Create snapshots", false)) {
			state.trace.createRootObject(manager.rootSchema.getContext(),
				manager.rootSchema.getName().toString());
			//activate(null);

			// add the DEFAULT_SECTION
			AddressRange range = manager.defaultRange;
			byte[] bytes = new byte[(int) range.getLength()];
			Arrays.fill(bytes, (byte) 0xFF);
			state.trace.putBytes(range.getMinAddress(), bytes, state.trace.getSnap());
		}
	}

	public void ghidraTraceStart(String name) {
		state.requireClient();
		if (name == null) {
			name = computeName();
		}
		state.requireNoTrace();
		startTrace(name);
	}

	public void ghidraTraceStop() {
		state.requireTrace().close();
		state.resetTrace();
	}

	public void ghidraTraceRestart(String name) {
		state.requireClient();
		if (state.trace != null) {
			state.trace.close();
			state.resetTrace();
		}
		if (name == null) {
			name = computeName();
		}
		startTrace(name);
	}

	public void ghidraTraceInfo() {
		if (state.client == null) {
			Msg.error(this, "Not connected to Ghidra");
		}
		Msg.info(this, "Connected to" + state.client.getDescription());
		if (state.trace == null) {
			Msg.error(this, "No trace");
		}
		Msg.info(this, "Trace active");
	}

	public void ghidraTraceInfoLcsp() {
		TraceJdiArch arch = manager.getArch();
		LanguageID language = arch.computeGhidraLanguage();
		CompilerSpecID compiler = arch.computeGhidraCompiler(language);
		Msg.info(this, "Selected Ghidra language: " + language);
		Msg.info(this, "Selected Ghidra compiler: " + compiler);
	}

	public void ghidraTraceTxStart(String description) {
		state.requireTx();
		state.tx = state.requireTrace().startTx(description, false);
	}

	public void ghidraTraceTxCommit() {
		state.requireTx().commit();
		state.resetTx();
	}

	public void ghidraTraceTxAbort() {
		RmiTransaction tx = state.requireTx();
		Msg.info(this, "Aborting trace transaction!");
		tx.abort();
		state.resetTx();
	}

	public void ghidraTraceSave() {
		state.requireTrace().save();
	}

	public long ghidraTraceNewSnap(String description) {
		state.requireTx();
		return state.requireTrace().snapshot(description, null, null);
	}

	public void ghidraTraceSetSnap(long snap) {
		state.requireTrace().setSnap(snap);
	}

	public void ghidraTracePutMem(Address address, long length) {
		state.requireTx();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutMem", false)) {
			putMem(address, length, true);
		}
	}

	public void ghidraTracePutMemState(Address address, long length, MemoryState memState) {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutMemState", false)) {
			putMemState(address, length, memState, true);
		}
	}

	public void ghidraTraceDelMem(Address address, long length) {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceDelMem", false)) {
			VirtualMachine currentVM = manager.getJdi().getCurrentVM();
			Address mapped = state.trace.memoryMapper.map(address);
			AddressRangeImpl range = new AddressRangeImpl(mapped, mapped.add(length - 1));
			state.trace.deleteBytes(range, state.trace.getSnap());
		}
	}

	public void ghidraTracePutReg(StackFrame frame) {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutReg", false)) {
			putReg(frame);
		}
	}

	public void ghidraTraceDelReg(StackFrame frame) {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceDelReg", false)) {
			String ppath = getPath(frame);
			if (ppath == null) {
				Msg.error(this, "Null path for " + frame);
				return;
			}
			String path = ppath + ".Registers";
			state.trace.deleteRegisters(path, regNames, state.trace.getSnap());
		}
	}

	public void ghidraTraceCreateObj(String path) {
		state.requireTx();
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceCreateObj", false)) {
			createObject(path);
		}
	}

	public void ghidraTraceInsertObj(String path) {
		state.requireTx();
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceInsertObj", false)) {
			state.trace.proxyObjectPath(path).insert(state.trace.getSnap(), Resolution.CR_ADJUST);
		}
	}

	public void ghidraTraceRemoveObj(String path) {
		state.requireTx();
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceRemoveObj", false)) {
			state.trace.proxyObjectPath(path).remove(state.trace.getSnap(), false);
		}
	}

//	public void ghidraTraceSetValue(String path, String key, Object value, TargetObjectSchema schema) {
//	}

	public void ghidraTraceRetainValues(String kind, String path, Set<String> keys) {
		state.requireTx();
		ValueKinds kinds = ValueKinds.VK_ELEMENTS;
		if (kind != null && kind.startsWith("--")) {
			if (kind.equals("--elements")) {
				kinds = ValueKinds.VK_ELEMENTS;
			}
			if (kind.equals("--attributes")) {
				kinds = ValueKinds.VK_ATTRIBUTES;
			}
			if (kind.equals("--both")) {
				kinds = ValueKinds.VK_BOTH;
			}
		}
		state.trace.proxyObjectPath(path).retainValues(keys, state.trace.getSnap(), kinds);
	}

	public RmiTraceObject ghidraTraceGetObj(String path) {
		state.requireTrace();
		return state.trace.proxyObjectPath(path);
	}

//	public void ghidraTraceGetValues(String pattern) {
//	}
//
//	public void ghidraTraceGetValuesRng() {
//	}

	public void ghidraTracePutVMs() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutVMs", false)) {
			putVMs();
		}
	}

	public void ghidraTracePutProcesses() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutVMs", false)) {
			putProcesses();
		}
	}

	public void ghidraTracePutBreakpoints() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutBreakpoints", false)) {
			putBreakpoints();
		}
	}

	public void ghidraTracePutEvents() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutEvents", false)) {
			putEvents();
		}
	}

	public void activate(String path) {
		state.requireTrace();
		if (path == null) {
			VirtualMachine currentVM = manager.getJdi().getCurrentVM();
			path = getPath(currentVM);
			try {
				ThreadReference currentThread = manager.getJdi().getCurrentThread();
				if (currentThread != null) {
					path = getPath(currentThread);
				}
				StackFrame currentFrame = manager.getJdi().getCurrentFrame();
				if (currentFrame != null) {
					path = getPath(currentFrame);
				}
			}
			catch (VMDisconnectedException discExc) {
				Msg.info(this, "Activate failed - VM disconnected");
			}
		}
		state.trace.activate(path);
	}

	public void ghidraTraceActivate(String path) {
		activate(path);
	}

	public void ghidraTraceDisassemble(Address address) {
		state.requireTrace();
		VirtualMachine currentVM = manager.getJdi().getCurrentVM();
		MemoryMapper mapper = state.trace.memoryMapper;
		Address mappedAddress = mapper.map(address);
		AddressSpace addressSpace = mappedAddress.getAddressSpace();
		if (!addressSpace.equals(address.getAddressSpace())) {
			state.trace.createOverlaySpace(mappedAddress, address);
		}
		state.trace.disassemble(mappedAddress, state.trace.getSnap());
	}

	// STATE //

	public void putMemState(Address start, long length, MemoryState memState, boolean usePages) {
		VirtualMachine currentVM = manager.getJdi().getCurrentVM();
		Address mapped = state.trace.memoryMapper.map(start);
		if (mapped.getAddressSpace() != start.getAddressSpace() &&
			!memState.equals(MemoryState.MS_UNKNOWN)) {
			state.trace.createOverlaySpace(mapped, start);
		}
		AddressRangeImpl range = new AddressRangeImpl(mapped, mapped.add(length - 1));
		state.trace.setMemoryState(range, memState, state.trace.getSnap());
	}

	public void putReg(StackFrame frame) {
		String ppath = getPath(frame);
		if (ppath == null) {
			Msg.error(this, "Null path for " + frame);
			return;
		}
		String path = ppath + ".Registers";
		state.trace.createOverlaySpace("register", path);
		RegisterValue[] rvs = putRegisters(frame, path);
		state.trace.putRegisters(path, rvs, state.trace.getSnap());
	}

	public RegisterValue[] putRegisters(StackFrame frame, String ppath) {
		TraceJdiArch arch = manager.getArch();
		Language lang = arch.getLanguage();
		Set<String> keys = new HashSet<>();
		RegisterValue[] rvs = new RegisterValue[regNames.length];

		int ireg = 0;
		String r = regNames[0];
		Register register = lang.getRegister(r);
		keys.add(manager.key(r));
		Location loc = frame.location();
		Address addr = putRegister(ppath, r, loc);
		RegisterValue rv = new RegisterValue(register, BigInteger.valueOf(addr.getOffset()));
		rvs[ireg++] = rv;

		r = regNames[1];
		register = lang.getRegister(r);
		keys.add(manager.key(r));
		ThreadReference thread = frame.thread();
		Location ploc = null;
		int frameCount;
		try {
			frameCount = thread.frameCount();
			for (int i = 0; i < frameCount; i++) {
				StackFrame f = thread.frame(i);
				if (f.equals(frame) && i < frameCount - 1) {
					ploc = thread.frame(i + 1).location();
				}
			}
		}
		catch (IncompatibleThreadStateException e) {
			// IGNORE
		}
		if (ploc != null) {
			addr = putRegister(ppath, r, ploc);
			rv = new RegisterValue(register, BigInteger.valueOf(addr.getOffset()));
			rvs[ireg++] = rv;
		}
		else {
			rv = new RegisterValue(register, BigInteger.valueOf(0L));
			rvs[ireg++] = rv;
		}

		retainKeys(ppath, keys);
		return rvs;
	}

	public void putCurrentLocation() {
		Location loc = manager.getJdi().getCurrentLocation();
		if (loc == null) {
			return;
		}
		Method m = loc.method();
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		if (manager.getAddressRange(m.declaringType()) == null) {
			putReferenceType(getPath(vm) + ".Classes", m.declaringType(), true);
		}
		else {
			updateMemoryForMethod(m);
		}
	}

	public Address putRegister(String ppath, String name, Location loc) {
		Address addr = manager.getAddressFromLocation(loc);
		RegisterMapper mapper = state.trace.registerMapper;
		String regName = mapper.mapName(name);
		TraceJdiArch arch = manager.getArch();
		Language lang = arch.getLanguage();
		Register register = lang.getRegister(name);
		RegisterValue rv = new RegisterValue(register, addr.getOffsetAsBigInteger());
		RegisterValue mapped = mapper.mapValue(name, rv);
		Address regAddr = addr.getNewAddress(mapped.getUnsignedValue().longValue());
		setValue(ppath, manager.key(regName), Long.toHexString(regAddr.getOffset()));

		int codeIndex = (int) loc.codeIndex();
		regAddr = regAddr.subtract(codeIndex);
		putMem(regAddr, codeIndex + 1, false);

		return addr;
	}

	public void putMem(Address address, long length, boolean create) {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		MemoryMapper mapper = state.trace.memoryMapper;
		Address mappedAddress = mapper.map(address);
		AddressSpace addressSpace = mappedAddress.getAddressSpace();
		if (!addressSpace.equals(address.getAddressSpace())) {
			state.trace.createOverlaySpace(mappedAddress, address);
		}
		int ilen = (int) length;
		// NB: Right now, we return a full page even if the method/reftype
		//   is missing.  Probably should do something saner, e.g. mark it as an error,
		//   but gets tricky given all the possible callers.
		byte[] bytes = new byte[ilen];
		Arrays.fill(bytes, (byte) 0xFF);
		if (addressSpace.getName().equals("ram")) {
			Method method = manager.getMethodForAddress(address);
			if (method != null) {
				byte[] bytecodes = method.bytecodes();
				if (bytecodes != null) {
					bytes = Arrays.copyOf(bytecodes, ilen);
				}
				state.trace.putBytes(mappedAddress, bytes, state.trace.getSnap());
			}
			else {
				if (create) {
					throw new RuntimeException("Attempt to create existing memory");
				}
			}
			return;
		}
		if (addressSpace.getName().equals("constantPool")) {
			ReferenceType reftype = manager.getReferenceTypeForPoolAddress(address);
			if (reftype != null) {
				byte[] bytecodes = reftype.constantPool();
				if (bytecodes != null) {
					bytes = Arrays.copyOf(bytecodes, ilen);
				}
				state.trace.putBytes(mappedAddress, bytes, state.trace.getSnap());
			}
			return;
		}
		throw new RuntimeException();
	}

	// TYPES //

	public void putType(String ppath, String key, Type type) {
		String path = createObject(type, key, ppath);
		putTypeDetails(path, type);
		insertObject(path);
	}

	public void putTypeDetails(String path, Type type) {
		setValue(path, "_display", "Type: " + type.name());
		setValue(path, "Signature", type.signature());
	}

	public void putReferenceTypeContainer(String ppath, List<ReferenceType> reftypes) {
		Set<String> keys = new HashSet<>();
		for (ReferenceType ref : reftypes) {
			keys.add(manager.key(ref.name()));
			putReferenceType(ppath, ref, false);
		}
		retainKeys(ppath, keys);
	}

	public void putReferenceType(String ppath, ReferenceType reftype, boolean load) {
		String path = createObject(reftype, reftype.name(), ppath);
		if (manager.getAddressRange(reftype) == null) {
			manager.bumpRamIndex();
		}
		if (load) {
			registerMemory(path, reftype);
		}
		putReferenceTypeDetails(path, reftype);
		insertObject(path);
	}

	public void putReferenceTypeDetails(String path, ReferenceType reftype) {
		String name = reftype.name();
		if (name.indexOf(".") > 0) {
			name = name.substring(name.lastIndexOf(".") + 1);
		}
		setValue(path, TraceJdiManager.MODULE_NAME_ATTRIBUTE_NAME, name + ".class");
		putRefTypeAttributes(path, reftype);
		createObject(path + ".Fields");
		createObject(path + ".Instances");
		createObject(path + ".Locations");
		//createObject(path + ".Methods");
		putMethodContainer(path + ".Methods", reftype);

		String rpath = createObject(path + ".Relations");
		ModuleReference module = reftype.module();
		createObject(module, module.name(), rpath + ".ModuleRef");
		if (reftype instanceof ArrayType at) {
			putArrayTypeDetails(rpath, at);
		}
		if (reftype instanceof ClassType ct) {
			putClassTypeDetails(rpath, ct);
		}
		if (reftype instanceof InterfaceType it) {
			putInterfaceTypeDetails(rpath, it);
		}
	}

	private void putRefTypeAttributes(String ppath, ReferenceType reftype) {
		String path = createObject(ppath + ".Attributes");
		if (reftype instanceof ArrayType) {
			return;
		}
		try {
			setValue(path, "isAbstract", reftype.isAbstract());
			setValue(path, "isFinal", reftype.isFinal());
			setValue(path, "isInitialized", reftype.isInitialized());
			setValue(path, "isPackagePrivate", reftype.isPackagePrivate());
			setValue(path, "isPrepared", reftype.isPrepared());
			setValue(path, "isPrivate", reftype.isPrivate());
			setValue(path, "isProtected", reftype.isProtected());
			setValue(path, "isPublic", reftype.isPublic());
			setValue(path, "isStatic", reftype.isStatic());
			setValue(path, "isVerified", reftype.isVerified());
		}
		catch (Exception e) {
			if (e instanceof ClassNotLoadedException) {
				setValue(path, "status", "Class not loaded");
			}
		}
		setValue(path, "defaultStratum", reftype.defaultStratum());
		setValue(path, "availableStata", reftype.availableStrata());
		setValue(path, "failedToInitialize", reftype.failedToInitialize());
	}

	private void registerMemory(String path, ReferenceType reftype) {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		String mempath = getPath(vm) + ".Memory";
		AddressSet bounds = new AddressSet();
		for (Method m : reftype.methods()) {
			if (m.location() != null) {
				AddressRange range = manager.registerAddressesForMethod(m);
				if (range != null && range.getMinAddress().getOffset() != 0) {
					putMem(range.getMinAddress(), range.getLength(), true);
					bounds.add(range);

					String mpath = createObject(mempath + manager.key(m.toString()));
					setValue(mpath, "Range", range);
					insertObject(path);
				}
			}
		}
		AddressRange range = manager.putAddressRange(reftype, bounds);
		setValue(path, "Range", range);

		setValue(path, "Count", reftype.constantPoolCount());
		range = manager.getPoolAddressRange(reftype, getSize(reftype) - 1);
		setValue(path, "RangeCP", range);
		try {
			putMem(range.getMinAddress(), range.getLength(), true);
		}
		catch (RuntimeException e) {
			// Ignore
		}
	}

	private void updateMemoryForMethod(Method m) {
		if (m.location() != null) {
			AddressRange range = manager.registerAddressesForMethod(m);
			if (range != null && range.getMinAddress().getOffset() != 0) {
				putMem(range.getMinAddress(), range.getLength(), true);
			}
		}
	}

	public void loadReferenceType(String ppath, List<ReferenceType> reftypes, String targetClass) {
		List<ReferenceType> classes = reftypes;
		for (ReferenceType ref : classes) {
			if (ref.name().contains(targetClass)) {
				putReferenceType(ppath, ref, true);
			}
		}
	}

	public void putArrayTypeDetails(String path, ArrayType type) {
		setValue(path, "ComponentSignature", type.componentSignature());
		setValue(path, "ComponentTypeName", type.componentTypeName());
		createObject(path + ".ComponentType");
	}

	public void putClassTypes(String ppath, List<ClassType> reftypes) {
		Set<String> keys = new HashSet<>();
		for (ClassType ref : reftypes) {
			keys.add(manager.key(ref.name()));
			putReferenceType(ppath, ref, true);
		}
		retainKeys(ppath, keys);
	}

	public void putClassTypeDetails(String path, ClassType type) {
		setValue(path, "IsEnum", type.isEnum());
		createObject(path + ".AllInterfaces");
		createObject(path + ".Interfaces");
		createObject(path + ".SubClasses");
		createObject(path + ".ClassType");
	}

	public void putInterfaceTypes(String ppath, List<InterfaceType> reftypes) {
		Set<String> keys = new HashSet<>();
		for (ReferenceType ref : reftypes) {
			keys.add(manager.key(ref.name()));
			putReferenceType(ppath, ref, true);
		}
		retainKeys(ppath, keys);
	}

	public void putInterfaceTypeDetails(String path, InterfaceType type) {
		createObject(path + ".Implementors");
		createObject(path + ".SubInterfaces");
		createObject(path + ".SuperInterfaces");
	}

	// VALUES //

	public void putValueContainer(String path, List<Value> values) {
		for (Value v : values) {
			putValue(path, v.toString(), v);
		}
	}

	public void putValue(String ppath, String key, Value value) {
		String path = createObject(value, key, ppath);
		setValue(path, "_display", "Value: " + value.toString());
		//putValueDetailsByType(path, value);
		insertObject(path);
	}

	public void putValueDetailsByType(String path, Value value) {
		if (value instanceof PrimitiveValue pval) {
			putPrimitiveValue(path, pval);
		}
		else if (value instanceof ArrayReference aref) {
			putArrayReferenceDetails(path, aref);
		}
		else if (value instanceof ClassLoaderReference aref) {
			putClassLoaderReferenceDetails(path, aref);
		}
		else if (value instanceof ClassObjectReference aref) {
			putClassObjectReferenceDetails(path, aref);
		}
		else if (value instanceof ModuleReference aref) {
			putModuleReferenceDetails(path, aref);
		}
		else if (value instanceof StringReference aref) {
			putStringReferenceDetails(path, aref);
		}
		else if (value instanceof ThreadGroupReference aref) {
			putThreadGroupReferenceDetails(path, aref);
		}
		else if (value instanceof ThreadReference aref) {
			putThreadReferenceDetails(path, aref);
		}
		else if (value instanceof ObjectReference oref) {
			putObjectReferenceDetails(path, oref);
		}
	}

	public void putValueDetails(String path, Value value) {
		putType(path, "Type", value.type());
	}

	public void putPrimitiveValue(String ppath, PrimitiveValue value) {
		String path = createObject(value, value.toString(), ppath);
		putValueDetails(path, value);
		if (value instanceof BooleanValue v) {
			setValue(path, "Value", v.booleanValue());
		}
		if (value instanceof ByteValue b) {
			setValue(path, "Value", b.byteValue());
		}
		if (value instanceof CharValue v) {
			setValue(path, "Value", v.charValue());
		}
		if (value instanceof ShortValue v) {
			setValue(path, "Value", v.shortValue());
		}
		if (value instanceof IntegerValue v) {
			setValue(path, "Value", v.intValue());
		}
		if (value instanceof LongValue v) {
			setValue(path, "Value", v.longValue());
		}
		if (value instanceof FloatValue v) {
			setValue(path, "Value", v.floatValue());
		}
		if (value instanceof DoubleValue v) {
			setValue(path, "Value", v.doubleValue());
		}
		insertObject(path);
	}

	private Value getPrimitiveValue(Value value, String newVal) {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		if (value instanceof BooleanValue) {
			return vm.mirrorOf(Boolean.valueOf(newVal));
		}
		if (value instanceof ByteValue) {
			return vm.mirrorOf(Byte.valueOf(newVal));
		}
		if (value instanceof CharValue) {
			return vm.mirrorOf(newVal.charAt(0));
		}
		if (value instanceof ShortValue) {
			return vm.mirrorOf(Short.valueOf(newVal));
		}
		if (value instanceof IntegerValue) {
			return vm.mirrorOf(Integer.valueOf(newVal));
		}
		if (value instanceof LongValue) {
			return vm.mirrorOf(Long.valueOf(newVal));
		}
		if (value instanceof FloatValue) {
			return vm.mirrorOf(Float.valueOf(newVal));
		}
		if (value instanceof DoubleValue) {
			return vm.mirrorOf(Double.valueOf(newVal));
		}
		if (value instanceof StringReference) {
			return vm.mirrorOf(newVal);
		}
		return null;
	}

	public void modifyValue(LocalVariable lvar, String valstr) {
		String path = getPath(lvar);
		String ppath = getParentPath(path);
		Object parent = manager.objForPath(ppath);
		if (parent instanceof StackFrame frame) {
			Value orig = frame.getValue(lvar);
			Value repl = getPrimitiveValue(orig, valstr);
			if (repl != null) {
				try {
					frame.setValue(lvar, repl);
				}
				catch (InvalidTypeException e) {
					Msg.error(this, "Invalid type for " + lvar);
				}
				catch (ClassNotLoadedException e) {
					Msg.error(this, "Class not loaded for " + lvar);
				}

				putLocalVariable(ppath + ".Variables", lvar, repl);
			}
		}
		Msg.error(this, "Cannot set value for " + lvar);
	}

	public void modifyValue(Field field, String valstr) {
		String path = getPath(field);
		String ppath = getParentPath(path);
		Object parent = manager.objForPath(ppath);
		if (parent instanceof ObjectReference ref) {
			Value orig = ref.getValue(field);
			Value repl = getPrimitiveValue(orig, valstr);
			if (repl != null) {
				try {
					ref.setValue(field, repl);
				}
				catch (InvalidTypeException e) {
					Msg.error(this, "Invalid type for " + field);
				}
				catch (ClassNotLoadedException e) {
					Msg.error(this, "Class not loaded for " + field);
				}
				putField(ppath + ".Variables", field, repl);
			}
		}
		Msg.error(this, "Cannot set value for " + field);
	}

	public void putObjectContainer(String path, List<ObjectReference> objects) {
		for (ObjectReference obj : objects) {
			createObject(obj, obj.toString(), path);
		}
	}

	public void putObjectReference(String ppath, ObjectReference ref) {
		String path = createObject(ref, ref.toString(), ppath);
		putObjectReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putObjectReferenceDetails(String path, ObjectReference ref) {
		putValueDetails(path, ref);
		setValue(path, "UniqueId", ref.uniqueID());
		String apath = createObject(path + ".Attributes");
		try {
			setValue(apath, "entryCount", ref.entryCount());
		}
		catch (IncompatibleThreadStateException e) {
			// IGNORE
		}
		setValue(apath, "isCollected", ref.isCollected());
		String rpath = createObject(path + ".Relations");
		try {
			if (ref.owningThread() != null) {
				createObject(rpath + ".OwningThread");
			}
			if (ref.waitingThreads() != null) {
				createObject(rpath + ".WaitingThreads");
			}
		}
		catch (IncompatibleThreadStateException e) {
			// IGNORE
		}
		if (ref.referenceType() != null) {
			createObject(rpath + ".ReferenceType");
		}
		if (ref.referringObjects(MAX_REFS) != null) {
			createObject(rpath + ".ReferringObjects");
		}
		if (!(ref instanceof ArrayReference)) {
			createObject(path + ".Variables");
		}
	}

	public void putArrayReference(String ppath, ArrayReference ref) {
		String path = createObject(ref, ref.toString(), ppath);
		putArrayReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putArrayReferenceDetails(String path, ArrayReference ref) {
		putObjectReferenceDetails(path, ref);
		setValue(path, "Length", ref.length());
		createObject(path + ".Values");
	}

	public void putClassLoaderReference(String ppath, ClassLoaderReference ref) {
		String path = createObject(ref, ref.toString(), ppath);
		putClassLoaderReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putClassLoaderReferenceDetails(String path, ClassLoaderReference ref) {
		putObjectReferenceDetails(path, ref);
		createObject(path + ".DefinedClasses");
		createObject(path + ".VisibleClasses");
	}

	public void putClassObjectReference(String ppath, ClassObjectReference ref) {
		String path = createObject(ref, ref.toString(), ppath);
		putClassObjectReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putClassObjectReferenceDetails(String path, ClassObjectReference ref) {
		putObjectReferenceDetails(path, ref);
		createObject(path + ".ReflectedType");
	}

	public void putModuleReferenceContainer() {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		String ppath = getPath(vm) + ".ModuleRefs";
		Set<String> keys = new HashSet<>();
		List<ModuleReference> modules = vm.allModules();
		for (ModuleReference ref : modules) {
			keys.add(manager.key(ref.name()));
			createObject(ref, ref.name(), ppath);
		}
		retainKeys(ppath, keys);
	}

	public void putModuleReference(String ppath, ModuleReference ref) {
		String path = createObject(ref, ref.name(), ppath);
		putModuleReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putModuleReferenceDetails(String path, ModuleReference ref) {
		putObjectReferenceDetails(path, ref);
		createObject(path + ".ClassLoader");
	}

	public void putStringReference(String ppath, StringReference ref) {
		String path = createObject(ref, ref.toString(), ppath);
		putStringReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putStringReferenceDetails(String path, StringReference ref) {
		putObjectReferenceDetails(path, ref);
		setValue(path, "Value", ref.value());
	}

	public void putThreadGroupContainer(String refpath, List<ThreadGroupReference> refs) {
		String ppath = refpath + ".ThreadGroups";
		Set<String> keys = new HashSet<>();
		for (ThreadGroupReference subref : refs) {
			keys.add(manager.key(subref.name()));
			putThreadGroupReference(ppath, subref);
		}
		retainKeys(ppath, keys);
	}

	public void putThreadGroupReference(String ppath, ThreadGroupReference ref) {
		String path = createObject(ref, ref.name(), ppath);
		putThreadGroupReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putThreadGroupReferenceDetails(String path, ThreadGroupReference ref) {
		putObjectReferenceDetails(path, ref);
		if (ref.parent() != null) {
			createObject(path + ".Parent");
		}
		createObject(path + ".ThreadGroups");
		createObject(path + ".Threads");
	}

	public void putThreadContainer(String refpath, List<ThreadReference> refs, boolean asLink) {
		String ppath = refpath + ".Threads";
		Set<String> keys = new HashSet<>();
		for (ThreadReference subref : refs) {
			keys.add(manager.key(subref.name()));
			if (asLink) {
				createLink(ppath, subref.name(), subref);
			}
			else {
				putThreadReference(ppath, subref);
			}
		}
		retainKeys(ppath, keys);
	}

	public void putThreadReference(String ppath, ThreadReference ref) {
		String path = createObject(ref, ref.name(), ppath);
		putThreadReferenceDetails(path, ref);
		insertObject(path);
	}

	public void putThreadReferenceDetails(String path, ThreadReference ref) {
		putObjectReferenceDetails(path, ref);
		createObject(path + ".Stack");
		String rpath = createObject(path + ".Relations");
		createObject(rpath + ".CurrentContendedMonitor");
		createObject(rpath + ".OwnedMonitors");
		createObject(rpath + ".OwnedMonitorsAndFrames");
		createObject(rpath + ".ThreadGroup");
		putThreadAttributes(ref, path);
	}

	void putThreadAttributes(ThreadReference thread, String ppath) {
		String path = createObject(ppath + ".Attributes");
		setValue(path, "Status", thread.status());
		setValue(path, "isAtBreakpoint", thread.isAtBreakpoint());
		setValue(path, "isCollected", thread.isCollected());
		setValue(path, "isSuspended", thread.isSuspended());
		setValue(path, "isVirtual", thread.isVirtual());
		try {
			setValue(path, "entryCount", thread.entryCount());
		}
		catch (IncompatibleThreadStateException e) {
			// Ignore
		}
		try {
			setValue(path, "frameCount", thread.frameCount());
		}
		catch (IncompatibleThreadStateException e) {
			// Ignore
		}
		setValue(path, "suspendCount", thread.suspendCount());
	}

	public void putMonitorInfoContainer(String path, List<MonitorInfo> info) {
		for (MonitorInfo f : info) {
			createObject(f, f.toString(), path);
		}
	}

	public void putMonitorInfoDetails(String path, MonitorInfo info) {
		setValue(path, "StackDepth", info.stackDepth());
		createObject(path + ".Monitor");
		createObject(path + ".Thread");
	}

	// TYPE COMPONENTS

	public void putFieldContainer(String path, ReferenceType reftype) {
		boolean scope = manager.getScope(reftype);
		List<Field> fields = scope ? reftype.allFields() : reftype.fields();
		Set<String> keys = new HashSet<>();
		for (Field f : fields) {
			Value value = null;
			try {
				value = reftype.getValue(f);
				if (value != null) {
					keys.add(manager.key(value.toString()));
				}
			}
			catch (IllegalArgumentException iae) {
				// IGNORE
			}
			putField(path, f, value);
		}
		retainKeys(path, keys);
	}

	public void putVariableContainer(String path, ObjectReference ref) {
		boolean scope = manager.getScope(ref);
		List<Field> fields = scope ? ref.referenceType().allFields() : ref.referenceType().fields();
		Set<String> keys = new HashSet<>();
		for (Field f : fields) {
			Value value = null;
			try {
				value = ref.getValue(f);
				keys.add(manager.key(value.toString()));
			}
			catch (IllegalArgumentException iae) {
				// IGNORE
			}
			putField(path, f, value);
		}
		retainKeys(path, keys);
	}

	public void putField(String ppath, Field f, Value value) {
		String path = createObject(f, f.name(), ppath);
		putFieldDetails(path, f);
		if (value != null) {
			putValue(path, "Value", value);
			setValue(path, "_display", f.name() + " (" + f.typeName() + ") : " + value);
		}
		else {
			setValue(path, "_display", f.name() + " (" + f.typeName() + ")");
		}
		insertObject(path);
	}

	public void putFieldDetails(String path, Field f) {
		setValue(path, TraceJdiManager.MODULE_NAME_ATTRIBUTE_NAME, f.declaringType().name());
		if (f.genericSignature() != null) {
			setValue(path, "GenericSignature", f.genericSignature());
		}
		putFieldAttributes(path, f);
		try {
			putType(path, "Type", f.type());
		}
		catch (ClassNotLoadedException e) {
			// IGNORE
		}
	}

	private void putFieldAttributes(String ppath, Field f) {
		String path = createObject(ppath + ".Attributes");
		setValue(path, "Modifiers", Integer.toHexString(f.modifiers()));
		setValue(path, "Signature", f.signature());
		setValue(path, "isEnumConstant", f.isEnumConstant());
		setValue(path, "isFinal", f.isFinal());
		setValue(path, "isPackagePrivate", f.isPackagePrivate());
		setValue(path, "isPrivate", f.isPrivate());
		setValue(path, "isProtected", f.isProtected());
		setValue(path, "isPublic", f.isPublic());
		setValue(path, "isStatic", f.isStatic());
		setValue(path, "isSynthetic", f.isSynthetic());
		setValue(path, "isTransient", f.isTransient());
		setValue(path, "isVolatile", f.isVolatile());
	}

	public void putMethodContainer(String path, ReferenceType reftype) {
		boolean scope = manager.getScope(reftype);
		List<Method> methods = scope ? reftype.allMethods() : reftype.methods();
		Set<String> keys = new HashSet<>();
		for (Method m : methods) {
			keys.add(manager.key(m.name()));
			putMethod(path, m);
		}
		retainKeys(path, keys);
	}

	public void putMethod(String ppath, Method m) {
		String path = createObject(m, m.name(), ppath);
		putMethodDetails(path, m, true);
		insertObject(path);
	}

	public void putMethodDetails(String path, Method m, boolean partial) {
		ReferenceType declaringType = m.declaringType();
		setValue(path, TraceJdiManager.MODULE_NAME_ATTRIBUTE_NAME, declaringType.name());
		createLink(m, "DeclaringType", declaringType);
		if (!partial) {
			createObject(path + ".Arguments");
			if (m.genericSignature() != null) {
				setValue(path, "GenericSignature", m.genericSignature());
			}
			createObject(path + ".Locations");
			setValue(path, "Modifiers", m.modifiers());
			setValue(path, "ReturnType", m.returnTypeName());
			setValue(path, "Signature", m.signature());
			createObject(path + ".Variables");
			putMethodAttributes(path, m);
		}
		if (m.location() != null) {
			AddressRange range = manager.getAddressRange(m);
			if (!range.equals(manager.defaultRange)) {
				setValue(path, "Range", range);
			}
		}
		String bytes = "";
		for (byte b : m.bytecodes()) {
			bytes += Integer.toHexString(b & 0xff);
		}
		setValue(path, "ByteCodes", bytes);
	}

	private void putMethodAttributes(String ppath, Method m) {
		String path = createObject(ppath + ".Attributes");
		setValue(path, "isAbstract", m.isAbstract());
		setValue(path, "isBridge", m.isBridge());
		setValue(path, "isConstructor", m.isConstructor());
		setValue(path, "isDefault", m.isDefault());
		setValue(path, "isFinal", m.isFinal());
		setValue(path, "isNative", m.isNative());
		setValue(path, "isObsolete", m.isObsolete());
		setValue(path, "isPackagePrivate", m.isPackagePrivate());
		setValue(path, "isPrivate", m.isPrivate());
		setValue(path, "isProtected", m.isProtected());
		setValue(path, "isPublic", m.isPublic());
	}

	// OTHER OBJECTS //

	public void putVMs() {
		try {
			Set<String> keys = new HashSet<>();
			for (Entry<String, VirtualMachine> entry : jdi.listVMs().get().entrySet()) {
				VirtualMachine vm = entry.getValue();
				keys.add(manager.key(vm.name()));
				putVM("VMs", vm);
			}
			retainKeys("VMs", keys);
		}
		catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
	}

	public void putVM(String ppath, VirtualMachine vm) {
		String path = createObject(vm, vm.name(), ppath);
		putVMDetails(path, vm);
		insertObject(path);
	}

	public void putVMDetails(String path, VirtualMachine vm) {
		createObject(path + ".Classes");
		createObject(path + ".Memory");
		createObject(path + ".ThreadGroups");
		createObject(path + ".Threads");
		Event currentEvent = jdi.getCurrentEvent();
		String shortName = vm.name().substring(0, vm.name().indexOf(" "));
		String display = currentEvent == null ? shortName : shortName + " [" + currentEvent + "]";
		setValue(path, TraceJdiManager.DISPLAY_ATTRIBUTE_NAME, display);
		setValue(path, TraceJdiManager.ARCH_ATTRIBUTE_NAME, vm.name());
		setValue(path, TraceJdiManager.DEBUGGER_ATTRIBUTE_NAME, vm.description());
		setValue(path, TraceJdiManager.OS_ATTRIBUTE_NAME, vm.version());
	}

	public void putProcesses() {
		Map<String, VirtualMachine> vms;
		try {
			vms = jdi.listVMs().get();
		}
		catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			return;
		}
		for (Entry<String, VirtualMachine> entry : vms.entrySet()) {
			Set<String> keys = new HashSet<>();
			VirtualMachine vm = entry.getValue();
			String path = getPath(vm);
			if (path != null) {
				String ppath = path + ".Processes";
				Process proc = vm.process();
				if (proc != null) {
					String key = Long.toString(proc.pid());
					keys.add(manager.key(key));
					putProcess(ppath, proc);
				}
				retainKeys(ppath, keys);
			}
		}
	}

	public void putProcess(String ppath, Process proc) {
		String path = createObject(proc, Long.toString(proc.pid()), ppath);
		putProcessDetails(path, proc);
		insertObject(path);
	}

	public void putProcessDetails(String path, Process proc) {
		Info info = proc.info();
		Optional<String> optional = info.command();
		if (optional.isPresent()) {
			setValue(path, "Executable", optional.get());
		}
		optional = info.commandLine();
		if (optional.isPresent()) {
			setValue(path, "CommandLine", optional.get());
		}
		setValue(path, "Alive", proc.isAlive());
	}

	public void putFrames() {
		ThreadReference thread = manager.getJdi().getCurrentThread();
		String ppath = createObject(getPath(thread) + ".Stack");
		Set<String> keys = new HashSet<>();
		try {
			int frameCount = thread.frameCount();
			for (int i = 0; i < frameCount; i++) {
				StackFrame frame = thread.frame(i);
				String key = Integer.toString(i);
				keys.add(manager.key(key));
				putFrame(ppath, frame, key);
			}
		}
		catch (IncompatibleThreadStateException e) {
			// IGNORE
		}
		retainKeys(ppath, keys);
	}

	private void putFrame(String ppath, StackFrame frame, String key) {
		String path = createObject(frame, key, ppath);
		putFrameDetails(path, frame, key);
		insertObject(path);
	}

	private void putFrameDetails(String path, StackFrame frame, String key) {
		Location location = frame.location();
		setValue(path, "_display", "[" + key + "] " + location + ":" + location.method().name() +
			":" + location.codeIndex());
		putLocation(path, "Location", location);
		Address addr = manager.getAddressFromLocation(location);
		setValue(path, "PC", addr);

		String rpath = createObject(path + ".Registers");
		putRegisters(frame, rpath);
		createObject(path + ".Variables");
		try {
			createObject(frame.thisObject(), "This", path);
		}
		catch (Exception e) {
			// Ignore
		}
	}

	public void putLocationContainer(String path, Method m) {
		try {
			for (Location loc : m.allLineLocations()) {
				createObject(loc, loc.toString(), path);
			}
		}
		catch (AbsentInformationException e) {
			// Ignore
		}
	}

	public void putLocationContainer(String path, ReferenceType ref) {
		try {
			for (Location loc : ref.allLineLocations()) {
				createObject(loc, loc.toString(), path);
			}
		}
		catch (AbsentInformationException e) {
			// Ignore
		}
	}

	public void putLocation(String ppath, String key, Location location) {
		String path = createObject(location, key, ppath);
		putLocationDetails(path, location);
		insertObject(path);
	}

	public void putLocationDetails(String path, Location location) {
		Address addr = manager.getAddressFromLocation(location);
		if (isLoaded(location)) {
			setValue(path, "_display", manager.key(location.toString()) + ": " + addr);
			setValue(path, "Addr", addr);
		}
		setValue(path, "Index", location.codeIndex());
		setValue(path, "Line#", location.lineNumber());
		try {
			setValue(path, "Name", location.sourceName());
		}
		catch (AbsentInformationException e) {
			// IGNORE
		}
		try {
			setValue(path, "Path", location.sourcePath());
		}
		catch (AbsentInformationException e) {
			// IGNORE
		}
		Method method = location.method();
		createLink(location, "Method", method);
		createLink(location, "DeclaringType", location.declaringType());
		createLink(location, "ModuleRef", location.declaringType().module());
		//createObject(method, method.name(), path+".Method");
		//putMethodDetails(path, method);
	}

	private boolean isLoaded(Location location) {
		AddressRange range = manager.getAddressRange(location.method());
		return !range.equals(manager.defaultRange);
	}

	public void putLocalVariableContainer(String path, Map<LocalVariable, Value> variables) {
		for (LocalVariable lv : variables.keySet()) {
			putLocalVariable(path, lv, variables.get(lv));
		}
	}

	public void putLocalVariableContainer(String path, List<LocalVariable> variables) {
		for (LocalVariable lv : variables) {
			putLocalVariable(path, lv, null);
		}
	}

	public void putLocalVariable(String ppath, LocalVariable lv, Value value) {
		String path = createObject(lv, lv.name(), ppath);
		putLocalVariableDetails(path, lv);
		if (value != null) {
			putValue(path, "Value", value);
			setValue(path, "_display", lv.name() + ": " + value);
		}
		insertObject(path);
	}

	public void putLocalVariableDetails(String path, LocalVariable lv) {
		try {
			putType(path, "Type", lv.type());
		}
		catch (ClassNotLoadedException e) {
			// IGNORE
		}
		putLocalVariableAttributes(path, lv);
	}

	private void putLocalVariableAttributes(String ppath, LocalVariable lv) {
		String path = createObject(ppath + ".Attributes");
		setValue(path, "isArgument", lv.isArgument());
		if (lv.genericSignature() != null) {
			setValue(path, "GenericSignature", lv.genericSignature());
		}
		setValue(path, "Signature", lv.signature());
	}

	public void putMethodTypeContainer(String ppath, Method m) {
		try {
			for (Type type : m.argumentTypes()) {
				createObject(type, type.name(), ppath);
			}
		}
		catch (ClassNotLoadedException e) {
			createObject(ppath + "Class Not Loaded");
		}
	}

	public void putBreakpoints() {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		EventRequestManager requestManager = vm.eventRequestManager();
		String ppath = getPath(vm) + ".Breakpoints";
		createObject(ppath);
		Set<String> keys = new HashSet<>();

		List<BreakpointRequest> brkReqs = requestManager.breakpointRequests();
		for (BreakpointRequest req : brkReqs) {
			String key = manager.key(req.toString());
			keys.add(key);
			putReqBreakpoint(ppath, req, key);
		}

		List<AccessWatchpointRequest> watchReqs = requestManager.accessWatchpointRequests();
		for (AccessWatchpointRequest req : watchReqs) {
			String key = manager.key(req.toString());
			keys.add(key);
			putReqAccessWatchpoint(ppath, req, key);
		}

		List<ModificationWatchpointRequest> modReqs =
			requestManager.modificationWatchpointRequests();
		for (ModificationWatchpointRequest req : modReqs) {
			String key = manager.key(req.toString());
			keys.add(key);
			putReqModificationWatchpoint(ppath, req, key);
		}

		retainKeys(ppath, keys);
	}

	public void putEvents() {
		VirtualMachine vm = manager.getJdi().getCurrentVM();
		EventRequestManager requestManager = vm.eventRequestManager();
		String ppath = getPath(vm) + ".Events";
		createObject(ppath);
		Set<String> keys = new HashSet<>();

		List<VMDeathRequest> deathReqs = requestManager.vmDeathRequests();
		for (VMDeathRequest req : deathReqs) {
			keys.add(manager.key(req.toString()));
			putReqVMDeath(ppath, req, req.toString());
		}

		List<ThreadStartRequest> threadStartReqs = requestManager.threadStartRequests();
		for (ThreadStartRequest req : threadStartReqs) {
			keys.add(manager.key(req.toString()));
			putReqThreadStarted(ppath, req, req.toString());
		}

		List<ThreadDeathRequest> threadDeathReqs = requestManager.threadDeathRequests();
		for (ThreadDeathRequest req : threadDeathReqs) {
			keys.add(manager.key(req.toString()));
			putReqThreadExited(ppath, req, req.toString());
		}

		List<ExceptionRequest> excReqs = requestManager.exceptionRequests();
		for (ExceptionRequest req : excReqs) {
			keys.add(manager.key(req.toString()));
			putReqException(ppath, req, req.toString());
		}

		List<ClassPrepareRequest> loadReqs = requestManager.classPrepareRequests();
		for (ClassPrepareRequest req : loadReqs) {
			keys.add(manager.key(req.toString()));
			putReqClassLoad(ppath, req, req.toString());
		}

		List<ClassUnloadRequest> unloadReqs = requestManager.classUnloadRequests();
		for (ClassUnloadRequest req : unloadReqs) {
			keys.add(manager.key(req.toString()));
			putReqClassUnload(ppath, req, req.toString());
		}

		List<MethodEntryRequest> entryReqs = requestManager.methodEntryRequests();
		for (MethodEntryRequest req : entryReqs) {
			keys.add(manager.key(req.toString()));
			putReqMethodEntry(ppath, req, req.toString());
		}

		List<MethodExitRequest> exitReqs = requestManager.methodExitRequests();
		for (MethodExitRequest req : exitReqs) {
			keys.add(manager.key(req.toString()));
			putReqMethodExit(ppath, req, req.toString());
		}

		List<StepRequest> stepReqs = requestManager.stepRequests();
		for (StepRequest req : stepReqs) {
			keys.add(manager.key(req.toString()));
			putReqStep(ppath, req, req.toString());
		}

		List<MonitorContendedEnterRequest> monEnterReqs =
			requestManager.monitorContendedEnterRequests();
		for (MonitorContendedEnterRequest req : monEnterReqs) {
			keys.add(manager.key(req.toString()));
			putReqMonContendedEnter(ppath, req, req.toString());
		}

		List<MonitorContendedEnteredRequest> monEnteredReqs =
			requestManager.monitorContendedEnteredRequests();
		for (MonitorContendedEnteredRequest req : monEnteredReqs) {
			keys.add(manager.key(req.toString()));
			putReqMonContendedEntered(ppath, req, req.toString());
		}

		List<MonitorWaitRequest> monWaitReqs = requestManager.monitorWaitRequests();
		for (MonitorWaitRequest req : monWaitReqs) {
			keys.add(manager.key(req.toString()));
			putReqMonWait(ppath, req, req.toString());
		}

		List<MonitorWaitedRequest> monWaitedReqs = requestManager.monitorWaitedRequests();
		for (MonitorWaitedRequest req : monWaitedReqs) {
			keys.add(manager.key(req.toString()));
			putReqMonWaited(ppath, req, req.toString());
		}

		retainKeys(ppath, keys);
	}

	// REQUESTS //

	private void putReqVMDeath(String ppath, VMDeathRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqVMDeathDetails(path, req, key);
		insertObject(path);
	}

	private void putReqVMDeathDetails(String path, VMDeathRequest req, String key) {
		putFilterDetails(path, req);
	}

	private void putReqThreadStarted(String ppath, ThreadStartRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqThreadStartedDetails(path, req, key);
		insertObject(path);
	}

	private void putReqThreadStartedDetails(String path, ThreadStartRequest req, String key) {
		putFilterDetails(path, req);
	}

	private void putReqThreadExited(String ppath, ThreadDeathRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqThreadExitedDetails(path, req, key);
		insertObject(path);
	}

	private void putReqThreadExitedDetails(String path, ThreadDeathRequest req, String key) {
		putFilterDetails(path, req);
	}

	private void putReqBreakpoint(String ppath, BreakpointRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqBreakpointDetails(path, req, key);
		insertObject(path);
	}

	private void putReqBreakpointDetails(String path, BreakpointRequest req, String key) {
		Location location = req.location();
		setValue(path, "_display", "[" + key + "] " + location + ":" + location.method().name() +
			":" + location.codeIndex());
		Address addr = manager.getAddressFromLocation(location);
		AddressRangeImpl range = new AddressRangeImpl(addr, addr);
		setValue(path, "Range", range);
		createObject(location, location.toString(), path + ".Location");
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqAccessWatchpoint(String ppath, AccessWatchpointRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqAccessWatchpointDetails(path, req, key);
		insertObject(path);
	}

	private void putReqAccessWatchpointDetails(String path, AccessWatchpointRequest req,
			String key) {
		Field field = req.field();
		setValue(path, "_display", "[" + key + "] " + field + ":" + field.declaringType());
		// NB: This isn't correct, but we need a range (any range)
		AddressRange range =
			manager.getPoolAddressRange(field.declaringType(), getSize(field.declaringType()));
		setValue(path, "Range", range);
		createObject(field, field.toString(), path + ".Field");
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqModificationWatchpoint(String ppath, ModificationWatchpointRequest req,
			String key) {
		String path = createObject(req, key, ppath);
		putReqModificationWatchpointDetails(path, req, key);
		insertObject(path);
	}

	private void putReqModificationWatchpointDetails(String path, ModificationWatchpointRequest req,
			String key) {
		Field field = req.field();
		setValue(path, "_display", "[" + key + "] " + field + ":" + field.declaringType());
		// NB: This isn't correct, but we need a range (any range)
		AddressRange range =
			manager.getPoolAddressRange(field.declaringType(), getSize(field.declaringType()));
		setValue(path, "Range", range);
		createObject(field, field.toString(), path + ".Field");
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqException(String ppath, ExceptionRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqExceptionDetails(path, req, key);
		insertObject(path);
	}

	private void putReqExceptionDetails(String path, ExceptionRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
	}

	private void putReqClassLoad(String ppath, ClassPrepareRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqClassLoadDetails(path, req, key);
		insertObject(path);
	}

	private void putReqClassLoadDetails(String path, ClassPrepareRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqClassUnload(String ppath, ClassUnloadRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqClassUnloadDetails(path, req, key);
		insertObject(path);
	}

	private void putReqClassUnloadDetails(String path, ClassUnloadRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqMethodEntry(String ppath, MethodEntryRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqMethodEntryDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMethodEntryDetails(String path, MethodEntryRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqMethodExit(String ppath, MethodExitRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqMethodExitDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMethodExitDetails(String path, MethodExitRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqStep(String ppath, StepRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqStepRequestDetails(path, req, key);
		insertObject(path);
	}

	private void putReqStepRequestDetails(String path, StepRequest req, String key) {
		setValue(path, "Enabled", req.isEnabled());
		putFilterDetails(path, req);
	}

	private void putReqMonContendedEnter(String ppath, MonitorContendedEnterRequest req,
			String key) {
		String path = createObject(req, key, ppath);
		putReqMonContendedEnterDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMonContendedEnterDetails(String path, MonitorContendedEnterRequest req,
			String key) {
		putFilterDetails(path, req);
	}

	private void putReqMonContendedEntered(String ppath, MonitorContendedEnteredRequest req,
			String key) {
		String path = createObject(req, key, ppath);
		putReqMonContendedEnteredDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMonContendedEnteredDetails(String path, MonitorContendedEnteredRequest req,
			String key) {
		putFilterDetails(path, req);
	}

	private void putReqMonWait(String ppath, MonitorWaitRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqMonWaitDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMonWaitDetails(String path, MonitorWaitRequest req, String key) {
		putFilterDetails(path, req);
	}

	private void putReqMonWaited(String ppath, MonitorWaitedRequest req, String key) {
		String path = createObject(req, key, ppath);
		putReqMonWaitedDetails(path, req, key);
		insertObject(path);
	}

	private void putReqMonWaitedDetails(String path, MonitorWaitedRequest req, String key) {
		putFilterDetails(path, req);
	}

	private void putFilterDetails(String path, EventRequest req) {
		Object property = req.getProperty("Class");
		if (property != null) {
			if (property instanceof ReferenceType reftype) {
				setValue(path, "Class", reftype.name());
			}
		}
		property = req.getProperty("Instance");
		if (property != null) {
			if (property instanceof ObjectReference ref) {
				setValue(path, "Instance", ref.toString());
			}
		}
		property = req.getProperty("Thread");
		if (property != null) {
			if (property instanceof ThreadReference ref) {
				setValue(path, "Thread", ref.name());
			}
		}
	}

	public void ghidraTracePutModules() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutModules", false)) {
			putModuleReferenceContainer();
		}
	}

	public void ghidraTracePutClasses() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutClasses", false)) {
			VirtualMachine vm = manager.getJdi().getCurrentVM();
			putReferenceTypeContainer(getPath(vm) + ".Classes", vm.allClasses());
		}
	}

	public void ghidraTracePutThreads() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutThreads", false)) {
			VirtualMachine vm = manager.getJdi().getCurrentVM();
			putThreadContainer(getPath(vm), vm.allThreads(), false); // Do this first
			putThreadGroupContainer(getPath(vm), vm.topLevelThreadGroups());
		}
	}

	public void ghidraTracePutFrames() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutFrames", false)) {
			putFrames();
		}
	}

	public void ghidraTracePutAll() {
		state.requireTrace();
		try (RmiTransaction tx = state.trace.startTx("ghidraTracePutAll", false)) {
			putVMs();
			VirtualMachine vm = manager.getJdi().getCurrentVM();
			putProcesses();
			putThreadContainer(getPath(vm), vm.allThreads(), false);
			putThreadGroupContainer(getPath(vm), vm.topLevelThreadGroups());
			putFrames();
			putBreakpoints();
			putEvents();
			putReferenceTypeContainer(getPath(vm) + ".Classes", vm.allClasses());
		}
	}

	public void ghidraTraceInstallHooks() {
		manager.getHooks().installHooks();
	}

	public void ghidraTraceRemoveHooks() {
		manager.getHooks().removeHooks();
	}

	public void ghidraTraceSyncEnable() {
		try (RmiTransaction tx = state.trace.startTx("ghidraTraceSyncEnable", false)) {
			TraceJdiHooks hooks = manager.getHooks();
			hooks.installHooks();
			hooks.enableCurrentVM();
		}
	}

	public void ghidraTraceSyncDisable() {
		manager.getHooks().disableCurrentVM();
	}

	public void ghidraTraceSyncSynthStopped() {
		manager.getHooks().onStop(null, state.trace);
	}

	public void ghidraTraceWaitStopped(int timeout) {
		ThreadReference currentThread = manager.getJdi().getCurrentThread();
		if (currentThread == null) {
			return;
		}
		long start = System.currentTimeMillis();
		while (!currentThread.isSuspended()) {
			currentThread = manager.getJdi().getCurrentThread();
			try {
				Thread.sleep(100);
				long elapsed = System.currentTimeMillis() - start;
				if (elapsed > timeout) {
					throw new RuntimeException("Timed out waiting for thread to stop");
				}
			}
			catch (InterruptedException e) {
				Msg.error(this, "Wait interrupted");
			}
		}
	}

	private int getSize(ReferenceType reftype) {
		byte[] cp = reftype.constantPool();
		int sz = 1;
		if (cp != null && cp.length > 0) {
			sz = cp.length;
		}
		return sz;
	}

	public void setValue(String path, String key, Object value) {
		state.trace.setValue(path, key, value);
	}

	String getPath(Object obj) {
		return manager.pathForObj(obj);
	}

	RmiTraceObject proxyObject(Object obj) {
		String path = getPath(obj);
		return path == null ? null : RmiTraceObject.fromPath(state.trace, path);
	}

	private String createObject(String path) {
		state.trace.createObject(path);
		return path;
	}

	private String createObject(Object obj, String key, String ppath) {
		if (obj == null) {
			return null;
		}
		String path = manager.recordPath(obj, ppath, key);
		state.trace.createObject(path);
		return path;
	}

	private String insertObject(String path) {
		state.trace.insertObject(path);
		return path;
	}

	private void retainKeys(String ppath, Set<String> keys) {
		state.trace.retainValues(ppath, keys, ValueKinds.VK_ELEMENTS);
	}

	public void createLink(Object parent, String label, Object child) {
		String ppath = parent instanceof String ? (String) parent : getPath(parent);
		RmiTraceObject proxy = proxyObject(child);
		if (proxy != null) {
			setValue(ppath, label, proxy);
		}
		else {
			// TODO: is this really what we want to do?			
			String key = child.toString();
			if (child instanceof Method m) {
				key = m.name();
			}
			createObject(child, key, ppath + "." + label);
		}
	}

	public String getVmPath(VirtualMachine vm) {
		return manager.recordPath(vm, "VMs", vm.name());
	}

	public String getParentPath(String path) {
		String ppath = path.substring(0, path.lastIndexOf("."));
		if (ppath.endsWith(".Relations")) {
			return getParentPath(ppath);
		}
		return ppath;
	}

	public boolean setStatus(Object obj, boolean stopped) {
		String path = getPath(obj);
		if (obj == null || path == null) {
			return stopped;
		}
		boolean suspended = stopped;
		String name = obj.toString();
		if (obj instanceof ThreadReference thread) {
			suspended = thread.isSuspended();
			name = thread.name();
		}
		if (obj instanceof VirtualMachine vm) {
			Event currentEvent = jdi.getCurrentEvent();
			String shortName = vm.name().substring(0, vm.name().indexOf(" "));
			name = currentEvent == null ? shortName : shortName + " [" + currentEvent + "]";
		}
		setValue(path, TraceJdiManager.ACCESSIBLE_ATTRIBUTE_NAME, suspended);
		String annotation = suspended ? "(S)" : "(R)";
		setValue(path, TraceJdiManager.DISPLAY_ATTRIBUTE_NAME, name + " " + annotation);
		String tstate = suspended ? "STOPPED" : "RUNNING";
		setValue(path, TraceJdiManager.STATE_ATTRIBUTE_NAME, tstate);
		stopped |= suspended;
		return stopped;
	}
}
