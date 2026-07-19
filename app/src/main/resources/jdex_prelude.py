import re


def _node(host, descriptor):
    if "->" in descriptor:
        return Method(host, descriptor) if "(" in descriptor else Field(host, descriptor)
    return Class(host, descriptor)


def _to_py(v):
    if v is None or isinstance(v, (str, bytes, bool, int, float)):
        return v
    try:
        return [_to_py(x) for x in v]
    except TypeError:
        return v


def _as_dict(m):
    if m is None:
        return None
    try:
        items = [(e.getKey(), e.getValue()) for e in m.entrySet()]
    except (AttributeError, TypeError):
        items = [(k, m[k]) for k in m]
    return {k: _to_py(v) for k, v in items}


class _Node:
    """Base for descriptor-identified nodes. Equality and hashing are by descriptor."""

    def __init__(self, host, descriptor):
        self._host = host
        self.descriptor = descriptor

    def rename(self, new_name):
        """Rename this node in the project (persisted). Pass None or '' to clear the rename."""
        self._host.rename(self.descriptor, new_name)

    def xrefs_to(self):
        """Nodes that reference this one (callers, field accesses, type uses)."""
        return [_node(self._host, d) for d in self._host.xrefsTo(self.descriptor)]

    def __eq__(self, other):
        return isinstance(other, _Node) and self.descriptor == other.descriptor

    def __hash__(self):
        return hash(self.descriptor)


class Class(_Node):
    """A class, identified by its smali descriptor e.g. 'Lcom/foo/Bar;'."""

    @property
    def name(self):
        """Simple class name without package."""
        return self.descriptor[1:-1].rsplit("/", 1)[-1]

    @property
    def package(self):
        """Dotted package name, '' for the default package."""
        inner = self.descriptor[1:-1]
        return inner.rsplit("/", 1)[0].replace("/", ".") if "/" in inner else ""

    def methods(self):
        """Methods declared by this class."""
        return [Method(self._host, d) for d in self._host.classMethods(self.descriptor)]

    def fields(self):
        """Fields declared by this class."""
        return [Field(self._host, d) for d in self._host.classFields(self.descriptor)]

    def java(self):
        """Decompiled Java source for this class (top-level parent for inner classes)."""
        return self._host.classJava(self.descriptor)

    def smali(self):
        """Disassembled smali/bytecode listing for this class."""
        return self._host.classSmali(self.descriptor)

    def super_class(self):
        """The superclass as a Class, or None."""
        d = self._host.classSuper(self.descriptor)
        return Class(self._host, d) if d else None

    def interfaces(self):
        """Implemented interfaces as Class objects."""
        return [Class(self._host, d) for d in self._host.classInterfaces(self.descriptor)]

    def offset_at_line(self, line):
        """Bytecode offset (code units) for a 1-based decompiled-Java line, or None."""
        return self._host.offsetAtLine(self.descriptor, line)

    def strings(self):
        """Distinct string literals referenced by this class's methods, in first-seen order."""
        return list(self._host.classStrings(self.descriptor))

    def info(self):
        """Structured metadata dict: descriptor, name, package, super, interfaces, access_flags, modifiers."""
        return _as_dict(self._host.classInfo(self.descriptor))

    def __repr__(self):
        return "<Class %s>" % self.descriptor


class Method(_Node):
    """A method, identified as 'Lcom/foo/Bar;->name(args)ret'."""

    @property
    def declaring_class(self):
        """The Class that declares this method."""
        return Class(self._host, self.descriptor.split("->", 1)[0])

    @property
    def signature(self):
        """The 'name(args)ret' part of the descriptor."""
        return self.descriptor.split("->", 1)[1]

    @property
    def name(self):
        """Method name without signature."""
        return self.signature.split("(", 1)[0]

    def smali(self):
        """Disassembled smali/bytecode listing for just this method."""
        return self._host.methodSmali(self.descriptor)

    def instructions(self):
        """Structured bytecode rows, each a dict: offset, addr, line, mnemonic, operands, comment, resource."""
        rows = self._host.methodInstructions(self.descriptor)
        return None if rows is None else [_as_dict(r) for r in rows]

    def info(self):
        """Structured metadata dict: descriptor, name, signature, return_type, arg_types, access_flags, modifiers, registers."""
        return _as_dict(self._host.methodInfo(self.descriptor))

    def __repr__(self):
        return "<Method %s>" % self.descriptor


