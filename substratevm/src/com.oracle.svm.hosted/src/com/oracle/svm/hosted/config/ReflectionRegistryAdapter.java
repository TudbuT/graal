/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.config;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.graalvm.nativeimage.impl.ConfigurationPredicate;
import org.graalvm.nativeimage.impl.ReflectionRegistry;

import com.oracle.svm.core.TypeResult;
import com.oracle.svm.core.configure.ReflectionConfigurationParserDelegate;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.util.ClassUtil;

import jdk.vm.ci.meta.MetaUtil;

public class ReflectionRegistryAdapter implements ReflectionConfigurationParserDelegate<Class<?>> {
    private final ReflectionRegistry registry;
    private final ImageClassLoader classLoader;

    public ReflectionRegistryAdapter(ReflectionRegistry registry, ImageClassLoader classLoader) {
        this.registry = registry;
        this.classLoader = classLoader;
    }

    @Override
    public void registerType(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type);
    }

    @Override
    public ConfigurationPredicate resolvePredicate(Map<String, Object> predicate) {
        return ConfigurationPredicate.DEFAULT_CONFIGRATION_PREDICATE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Class<?> resolveType(String typeName) {
        return resolveTypeResult(typeName).get();
    }

    @Override
    public TypeResult<Class<?>> resolveTypeResult(String typeName) {
        String name = typeName;
        if (name.indexOf('[') != -1) {
            /* accept "int[][]", "java.lang.String[]" */
            name = MetaUtil.internalNameToJava(MetaUtil.toInternalName(name), true, true);
        }
        return classLoader.findClass(name);
    }

    @Override
    public void registerPublicClasses(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getClasses());
    }

    @Override
    public void registerDeclaredClasses(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getDeclaredClasses());
    }

    @Override
    public void registerPublicFields(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, false, type.getFields());
    }

    @Override
    public void registerDeclaredFields(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, false, type.getDeclaredFields());
    }

    @Override
    public void registerPublicMethods(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getMethods());
    }

    @Override
    public void registerDeclaredMethods(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getDeclaredMethods());
    }

    @Override
    public void registerPublicConstructors(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getConstructors());
    }

    @Override
    public void registerDeclaredConstructors(ConfigurationPredicate predicate, Class<?> type) {
        registry.register(predicate, type.getDeclaredConstructors());
    }

    @Override
    public void registerField(ConfigurationPredicate predicate, Class<?> type, String fieldName, boolean allowWrite) throws NoSuchFieldException {
        registry.register(ConfigurationPredicate.DEFAULT_CONFIGRATION_PREDICATE, allowWrite, type.getDeclaredField(fieldName));
    }

    @Override
    public boolean registerAllMethodsWithName(ConfigurationPredicate predicate, Class<?> type, String methodName) {
        boolean found = false;
        Executable[] methods = type.getDeclaredMethods();
        for (Executable method : methods) {
            if (method.getName().equals(methodName)) {
                registry.register(predicate, method);
                found = true;
            }
        }
        return found;
    }

    @Override
    public boolean registerAllConstructors(ConfigurationPredicate predicate, Class<?> clazz) {
        Executable[] methods = clazz.getDeclaredConstructors();
        for (Executable method : methods) {
            registry.register(predicate, method);
        }
        return methods.length > 0;
    }

    @Override
    public void registerMethod(ConfigurationPredicate predicate, Class<?> type, String methodName, List<Class<?>> methodParameterTypes) throws NoSuchMethodException {
        Class<?>[] parameterTypesArray = methodParameterTypes.toArray(new Class<?>[0]);
        Method method;
        try {
            method = type.getDeclaredMethod(methodName, parameterTypesArray);
        } catch (NoClassDefFoundError e) {
            /*
             * getDeclaredMethod() builds a set of all the declared methods, which can fail when a
             * symbolic reference from another method to a type (via parameters, return value)
             * cannot be resolved. getMethod() builds a different set of methods and can still
             * succeed. This case must be handled for predefined classes when, during the run
             * observed by the agent, a referenced class was not loaded and is not available now
             * precisely because the application used getMethod() instead of getDeclaredMethod().
             */
            try {
                method = type.getMethod(methodName, parameterTypesArray);
            } catch (Throwable ignored) {
                throw e;
            }
        }
        registry.register(predicate, method);
    }

    @Override
    public void registerConstructor(ConfigurationPredicate predicate, Class<?> clazz, List<Class<?>> methodParameterTypes) throws NoSuchMethodException {
        Class<?>[] parameterTypesArray = methodParameterTypes.toArray(new Class<?>[0]);
        registry.register(predicate, clazz.getDeclaredConstructor(parameterTypesArray));
    }

    @Override
    public String getTypeName(Class<?> type) {
        return type.getTypeName();
    }

    @Override
    public String getSimpleName(Class<?> type) {
        return ClassUtil.getUnqualifiedName(type);
    }
}
