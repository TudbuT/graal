/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.wasm.api;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import org.graalvm.wasm.WasmContext;
import org.graalvm.wasm.WasmFunction;
import org.graalvm.wasm.WasmFunctionInstance;
import org.graalvm.wasm.WasmMath;
import org.graalvm.wasm.WasmTable;
import org.graalvm.wasm.WasmType;
import org.graalvm.wasm.WasmVoidResult;
import org.graalvm.wasm.exception.WasmException;
import org.graalvm.wasm.exception.WasmJsApiException;

import static org.graalvm.wasm.api.JsConstants.JS_LIMITS;

public class WebAssembly extends Dictionary {
    private final WasmContext currentContext;

    public WebAssembly(WasmContext currentContext) {
        this.currentContext = currentContext;
        addMember("compile", new Executable(args -> compile(args)));
        addMember("instantiate", new Executable(args -> instantiate(args)));
        addMember("validate", new Executable(args -> validate(args)));
        addMember("Memory", new Executable(args -> createMemory(args)));
        addMember("Global", new Executable(args -> createGlobal(args)));

        Dictionary module = new Dictionary();
        module.addMember("exports", new Executable(args -> moduleExports(args)));
        module.addMember("imports", new Executable(args -> moduleImports(args)));
        module.addMember("customSections", new Executable(args -> moduleCustomSections(args)));
        addMember("Module", module);

        addMember("table_alloc", new Executable(args -> tableAlloc(args)));
        addMember("table_grow", new Executable(args -> tableGrow(args)));
        addMember("table_read", new Executable(args -> tableRead(args)));
        addMember("table_write", new Executable(args -> tableWrite(args)));
        addMember("table_size", new Executable(args -> tableSize(args)));

        addMember("func_type", new Executable(args -> funcType(args)));
    }

    private Object instantiate(Object[] args) {
        checkArgumentCount(args, 2);
        Object source = args[0];
        Object importObject = args[1];
        if (source instanceof Module) {
            return instantiate((Module) source, importObject);
        } else {
            return instantiate(toBytes(source), importObject);
        }
    }

    public WebAssemblyInstantiatedSource instantiate(byte[] source, Object importObject) {
        final Module module = compile(source);
        final Instance instance = instantiate(module, importObject);
        return new WebAssemblyInstantiatedSource(module, instance);
    }

    public Instance instantiate(Module module, Object importObject) {
        final TruffleContext innerTruffleContext = currentContext.environment().newContextBuilder().build();
        final Object prev = innerTruffleContext.enter(null);
        try {
            return new Instance(innerTruffleContext, module, importObject);
        } finally {
            innerTruffleContext.leave(null, prev);
        }
    }

    private Object compile(Object[] args) {
        checkArgumentCount(args, 1);
        return compile(toBytes(args[0]));
    }

    @SuppressWarnings("unused")
    public Module compile(byte[] source) {
        return new Module(currentContext, source);
    }

    private boolean validate(Object[] args) {
        checkArgumentCount(args, 1);
        return validate(toBytes(args[0]));
    }

    private boolean validate(byte[] bytes) {
        try {
            compile(bytes);
            return true;
        } catch (WasmException ex) {
            return false;
        }
    }