class Field(_Node):
    """A field, identified as 'Lcom/foo/Bar;->name'."""

    @property
    def declaring_class(self):
        """The Class that declares this field."""
        return Class(self._host, self.descriptor.split("->", 1)[0])

    @property
    def name(self):
        """Field name."""
        return self.descriptor.split("->", 1)[1].split(":", 1)[0]

    def info(self):
        """Structured metadata dict: descriptor, name, type, access_flags, modifiers."""
        return _as_dict(self._host.fieldInfo(self.descriptor))

    def reads(self):
        """Methods that read this field (IGET/SGET)."""
        return [Method(self._host, d) for d in self._host.fieldReads(self.descriptor)]

    def writes(self):
        """Methods that write this field (IPUT/SPUT)."""
        return [Method(self._host, d) for d in self._host.fieldWrites(self.descriptor)]

    def __repr__(self):
        return "<Field %s>" % self.descriptor


class Dex:
    """A dex file jadx could not load, identified by the SHA-256 of its original bytes."""

    def __init__(self, host, sha):
        self._host = host
        self.sha = sha

    @property
    def name(self):
        """The dex entry name (e.g. 'classes.dex' or an imported file name)."""
        return self._host.dexName(self.sha)

    @property
    def problems(self):
        """Human-readable header validation problems for the current bytes."""
        return list(self._host.dexProblems(self.sha))

    @property
    def malformed(self):
        """True while jadx still cannot load the current bytes."""
        return self._host.dexMalformed(self.sha)

    def bytes(self):
        """Current (patched) dex bytes."""
        return self._host.dexBytes(self.sha)

    def source_bytes(self):
        """Original unpatched dex bytes."""
        return self._host.dexSourceBytes(self.sha)

    def validate(self, data=None):
        """Validation problems for the current bytes, or for `data` if given."""
        return list(self._host.validateDex(self.bytes() if data is None else data))

    def repair(self):
        """Auto-fix the dex header, save the fix as a patch, reload. Returns True if now valid."""
        return self._host.repairDex(self.sha)

    def save(self, data):
        """Save hand-fixed `data` as the dex patch and reload (for fixes beyond the header)."""
        self._host.saveDex(self.sha, data)

    def __eq__(self, other):
        return isinstance(other, Dex) and self.sha == other.sha

    def __hash__(self):
        return hash(self.sha)

    def __repr__(self):
        return "<Dex %s %s>" % (self.name, self.sha[:12])


class _Ui:
    """Interaction with the jdex window: dialogs and navigation."""

    def __init__(self, host):
        self._host = host

    def message(self, text):
        """Show an information dialog."""
        self._host.uiMessage(str(text), False)

    def error(self, text):
        """Show an error dialog."""
        self._host.uiMessage(str(text), True)

    def ask(self, prompt, default=""):
        """Prompt for a line of text; returns the entered string, or None if cancelled."""
        return self._host.uiInput(str(prompt), str(default))

    def confirm(self, text):
        """Yes/No dialog; returns True if the user chose Yes."""
        return self._host.uiConfirm(str(text))

    def goto_offset(self, offset):
        """Reveal a file offset (int) in the bytecode view."""
        self._host.uiGotoOffset(int(offset))

    def open(self, node):
        """Reveal a Class, Method or Field (or a raw descriptor string) in the bytecode view."""
        self._host.uiOpen(node.descriptor if isinstance(node, _Node) else str(node))


