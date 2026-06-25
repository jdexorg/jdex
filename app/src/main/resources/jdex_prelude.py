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
        elapse. Returns True if stopped. Safe to call from a script after resume()."""
        import time
        deadline = time.monotonic() + float(timeout)
        while time.monotonic() < deadline:
            if self._host.debugState() == "stopped":
                return True
            time.sleep(float(poll))
        return self._host.debugState() == "stopped"


class _Jdex:
    """Top-level scripting facade, bound as `jdex`."""

    def __init__(self, host):
        self._host = host
        self.ui = _Ui(host)
        self.debug = _Debug(host)

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
