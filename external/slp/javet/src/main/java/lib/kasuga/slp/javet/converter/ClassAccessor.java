package lib.kasuga.slp.javet.converter;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.interop.converters.IJavetConverter;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueObject;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.security.Api;
import lib.kasuga.scripting.security.SecurityEngineFeature;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.value.JavetValueBridge;
import lombok.Setter;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class ClassAccessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final V8Runtime runtime;
    private final IJavetConverter provider;
    private final Class<?> classType;
    Map<String, Function<V8Value[], V8Value>> quickAccessors = new HashMap<>();
    Map<String, MethodOverrideMap> methods = new HashMap<>();
    Map<String, Field> fields = new HashMap<>();
    Map<String, Api> apiAnnotations = new HashMap<>();

    @Setter
    SecurityEngineFeature securityFeature;
    @Setter
    ScriptEngine engine;

    boolean isFunctionalInterface = false;
    String functionalMethodName;

    public ClassAccessor(
            V8Runtime runtime,
            IJavetConverter provider,
            Class<?> classType
    ){
        this.runtime = runtime;
        this.provider = provider;
        this.classType = classType;
    }


    public Object invoke(
            Object target,
            String name,
            V8Value ...value
    ) throws InvocationTargetException, IllegalAccessException, JavetException {
        checkSecurity(name);

        if(quickAccessors.containsKey(name)) {
            return quickAccessors.get(name).apply(value);
        }

        if(!methods.containsKey(name)) {
            LOGGER.error("[ClassAccessor] invoke: method '{}' not found on {}. Available: {}", name, classType.getName(), methods.keySet());
            throw new RuntimeException("Illegal invocation");
        }

        MethodOverrideMap overrideMap = methods.get(name);

        int valueSize = value == null ? 0 : value.length;
        BitSet convertMask = overrideMap.converterMask.get(valueSize);
        List<Method> localMethods = overrideMap.methods.get(valueSize);

        boolean varArgMethods = !overrideMap.varArgsMethods.isEmpty();
        if((convertMask == null || localMethods == null) && !varArgMethods) {
            LOGGER.error("[ClassAccessor] invoke: no matching overload for {}.{} with {} args", classType.getSimpleName(), name, valueSize);
            throw new RuntimeException("Illegal invocation");
        }

        List<Method> consideringMethod = new ArrayList<>();
        if(localMethods != null) consideringMethod.addAll(localMethods);
        consideringMethod.addAll(overrideMap.varArgsMethods);

        Object[] arrayParameters = new Object[valueSize];

        for(int i = 0; i < valueSize ; i++) {
            if(varArgMethods || !convertMask.get(i)){
                arrayParameters[i] = JavetValueBridge.wrap(value[i]);
                continue;
            }
            Object nativeObject = provider.toObject(value[i]);
            arrayParameters[i] = nativeObject == null ?
                    value[i] : nativeObject;
        }

        ScriptValue[] values = new ScriptValue[valueSize];

        for(int methodIndex = 0; methodIndex < consideringMethod.size(); methodIndex++){
            Method localMethod = consideringMethod.get(methodIndex);
            Class<?>[] parameterTypes = localMethod.getParameterTypes();
            Parameter[] parameterInstances = localMethod.getParameters();
            Object[] parameters = new Object[valueSize];
            boolean isSignatureMatch = true;
            int incomeParameterIndex = valueSize - 1;
            boolean lastMatchVarargs = false;
            for (int acceptParameterIndex = parameterTypes.length - 1; acceptParameterIndex >= 0; acceptParameterIndex--) {
                if(lastMatchVarargs && incomeParameterIndex < 0) {
                    continue;
                }
                Class<?> parameterType = parameterTypes[acceptParameterIndex];
                if(parameterType == ScriptValue.class){
                    if(values[incomeParameterIndex] == null){
                        values[incomeParameterIndex] = JavetValueBridge.wrap(value[incomeParameterIndex]);
                    }
                    parameters[incomeParameterIndex] = values[incomeParameterIndex];

                    lastMatchVarargs = false;
                    if(parameterInstances[acceptParameterIndex].isVarArgs()){
                        acceptParameterIndex++;
                        lastMatchVarargs = true;
                    }
                    incomeParameterIndex--;

                    continue;
                }

                if(arrayParameters[incomeParameterIndex] == null && value[incomeParameterIndex] != null) {
                    Object nativeObject = provider.toObject(value[incomeParameterIndex]);
                    arrayParameters[incomeParameterIndex] = nativeObject == null ?
                            value[incomeParameterIndex] : nativeObject;
                }

                if(arrayParameters[incomeParameterIndex] == null) {
                    isSignatureMatch = false;
                    break;
                }

                Class<?> valueType = arrayParameters[incomeParameterIndex].getClass();

                if(parameterInstances[acceptParameterIndex].isVarArgs()){
                    parameterType = parameterType.componentType();
                }

                if(
                        parameterType == valueType ||
                        parameterType.isAssignableFrom(valueType) ||
                        isAutoboxingCompatible(parameterType, valueType)
                ) {
                    parameters[incomeParameterIndex] = arrayParameters[incomeParameterIndex];
                    lastMatchVarargs = false;
                    if(parameterInstances[acceptParameterIndex].isVarArgs()){
                        acceptParameterIndex++;
                        lastMatchVarargs = true;
                    }
                    incomeParameterIndex--;
                    continue;
                }
                if(lastMatchVarargs) {
                    lastMatchVarargs = false;
                    continue;
                }
                isSignatureMatch = false;
                break;
            }
            if(!isSignatureMatch){
                continue;
            }
            if(localMethod.isVarArgs()) {
                int totalParams = localMethod.getParameterCount();
                int totalNonVarargParams = totalParams - 1;
                Object[] varArgsParameter = new Object[totalParams];
                System.arraycopy(
                        parameters,
                        0,
                        varArgsParameter,
                        0,
                        localMethod.getParameterCount() - 1
                );
                Object[] varArgs = (Object[]) Array.newInstance(
                        localMethod.getParameterTypes()[totalNonVarargParams].getComponentType(),
                        valueSize - totalNonVarargParams
                );
                System.arraycopy(
                        parameters,
                        totalNonVarargParams,
                        varArgs,
                        0,
                        valueSize - totalNonVarargParams
                );
                varArgsParameter[totalNonVarargParams] = varArgs;
                parameters = varArgsParameter;
            }
            return localMethod.invoke(target, parameters);
        }
        throw new RuntimeException("Illegal invocation");
    }

    public Object get(Object object, String name) throws IllegalAccessException {
        checkSecurity(name);
        return fields.get(name).get(object);
    }

    public Object set(
            Object object,
            String name,
            V8Value value
    ) throws JavetException, IllegalAccessException {
        checkSecurity(name);
        Object nativeObject = provider.toObject(value);
        if(
                !fields.get(name).getType().isAssignableFrom(nativeObject.getClass()) &&
                !Modifier.isFinal(fields.get(name).getModifiers())
        ) {
            throw new RuntimeException("Illegal operation");
        }
        fields.get(name).set(object, nativeObject);
        return nativeObject;
    }

    private void checkSecurity(String name) {
        Api api = apiAnnotations.get(name);
        if (api != null && securityFeature != null && engine != null) {
            if (!securityFeature.check(engine, api)) {
                throw new RuntimeException("Security check denied: " + name);
            }
        }
    }

    private static boolean isAutoboxingCompatible(Class<?> parameterType, Class<?> valueType) {
        if(parameterType == int.class) return valueType == Integer.class;
        if(parameterType == long.class) return valueType == Long.class;
        if(parameterType == double.class) return valueType == Double.class;
        if(parameterType == float.class) return valueType == Float.class;
        if(parameterType == boolean.class) return valueType == Boolean.class;
        if(parameterType == byte.class) return valueType == Byte.class;
        if(parameterType == short.class) return valueType == Short.class;
        if(parameterType == char.class) return valueType == Character.class;
        return false;
    }

    public void addMethod(String name, Method method, Api api){
        MethodOverrideMap overrideMap = methods.computeIfAbsent(name, (i)->new MethodOverrideMap());
        overrideMap.initIfAbsent(method.getParameterCount());
        overrideMap.methods.get(method.getParameterCount()).add(method);
        Class<?>[] parameterTypes = method.getParameterTypes();

        // A parameter typed as ScriptValue — or any of its subtypes (ScriptFunction, ScriptObject,
        // ScriptArray, ScriptPrimitive) — must take the JavetValueBridge.wrap fast path (which yields a
        // JavetValueXxx), not provider.toObject (which yields a raw JavetEntity* that no ScriptValue param
        // is assignable from). Without this, a JS function passed to a ScriptFunction-typed @Api param
        // arrives as a JavetEntityFunction and matches no overload ("Illegal invocation").
        for (int i = 0; i < parameterTypes.length; i++) {
            if(ScriptValue.class.isAssignableFrom(parameterTypes[i])){
                overrideMap.converterMask.get(method.getParameterCount()).set(i, false);
            }
        }

        if(method.getReturnType() != Void.class){
            overrideMap.isVoidReturn = false;
        }

        if(method.isVarArgs()) {
            overrideMap.varArgsMethods.add(method);
        }

        if (api != null) {
            apiAnnotations.put(name, api);
        }
    }

    public void addField(String name, Field field, Api api){
        this.fields.put(name, field);
        if (api != null) {
            apiAnnotations.put(name, api);
        }
    }

    public static ClassAccessor collect(
            V8Runtime runtime,
            IJavetConverter converter,
            Class<?> classType
    ){
        ClassAccessor accessor = new ClassAccessor(runtime, converter, classType);
        Method[] methods = classType.getMethods();
        HashSet<Method> filteredMethods = new HashSet<>();
        Map<Method, Api> methodApis = new HashMap<>();
        for (Method method : methods) {
            Api api = method.getAnnotation(Api.class);
            if(api != null && api.export()){
                filteredMethods.add(method);
                methodApis.put(method, api);
            }
        }
        Class<?>[] interfaces = classType.getInterfaces();
        Map<Method, Method> varArgsMethods = new HashMap<>();
        for (int i = 0; i < interfaces.length; i++) {
            Method[] interfaceMethods = interfaces[i].getDeclaredMethods();
            boolean isFuncInterface = interfaces[i].isAnnotationPresent(FunctionalInterface.class);
            for (Method interfaceMethod : interfaceMethods) {
                boolean isAbstract = Modifier.isAbstract(interfaceMethod.getModifiers());
                for(Method method : methods){
                    boolean paramMatch = Arrays.equals(method.getTypeParameters(), interfaceMethod.getTypeParameters());
                    boolean returnMatch = method.getReturnType() == interfaceMethod.getReturnType();
                    if(
                            paramMatch && returnMatch && isAbstract
                    ){
                        if(interfaces[i].isAnnotationPresent(FunctionalInterface.class)) {
                            filteredMethods.add(method);
                            accessor.isFunctionalInterface = true;
                            accessor.functionalMethodName = method.getName();
                        }
                        if(interfaceMethod.isVarArgs() && !method.isVarArgs()) {
                            varArgsMethods.put(method, interfaceMethod);
                        }
                    }
                }
            }
            // Lambda classes don't have the abstract method in getMethods() —
            // detect @FunctionalInterface directly and register the abstract method
            if(!accessor.isFunctionalInterface && isFuncInterface) {
                for(Method interfaceMethod : interfaceMethods) {
                    if(Modifier.isAbstract(interfaceMethod.getModifiers())) {
                        interfaceMethod.setAccessible(true);
                        accessor.addMethod(interfaceMethod.getName(), interfaceMethod, null);
                        accessor.isFunctionalInterface = true;
                        accessor.functionalMethodName = interfaceMethod.getName();
                        break;
                    }
                }
            }
        }

        for (Method filteredMethod : filteredMethods) {
            Api api = methodApis.get(filteredMethod);
            if(varArgsMethods.containsKey(filteredMethod))
                filteredMethod = varArgsMethods.get(filteredMethod);
            filteredMethod.setAccessible(true);
            accessor.addMethod(filteredMethod.getName(), filteredMethod, api);
        }

        Field[] fields = classType.getFields();

        for (Field field : fields) {
            Api api = field.getAnnotation(Api.class);
            if(api != null && api.export()){
                accessor.addField(field.getName(), field, api);
            }
        }

        return accessor;
    }

    public void bindPrototypeTo(V8Runtime runtime, FastJavetClassConverter converter, V8ValueObject value) throws JavetException {
        for (Map.Entry<String, MethodOverrideMap> entry : methods.entrySet()) {
            String name = entry.getKey();
            MethodOverrideMap overrideMap = entry.getValue();
            bind(runtime, value, converter, name, overrideMap.isVoidReturn);
        }

        for (Map.Entry<String, Field> fieldEntry : fields.entrySet()){
            String name = fieldEntry.getKey();
            Field field = fieldEntry.getValue();
            bindProp(runtime, value, converter, name, Modifier.isFinal(field.getModifiers()));
        }
    }

    protected void bind(
            V8Runtime runtime,
            V8ValueObject value,
            FastJavetClassConverter converter,
            String name,
            boolean voidReturn
    ) throws JavetException {
        JavetCallbackContext context = NativeProxyAccessor.getCallbackContext(
                runtime,
                converter,
                this,
                classType,
                name,
                voidReturn ?
                        NativeProxyAccessor.AccessorType.METHOD_VOID
                        : NativeProxyAccessor.AccessorType.METHOD
                );
        value.bindFunction(context);
    }


    protected void bindProp(
            V8Runtime runtime,
            V8ValueObject value,
            FastJavetClassConverter converter,
            String name,
            boolean isReadOnly
    ) throws JavetException {
        JavetCallbackContext readContext = NativeProxyAccessor.getCallbackContext(
                runtime,
                converter,
                this,
                classType,
                name,
                NativeProxyAccessor.AccessorType.FIELD_READ
        );
        if(isReadOnly){
            value.bindProperty(readContext);
            return;
        }
        JavetCallbackContext writeContext = NativeProxyAccessor.getCallbackContext(
                runtime,
                converter,
                this,
                classType,
                name,
                NativeProxyAccessor.AccessorType.FIELD_WRITE
        );
        value.bindProperty(readContext, writeContext);
    }

    public V8ValueObject createObject(
            Object object,
            IJavetConverter converter,
            V8Runtime v8Runtime
    ) throws JavetException {
        if(isFunctionalInterface){
            return v8Runtime.createV8ValueFunction(new JavetCallbackContext(
                    "apply",
                    JavetCallbackType.DirectCallNoThisAndResult,
                    (IJavetDirectCallable.NoThisAndResult) (V8Value ...values)->{
                        return converter.toV8Value(v8Runtime, invoke(object, functionalMethodName, values));
                    }
            ));
        }
        return v8Runtime.createV8ValueObject();
    }
}