class _Debug:
    """Debugger control: attach to a debuggable process, set breakpoints, step, inspect frames/locals."""

    def __init__(self, host):
        self._host = host

    @property
    def running(self):
        """True when a debug session is active (attached), False when detached."""
        return self._host.debugRunning()

    def devices(self):
        """Connected devices/emulators as a list of {serial, label, online}."""
        return [_as_dict(d) for d in self._host.debugDevices()]

    def processes(self, serial):
        """Running app processes on a device as a list of {pid, name}."""
        return [_as_dict(p) for p in self._host.debugProcesses(str(serial))]

    def attach(self, serial, pid):
        """Attach the debugger to process `pid` on device `serial`. Returns True on success."""
        return self._host.debugAttach(str(serial), int(pid))

    def detach(self):
        """Detach the debugger and let the process run freely."""
        self._host.debugDetach()

    def resume(self):
        """Resume execution after a stop."""
        self._host.debugResume()

    def pause(self):
        """Suspend the process at its current point."""
        self._host.debugPause()

    def step_into(self):
        """Step into the next call."""
        self._host.debugStepInto()

    def step_over(self):
        """Step over the next line."""
        self._host.debugStepOver()

    def step_out(self):
        """Step out of the current method."""
        self._host.debugStepOut()

    def breakpoint(self, descriptor, dex_pc=0):
        """Set a breakpoint at a method ('Lcom/foo/Bar;->m(...)V') and dex offset (code units)."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        self._host.debugSetBreakpoint(d, int(dex_pc))

    def clear_breakpoint(self, descriptor, dex_pc=0):
        """Remove a previously set breakpoint."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        self._host.debugClearBreakpoint(d, int(dex_pc))

    def state(self):
        """Current debugger state: 'detached', 'running' or 'stopped'."""
        return self._host.debugState()

    def frames(self):
        """Call stack at the current stop as a list of {index, description, descriptor, dex_pc}."""
        return [_as_dict(f) for f in self._host.debugFrames()]

    def variables(self, frame_index=0):
        """Local variables of a stack frame as a list of {name, type, value}."""
        return [_as_dict(v) for v in self._host.debugVariables(int(frame_index))]

    def read_memory(self, address, length):
        """Read `length` bytes of target memory at `address`; None if unavailable."""
        b = self._host.debugReadMemory(int(address), int(length))
        return bytes(b) if b is not None else None

    def write_memory(self, address, data):
        """Write raw bytes to target memory at `address`. Returns True on success."""
        return bool(self._host.debugWriteMemory(int(address), bytes(data)))

    def runtime_addr(self, native_id, vaddr):
        """Resolve a library vaddr ('libfoo.so', 0x1234) to its live runtime address; None if not loaded."""
        return self._host.debugRuntimeAddr(str(native_id), int(vaddr))

    def patch_native(self, native_id, vaddr, asm):
        """Assemble `asm` for the target arch and write it over the instruction at native_id+vaddr (live). True on success."""
        return bool(self._host.debugPatchNative(str(native_id), int(vaddr), str(asm)))

    def wait_until_stopped(self, timeout=10.0, poll=0.05):
        """Block until the debugger reports 'stopped' (a breakpoint/step hit) or `timeout` seconds
        elapse. Returns True if stopped."""
        import time
        deadline = time.monotonic() + float(timeout)
        while time.monotonic() < deadline:
            if self._host.debugState() == "stopped":
                return True
            time.sleep(float(poll))
        return self._host.debugState() == "stopped"


class _EmuObject:
    """A jdex object built by emulation (jdex.emu.new). Read/write fields, call methods, or pass
    it anywhere a value is expected (hook set_arg, run/resolve args). Lives in the session world,
    so its methods see shared statics/decrypted values."""

    def __init__(self, host, handle):
        self._host = host
        self._h = handle

    @property
    def type(self):
        """The object's type descriptor, e.g. 'Lcom/app/Foo;'."""
        return self._host.emuObjType(self._h)

    def get(self, name):
        """Read a field by name (walks the class hierarchy). Object fields come back as handles."""
        return _wrap_emu(self._host, self._host.emuObjGet(self._h, name))

    def set(self, name, value):
        """Write a field by name. Returns self for chaining."""
        self._host.emuObjSet(self._h, name, value)
        return self

    def call(self, short_id, *args):
        """Emulate a method on this object, e.g. call('sign(Ljava/lang/String;)Ljava/lang/String;', 'x')."""
        return _wrap_emu(self._host, self._host.emuObjCall(self._h, short_id, _ua(list(args))))

    def fields(self):
        """Snapshot of the object's fields as {name: value}."""
        return _as_dict(self._host.emuObjFields(self._h))

    def _jdex_unwrap(self):
        return self._h