    private static void checkArgumentCount(Object[] args, int requiredCount) {
        if (args.length < requiredCount) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Insufficient number of arguments");
        }
    }

    private static byte[] toBytes(Object source) {
        InteropLibrary interop = InteropLibrary.getUncached(source);
        if (interop.hasArrayElements(source)) {
            try {
                long size = interop.getArraySize(source);
                if (size == (int) size) {
                    byte[] bytes = new byte[(int) size];
                    for (int i = 0; i < bytes.length; i++) {
                        Object element = interop.readArrayElement(source, i);
                        if (element instanceof Number) {
                            bytes[i] = ((Number) element).byteValue();
                        } else {
                            bytes[i] = InteropLibrary.getUncached(element).asByte(element);
                        }
                    }
                    return bytes;
                }
            } catch (InteropException iex) {
                throw cannotConvertToBytesError(iex);
            }
        }
        throw cannotConvertToBytesError(null);
    }

    private static WasmJsApiException cannotConvertToBytesError(Throwable cause) {
        WasmJsApiException.Kind kind = WasmJsApiException.Kind.TypeError;
        String message = "Cannot convert to bytes";
        return (cause == null) ? new WasmJsApiException(kind, message) : new WasmJsApiException(kind, message, cause);
    }

    private static int[] toSizeLimits(Object[] args) {
        if (args.length == 0) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Initial argument is required");
        }

        int initial;
        try {
            initial = InteropLibrary.getUncached().asInt(args[0]);
        } catch (UnsupportedMessageException ex) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Initial argument must be convertible to int");
        }

        int maximum;
        if (args.length == 1) {
            maximum = -1;
        } else {
            try {
                maximum = InteropLibrary.getUncached().asInt(args[1]);
            } catch (UnsupportedMessageException ex) {
                throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Maximum argument must be convertible to int");
            }
        }

        return new int[]{initial, maximum};
    }

    private static Object createMemory(Object[] args) {
        final int[] limits = toSizeLimits(args);
        return Memory.create(limits[0], limits[1]);
    }

    private static Object createGlobal(Object[] args) {
        checkArgumentCount(args, 3);

        String valueType;
        try {
            valueType = InteropLibrary.getUncached().asString(args[0]);
        } catch (UnsupportedMessageException ex) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument (value type) must be convertible to String");
        }

        boolean mutable;
        try {
            mutable = InteropLibrary.getUncached().asBoolean(args[1]);
        } catch (UnsupportedMessageException ex) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument (mutable) must be convertible to boolean");
        }

        Object value = args[2];
        InteropLibrary valueInterop = InteropLibrary.getUncached(value);
        Object wasmValue;
        try {
            if ("i32".equals(valueType)) {
                wasmValue = valueInterop.asInt(value);
            } else if ("i64".equals(valueType)) {
                wasmValue = valueInterop.asLong(value);
            } else if ("f32".equals(valueType)) {
                wasmValue = valueInterop.asFloat(value);
            } else if ("f64".equals(valueType)) {
                wasmValue = valueInterop.asDouble(value);
            } else {
                throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Invalid value type");
            }
        } catch (UnsupportedMessageException ex) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Cannot convert value to the specified value type");
        }

        return new Global(valueType, mutable, wasmValue);
    }

    private static Module toModule(Object[] args) {
        checkArgumentCount(args, 1);
        if (args[0] instanceof Module) {
            return (Module) args[0];
        } else {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be Module");
        }
    }

    private static Object moduleExports(Object[] args) {
        return toModule(args).exports();
    }

    private static Object moduleImports(Object[] args) {
        return toModule(args).imports();
    }

    private static Object moduleCustomSections(Object[] args) {
        checkArgumentCount(args, 2);
        return toModule(args).customSections(args[1]);
    }

    private static Object tableAlloc(Object[] args) {
        final int[] limits = toSizeLimits(args);
        return tableAlloc(limits[0], limits[1]);
    }

    public static WasmTable tableAlloc(int initial, int maximum) {
        if (Integer.compareUnsigned(initial, maximum) > 0) {
            throw new WasmJsApiException(WasmJsApiException.Kind.LinkError, "Min table size exceeds max memory size");
        }
        if (Integer.compareUnsigned(initial, JS_LIMITS.memoryInstanceSizeLimit()) > 0) {
            throw new WasmJsApiException(WasmJsApiException.Kind.LinkError, "Min table size exceeds implementation limit");
        }
        final int maxAllowedSize = WasmMath.minUnsigned(maximum, JS_LIMITS.memoryInstanceSizeLimit());
        return new WasmTable(initial, maximum, maxAllowedSize);
    }

    private static Object tableGrow(Object[] args) {
        checkArgumentCount(args, 2);
        if (!(args[0] instanceof WasmTable)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be wasm table");
        }
        if (!(args[1] instanceof Integer)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Second argument must be integer");
        }
        WasmTable table = (WasmTable) args[0];
        int delta = (Integer) args[1];
        return tableGrow(table, delta);
    }

    public static int tableGrow(WasmTable table, int delta) {
        final int size = table.size();
        try {
            table.grow(delta);
        } catch (IllegalArgumentException e) {
            throw new WasmJsApiException(WasmJsApiException.Kind.RangeError, e.getMessage());
        }
        return size;
    }

    private static Object tableRead(Object[] args) {
        checkArgumentCount(args, 2);
        if (!(args[0] instanceof WasmTable)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be wasm table");
        }
        if (!(args[1] instanceof Integer)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Second argument must be integer");
        }
        WasmTable table = (WasmTable) args[0];
        int index = (Integer) args[1];
        return tableRead(table, index);
    }

    public static Object tableRead(WasmTable table, int index) {
        try {
            final Object result = table.get(index);
            return result == null ? WasmVoidResult.getInstance() : result;
        } catch (IndexOutOfBoundsException e) {
            throw new WasmJsApiException(WasmJsApiException.Kind.RangeError, "Table index out of bounds: " + e.getMessage());
        }
    }

    private static Object tableWrite(Object[] args) {
        checkArgumentCount(args, 3);
        if (!(args[0] instanceof WasmTable)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be wasm table");
        }
        if (!(args[1] instanceof Integer)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Second argument must be integer");
        }
        WasmTable table = (WasmTable) args[0];
        int index = (Integer) args[1];
        return tableWrite(table, index, args[2]);
    }

    public static Object tableWrite(WasmTable table, int index, Object element) {
        final WasmFunctionInstance functionInstance;
        if (element instanceof WasmFunctionInstance) {
            functionInstance = (WasmFunctionInstance) element;
        } else if (InteropLibrary.getUncached(element).isNull(element)) {
            functionInstance = null;
        } else {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Invalid table element");
        }

        try {
            table.set(index, functionInstance);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new WasmJsApiException(WasmJsApiException.Kind.RangeError, "Table index out of bounds: " + e.getMessage());
        }

        return WasmVoidResult.getInstance();
    }

    private static Object tableSize(Object[] args) {
        checkArgumentCount(args, 1);
        if (!(args[0] instanceof WasmTable)) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be wasm table");
        }
        WasmTable table = (WasmTable) args[0];
        return tableSize(table);
    }

    public static int tableSize(WasmTable table) {
        return table.size();
    }

    private static Object funcType(Object[] args) {
        checkArgumentCount(args, 1);
        if (args[0] instanceof WasmFunctionInstance) {
            WasmFunction fn = ((WasmFunctionInstance) args[0]).function();
            return functionTypeToString(fn);
        } else {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "First argument must be wasm function");
        }
    }

    public static String functionTypeToString(WasmFunction f) {
        StringBuilder typeInfo = new StringBuilder();

        typeInfo.append(f.index());

        typeInfo.append('(');
        int argumentCount = f.numArguments();
        for (int i = 0; i < argumentCount; i++) {
            typeInfo.append(ValueType.fromByteValue(f.argumentTypeAt(i)));
        }
        typeInfo.append(')');

        byte returnType = f.returnType();
        if (returnType != WasmType.VOID_TYPE) {
            typeInfo.append(ValueType.fromByteValue(f.returnType()));
        }
        return typeInfo.toString();
    }

}