def _wrap_emu(host, r):
    return _EmuObject(host, r) if host.emuIsObject(r) else _to_py(r)


def _ua(args):
    return [a._jdex_unwrap() if isinstance(a, _EmuObject) else a for a in args]


class _HookCall:
    """A call intercepted by an emulator hook. Read method/receiver/args; call set_arg(i, v) and/or
    replace(v) to alter arguments or supply a return value (and skip the body). An emulated-object
    handle (jdex.emu.new) may be passed to set_arg/replace."""

    def __init__(self, host, view):
        self._host = host
        self._v = view

    @property
    def method(self):
        return self._v.method

    @property
    def receiver(self):
        return _wrap_emu(self._host, self._v.receiver)

    @property
    def args(self):
        return _to_py(self._v.args)

    def set_arg(self, index, value):
        self._v.set_arg(index, value._jdex_unwrap() if isinstance(value, _EmuObject) else value)

    def replace(self, value):
        self._v.replace(value._jdex_unwrap() if isinstance(value, _EmuObject) else value)


class _Emulator:
    """Emulator: run/step/inspect dex methods in-process, replace register values, register
    per-APK stubs for unimplemented framework methods, and read statically resolved values."""

    def __init__(self, host, active=False):
        self._host = host
        self._a = active

    def new(self, class_desc, ctor_sig=None, *args):
        """Construct an object by emulating its <init> in the session world. Returns an object
        handle (.get/.set/.call/.fields), usable wherever a value is expected. None if the class or
        constructor is unknown. Works on both jdex.emu and jdex.this.emu."""
        cd = class_desc.descriptor if isinstance(class_desc, _Node) else str(class_desc)
        h = self._host.emuNew(self._a, cd, ctor_sig, _ua(list(args)))
        return None if h is None else _EmuObject(self._host, h)

    @property
    def running(self):
        """True when an emulation is active (a run is loaded/paused), False when detached."""
        return self._host.emuRunning(self._a)

    def run(self, descriptor, args=None, pause_at_entry=True):
        """Start emulating a method ('Lcom/foo/Bar;->m(I)I') with `args` (a list). Returns True if started."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        return self._host.emuRun(self._a, d, _ua(list(args or [])), bool(pause_at_entry))

    def detach(self):
        """Abort the current emulation."""
        self._host.emuDetach(self._a)

    def resume(self):
        """Resume after a stop."""
        self._host.emuResume(self._a)

    def pause(self):
        """Request a stop at the next instruction."""
        self._host.emuPause(self._a)

    def step_into(self):
        """Step into the next call."""
        self._host.emuStepInto(self._a)

    def step_over(self):
        """Step over the next call."""
        self._host.emuStepOver(self._a)

    def step_out(self):
        """Run until the current method returns."""
        self._host.emuStepOut(self._a)

    def breakpoint(self, descriptor, dex_pc=0):
        """Stop at a method and dex offset (code units)."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        self._host.emuSetBreakpoint(self._a, d, int(dex_pc))

    def clear_breakpoint(self, descriptor, dex_pc=0):
        """Remove a breakpoint."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        self._host.emuClearBreakpoint(self._a, d, int(dex_pc))

    def run_to_cursor(self, descriptor, dex_pc):
        """Resume and stop once at a method+offset."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        self._host.emuRunToCursor(self._a, d, int(dex_pc))

    def state(self):
        """Current state: 'detached', 'running' or 'stopped'."""
        return self._host.emuState(self._a)

    def frames(self):
        """Call stack at the current stop as a list of {index, description, descriptor, dex_pc}."""
        return [_as_dict(f) for f in self._host.emuFrames(self._a)]

    def variables(self, frame_index=0):
        """Registers of a frame as {name, type, value, ref, edit_key, available}.."""
        return [_as_dict(v) for v in self._host.emuVariables(self._a, int(frame_index))]

    def children(self, ref):
        """Fields/elements of an object or array variable by its `ref`."""
        return [_as_dict(v) for v in self._host.emuChildren(self._a, int(ref))]

    def set_value(self, edit_key, text):
        """Set a register from its `edit_key` (from variables()) to parsed `text`. True on success."""
        return self._host.emuSetValue(self._a, str(edit_key), str(text))

    def set_register(self, frame_index, reg, value):
        """Replace register `reg` of a frame with `value` directly. True on success."""
        return self._host.emuSetRegister(self._a, int(frame_index), int(reg), value)

    def return_value(self):
        """Return value of a finished emulation (None if unknown/void)."""
        return self._host.emuReturnValue(self._a)

    def resolve(self, descriptor, args=None):
        """Abstractly analyse a method (args optional, unknown if omitted); returns {return, unknown}."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        return _as_dict(self._host.emuResolve(self._a, d, None if args is None else _ua(list(args))))

    def register_field(self, cls, name, value):
        """Register a constant value for a framework static field ('Landroid/os/Build;', 'MODEL', ...)."""
        self._host.emuRegisterField(self._a, str(cls), str(name), value)

    def register_stub(self, cls, name, fn):
        """Implement an unimplemented framework method: fn(receiver, args) -> value.
        e.g. emu.register_stub('Landroid/text/TextUtils;', 'isEmpty', lambda r, a: not a[0])."""
        self._host.emuRegisterStub(self._a, str(cls), str(name), fn)

    def await_stop(self, timeout=5.0):
        """Block until stopped/finished or `timeout` seconds elapse. True if it stopped."""
        return self._host.emuAwaitStop(self._a, int(timeout * 1000))

    def await_finished(self, timeout=5.0):
        """Block until the emulation finishes or `timeout` seconds elapse. True if finished."""
        return self._host.emuAwaitFinished(self._a, int(timeout * 1000))

    def hook(self, descriptor, callback):
        """Intercept calls to `descriptor` (full signature); returns an id for unhook(). callback(call):
        call.set_arg(i, v) and/or call.replace(v) to supply a return value and skip the body."""
        d = descriptor.descriptor if isinstance(descriptor, _Node) else str(descriptor)
        return self._host.emuHook(self._a, d, lambda view: callback(_HookCall(self._host, view)))

    def unhook(self, hook_id):
        """Remove a hook by the id hook() returned. True if it was present."""
        return self._host.emuUnhook(self._a, int(hook_id))

    def hooks(self):
        """List installed hooks as [{id, descriptor}]. Hooks persist across runs until removed."""
        return [_as_dict(h) for h in self._host.emuHooksList(self._a)]

    def clear_hooks(self):
        """Remove all installed hooks."""
        self._host.emuClearHooks(self._a)


class _NativeEmulator:
    """Native (unidbg, ARM-only) emulator/debugger over an APK `.so`. `lib` is the path under lib/
    (e.g. 'arm64-v8a/libfoo.so')."""

    def __init__(self, host):
        self._host = host

    def decrypt(self, lib, cls, method_sig, cipher):
        """Run a native decryptor and return the plaintext. `cipher` is a bytes/list (jbyteArray) or a
        str (jstring). e.g. native_emu.decrypt('arm64-v8a/libfoo.so', 'com/foo/Native', 'dec([B)Ljava/lang/String;', data)."""
        return self._host.nativeEmuDecrypt(str(lib), str(cls), str(method_sig), cipher)

    def run(self, lib, cls, method_sig, args=None):
        """Emulate a JNI-bound native method, pausing at entry. args: bytes/str/ints."""
        return self._host.nativeEmuRun(str(lib), str(cls), str(method_sig), list(args or []))

    def load(self, lib):
        """Load/select a .so as the current emulator (runs JNI_OnLoad). Call before malloc/call/etc."""
        return self._host.nativeEmuLoad(str(lib))

    def malloc(self, size):
        """Allocate `size` bytes of guest memory; returns the runtime address."""
        return self._host.nativeEmuMalloc(int(size))

    def mem_read(self, address, size):
        """Read `size` bytes of guest memory."""
        return self._host.nativeEmuMemRead(int(address), int(size))

    def mem_write(self, address, data):
        """Write bytes/list to guest memory."""
        return self._host.nativeEmuWriteMemory(int(address), data)

    def reg_read(self, name):
        """Read a guest register (x0..x30/sp/pc/lr/fp or r0..r12/sp/lr/pc)."""
        return self._host.nativeEmuRegRead(str(name))

    def reg_write(self, name, value):
        """Write a guest register (any 64-bit value)."""
        return self._host.nativeEmuSetRegister(str(name), int(value))

    def call(self, address, *args):
        """Emulate the function at a runtime address with raw int/long args; returns the result, or None
        if a breakpoint paused it (then resume()/step and read return_value())."""
        return self._host.nativeEmuCall(int(address), list(args))

    def emulate(self, begin, until):
        """Run the current register/memory state from `begin` until `until`; returns the result register."""
        return self._host.nativeEmuEmulate(int(begin), int(until))

    def detach(self): self._host.nativeEmuDetach()
    def resume(self): self._host.nativeEmuResume()
    def step_into(self): self._host.nativeEmuStepInto()
    def step_over(self): self._host.nativeEmuStepOver()
    def step_out(self): self._host.nativeEmuStepOut()

    def symbol(self, name):
        """Runtime address of an exported symbol, or None."""
        return self._host.nativeEmuSymbolAddress(str(name))

    def module_base(self):
        """Load base address of the current .so."""
        return self._host.nativeEmuModuleBase()

    def breakpoint(self, address):
        """Break at a runtime address (e.g. native_emu.symbol('Java_...'))."""
        self._host.nativeEmuSetBreakpoint(int(address))

    def clear_breakpoint(self, address):
        self._host.nativeEmuClearBreakpoint(int(address))

    def state(self):
        """'running' | 'stopped' | 'detached'."""
        return self._host.nativeEmuState()

    def frames(self):
        """Native frames at the current stop: [{index, description, pc, file_offset}]."""
        return [_as_dict(f) for f in self._host.nativeEmuFrames()]

    def registers(self):
        """Guest registers at the current stop: {name: value}."""
        return _as_dict(self._host.nativeEmuRegisters())

    def set_register(self, name, value):
        """Patch a register at the current stop (takes effect on resume)."""
        return self._host.nativeEmuSetRegister(str(name), int(value))

    def write_memory(self, address, data):
        """Patch guest memory at the current stop."""
        return self._host.nativeEmuWriteMemory(int(address), data)

    def patch(self, file_offset, asm):
        """Assemble `asm` and patch it over the instruction at a .so file offset (Keystone)."""
        return self._host.nativeEmuPatch(int(file_offset), str(asm))

    def return_value(self):
        """The method's return (a recovered string, or None)."""
        return self._host.nativeEmuReturnValue()

    def await_stop(self, timeout=5.0):
        return self._host.nativeEmuAwaitStop(int(timeout * 1000))

    def await_finished(self, timeout=5.0):
        return self._host.nativeEmuAwaitFinished(int(timeout * 1000))

    def on_syscall(self, callback):
        """Intercept syscalls before unidbg handles them; callback(ctx) returns True if it fully
        handled the call (set the result via ctx.set_ret), else False/None. ctx: number(), arg(i),
        set_arg(i, v), reg(name), set_reg(name, v), read_mem(a, n), write_mem(a, d), ret(), set_ret(v)."""
        return self._host.nativeEmuOnSyscall(callback)

    def clear_syscall(self):
        """Remove the syscall interceptor installed by on_syscall()."""
        self._host.nativeEmuClearSyscall()

    def hook(self, address, on_enter=None, on_leave=None):
        """Hook the function at a runtime address (returns an unhook id). on_enter(ctx)/on_leave(ctx)
        inspect or modify args, registers and memory (see ctx accessors on on_syscall); on_leave fires
        for functions called from within the emulated code."""
        return self._host.nativeEmuHook(int(address), on_enter, on_leave)

    def replace(self, address, callback):
        """Replace the function at a runtime address (returns an unhook id). callback(ctx) -> int forces
        that return value and skips the original; return None to run the original."""
        return self._host.nativeEmuReplace(int(address), callback)

    def unhook(self, hook_id):
        """Remove a hook/replace/mem_watch/trace by the id it returned. True if it was present."""
        return self._host.nativeEmuUnhook(int(hook_id))

    def mem_watch(self, begin, end, on_read=None, on_write=None):
        """Watch guest memory in [begin, end]: on_read(acc)/on_write(acc) fire on every read/write of
        that range during emulation. acc: address(), size(), value() (write value; 0 for reads),
        is_write(), reg(name), set_reg(name, v), read_mem(a, n), write_mem(a, d). Returns an id;
        remove with unhook(id). Configure before run()/call()."""
        return self._host.nativeEmuMemWatch(int(begin), int(end), on_read, on_write)

    def trace(self, callback, begin=0, end=0):
        """Trace basic blocks as they execute — the whole image when begin==end==0, else only within
        [begin, end]. callback(block): block.address(), size(), reg(name), read_mem(a, n), ...
        Returns an id; remove with unhook(id). Configure before run()/call()."""
        return self._host.nativeEmuTrace(int(begin), int(end), callback)

    def modules(self):
        """Loaded native modules as [{name, base, size}] — the target .so plus dependencies
        (libc, libdl, ...). Use with symbol_at() to make sense of frames()/addresses."""
        return [_as_dict(m) for m in self._host.nativeEmuModules()]

    def symbol_at(self, address):
        """Resolve a runtime address to {name, module, offset} (nearest exported symbol at or below
        it), or None — turns a raw pc into e.g. libfoo.so!decryptBytes+0x1c."""
        r = self._host.nativeEmuSymbolAt(int(address))
        return _as_dict(r) if r is not None else None


class _Context:
    """The session running right now in the UI. `emu`/`debug` are None when nothing is running."""

    def __init__(self, host):
        self._host = host

    @property
    def emu(self):
        """The UI emulator. Hooks/inspection (hook/unhook/hooks/clear_hooks) persist across runs;
        state/frames/step reflect the current run (detached/empty when idle). Same API as jdex.emu,
        but run()/resolve() and other run-starting methods throw — it's the UI's run, not yours."""
        return _Emulator(self._host, active=True)

    @property
    def debug(self):
        """The live device debugger. Check .running to see if a session is attached."""
        return _Debug(self._host)


class _Config:
    """Deobfuscation engine configuration. Setters re-analyze the project so changes take effect."""

    def __init__(self, host):
        self._host = host

    def _apply(self, emulator, decrypt_startup, cleanup, passes):
        self._host.configApply(bool(emulator), bool(decrypt_startup), bool(cleanup), [str(p) for p in passes])

    @property
    def emulator_enabled(self):
        """Master switch; when False JDEC output is raw jadx (no deobfuscation passes)."""
        return self._host.configEmulatorEnabled()

    @emulator_enabled.setter
    def emulator_enabled(self, v):
        self._apply(v, self.decrypt_strings_at_startup, self.code_cleanup, self.passes)

    @property
    def decrypt_strings_at_startup(self):
        """When True, decrypt every class's strings at load; otherwise decrypt lazily per class."""
        return self._host.configDecryptStringsAtStartup()

    @decrypt_strings_at_startup.setter
    def decrypt_strings_at_startup(self, v):
        self._apply(self.emulator_enabled, v, self.code_cleanup, self.passes)

    @property
    def code_cleanup(self):
        """Type recovery, dead-code / opaque-branch removal, const-fold and CFG repair."""
        return self._host.configCodeCleanup()

    @code_cleanup.setter
    def code_cleanup(self, v):
        self._apply(self.emulator_enabled, self.decrypt_strings_at_startup, v, self.passes)

    @property
    def passes(self):
        """Enabled deobfuscation passes (list of ids); assign a list of ids to change."""
        return _to_py(self._host.configPasses())

    @passes.setter
    def passes(self, v):
        self._apply(self.emulator_enabled, self.decrypt_strings_at_startup, self.code_cleanup, v)

    @property
    def available_passes(self):
        """All toggleable deobfuscation pass ids."""
        return _to_py(self._host.configAllPasses())

    def apply(self, emulator_enabled=None, decrypt_strings_at_startup=None, code_cleanup=None, passes=None):
        """Set any subset of options at once and re-analyze the project once."""
        self._apply(
            self.emulator_enabled if emulator_enabled is None else emulator_enabled,
            self.decrypt_strings_at_startup if decrypt_strings_at_startup is None else decrypt_strings_at_startup,
            self.code_cleanup if code_cleanup is None else code_cleanup,
            self.passes if passes is None else passes,
        )


class _Jdex:
    """Top-level scripting facade, bound as `jdex`."""

    def __init__(self, host):
        self._host = host
        self.ui = _Ui(host)
        self.debug = _Debug(host)
        self.emu = _Emulator(host)
        self.native_emu = _NativeEmulator(host)
        self.this = _Context(host)
        self.config = _Config(host)

    def files(self):
        """Names of every file entry inside the loaded APK (or the dex name for a bare dex)."""
        return list(self._host.fileNames())

    def read_file(self, path):
        """Raw bytes of one APK entry by name; empty bytes if absent."""
        return self._host.readFile(str(path))

    def assemble(self, asm, arch, address=0):
        """Assemble instruction text to bytes for `arch` ('arm64','arm','x86','x86_64','mips','mips64'); None on error."""
        b = self._host.assemble(str(asm), str(arch), int(address))
        return bytes(b) if b is not None else None

    def import_file(self, name, data):
        """Add a file (bytes) under `name`, type auto-detected: a dex is merged into
        analysis; a native ELF is placed in its detected lib/<abi>/ folder; anything
        else goes to 'unknown'. Asks to override if the name already exists."""
        self._host.importFile(str(name), data)

    def classes(self):
        """All classes including inner classes."""
        return [Class(self._host, d) for d in self._host.classDescriptors()]

    def get_class(self, name):
        """Look up a class by descriptor ('Lcom/foo/Bar;') or dotted name ('com.foo.Bar'); None if absent."""
        desc = name if name.startswith("L") and name.endswith(";") else "L" + name.replace(".", "/") + ";"
        return Class(self._host, desc) if self._host.hasClass(desc) else None

    def find_classes(self, pattern):
        """Classes whose descriptor or simple name matches the regex `pattern`."""
        rx = re.compile(pattern)
        return [c for c in self.classes() if rx.search(c.descriptor) or rx.search(c.name)]

    def find_methods(self, pattern):
        """Methods across all classes whose descriptor matches the regex `pattern`."""
        return [Method(self._host, d) for d in self._host.findMethods(pattern)]

    def find_fields(self, pattern):
        """Fields across all classes whose descriptor matches the regex `pattern`."""
        return [Field(self._host, d) for d in self._host.findFields(pattern)]

    def strings(self):
        """Every string literal across all classes as dicts {value, method}. Expensive: scans all bytecode."""
        return [_as_dict(r) for r in self._host.allStrings()]

    def search_code(self, pattern, limit=1000):
        """Bytecode lines matching regex `pattern`, as dicts {method, offset, text}. Expensive full scan, capped at `limit`."""
        return [_as_dict(r) for r in self._host.searchCode(pattern, limit)]

    def set_annotation(self, node, offset, text):
        """Attach or replace a annotation."""
        d = node.descriptor if isinstance(node, _Node) else str(node)
        self._host.setAnnotation(d, int(offset), None if text is None else str(text))

    def clear_annotations(self):
        """Remove all annotations."""
        self._host.clearAnnotations()

    def manifest(self):
        """Decoded AndroidManifest.xml text, '' if none."""
        return self._host.manifest()

    def permissions(self):
        """Requested permission names from <uses-permission>."""
        return list(self._host.permissions())

    def components(self, kind="activity"):
        """Declared component names for a manifest tag: activity, service, receiver or provider."""
        return list(self._host.components(kind))

    def app_package(self):
        """Application package id, or None."""
        return self._host.appPackage()

    def main_activity(self):
        """Launcher activity class name, or None."""
        return self._host.mainActivity()

    def malformed_dexes(self):
        """Dex files jadx could not load, as Dex objects you can inspect and repair."""
        return [Dex(self._host, sha) for sha in self._host.dexShas()]

    def jadx(self):
        """The raw jadx JadxDecompiler, for anything the facade does not cover."""
        return self._host.jadx()


jdex = _Jdex(_jdex_host)
del _jdex_host
