/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.withModifier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.Route;

import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * A registry of the implementation methods bound to a {@link GrpcService}. Used for method dispatch and
 * documentation generation.
 */
final class HandlerRegistry {

    // e.g. UnaryCall -> unaryCall
    private static final Converter<String, String> methodNameConverter =
            CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_CAMEL);

    private final List<ServerServiceDefinition> services;
    private final Map<String, ServerMethodDefinition<?, ?>> methods;
    private final Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute;
    private final Map<MethodDescriptor<?, ?>, String> simpleMethodNames;
    private final Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators;

    private HandlerRegistry(List<ServerServiceDefinition> services,
                            Map<String, ServerMethodDefinition<?, ?>> methods,
                            Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute,
                            Map<MethodDescriptor<?, ?>, String> simpleMethodNames,
                            Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators) {
        this.services = requireNonNull(services, "services");
        this.methods = requireNonNull(methods, "methods");
        this.methodsByRoute = requireNonNull(methodsByRoute, "methodsByRoute");
        this.simpleMethodNames = requireNonNull(simpleMethodNames, "simpleMethodNames");
        this.decorators = requireNonNull(decorators, "decorators");
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(String methodName) {
        return methods.get(methodName);
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(Route route) {
        return methodsByRoute.get(route);
    }

    String simpleMethodName(MethodDescriptor<?, ?> methodName) {
        return simpleMethodNames.get(methodName);
    }

    List<ServerServiceDefinition> services() {
        return services;
    }

    Map<String, ServerMethodDefinition<?, ?>> methods() {
        return methods;
    }

    Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return methodsByRoute;
    }

    Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators() {
        return decorators;
    }

    static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        Builder addService(ServerServiceDefinition service, @Nullable Class<?> type) {
            entries.add(new Entry(service.getServiceDescriptor().getName(), service, null, type));
            return this;
        }

        Builder addService(String path, ServerServiceDefinition service,
                           @Nullable MethodDescriptor<?, ?> methodDescriptor, @Nullable Class<?> type) {
            entries.add(new Entry(normalizePath(path, methodDescriptor == null),
                                  service, methodDescriptor, type));
            return this;
        }

        private static String normalizePath(String path, boolean isServicePath) {
            if (path.isEmpty()) {
                return path;
            }

            if (path.charAt(0) == '/') {
                path = path.substring(1);
            }
            if (path.isEmpty()) {
                return path;
            }

            if (isServicePath) {
                final int lastCharIndex = path.length() - 1;
                if (path.charAt(lastCharIndex) == '/') {
                    path = path.substring(0, lastCharIndex);
                }
            }

            return path;
        }

        List<Entry> entries() {
            return entries;
        }

        HandlerRegistry build() {
            // Store per-service first, to make sure services are added/replaced atomically.
            final ImmutableMap.Builder<String, ServerServiceDefinition> services = ImmutableMap.builder();
            final ImmutableMap.Builder<String, ServerMethodDefinition<?, ?>> methods = ImmutableMap.builder();
            final ImmutableMap.Builder<Route, ServerMethodDefinition<?, ?>> methodsByRoute =
                    ImmutableMap.builder();
            final ImmutableMap.Builder<MethodDescriptor<?, ?>, String> bareMethodNames = ImmutableMap.builder();
            final ImmutableMap.Builder<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> decorators =
                    ImmutableMap.builder();

            for (Entry entry : entries) {
                final ServerServiceDefinition service = entry.service();
                final String path = entry.path();
                services.put(path, service);
                final MethodDescriptor<?, ?> methodDescriptor = entry.method();
                if (methodDescriptor == null) {
                    final Class<?> type = entry.type();
                    final Map<String, Method> publicMethods = new HashMap<>();
                    if (type != null) {
                        for (Method method : InternalReflectionUtils.getAllSortedMethods(
                                type, withModifier(Modifier.PUBLIC))) {
                            final String methodName = method.getName();
                            if (!publicMethods.containsKey(methodName)) {
                                publicMethods.put(methodName, method);
                            }
                        }
                    }

                    for (ServerMethodDefinition<?, ?> methodDefinition : service.getMethods()) {
                        final MethodDescriptor<?, ?> methodDescriptor0 = methodDefinition.getMethodDescriptor();
                        final String bareMethodName = methodDescriptor0.getBareMethodName();
                        assert bareMethodName != null;
                        final String pathWithMethod = path + '/' + bareMethodName;
                        methods.put(pathWithMethod, methodDefinition);
                        methodsByRoute.put(Route.builder().exact('/' + pathWithMethod).build(),
                                           methodDefinition);
                        bareMethodNames.put(methodDescriptor0, bareMethodName);

                        final String methodName = methodNameConverter.convert(bareMethodName);
                        final Method method = publicMethods.get(methodName);
                        if (method != null) {
                            assert type != null;
                            final List<DecoratorAndOrder> decoratorAndOrders =
                                    DecoratorAnnotationUtil.collectDecorators(type, method);
                            if (!decoratorAndOrders.isEmpty()) {
                                decorators.put(methodDefinition, decoratorAndOrders);
                            }
                        }
                    }
                } else {
                    final ServerMethodDefinition<?, ?> methodDefinition =
                            service.getMethods().stream()
                                   .filter(method0 -> method0.getMethodDescriptor() == methodDescriptor)
                                   .findFirst()
                                   .orElseThrow(() -> new IllegalArgumentException(
                                           "Failed to retrieve " + methodDescriptor + " in " + service));
                    methods.put(path, methodDefinition);
                    methodsByRoute.put(Route.builder().exact('/' + path).build(), methodDefinition);
                    final MethodDescriptor<?, ?> methodDescriptor0 = methodDefinition.getMethodDescriptor();
                    final String bareMethodName = methodDescriptor0.getBareMethodName();
                    assert bareMethodName != null;
                    bareMethodNames.put(methodDescriptor0, bareMethodName);
                    final Class<?> type = entry.type();
                    if (type != null) {
                        final String methodName = methodNameConverter.convert(bareMethodName);
                        final Optional<Method> method =
                                InternalReflectionUtils.getAllSortedMethods(type, withModifier(Modifier.PUBLIC))
                                                       .stream()
                                                       .filter(m -> methodName.equals(m.getName()))
                                                       .findFirst();
                        if (method.isPresent()) {
                            final List<DecoratorAndOrder> decoratorAndOrders =
                                    DecoratorAnnotationUtil.collectDecorators(type, method.get());
                            if (!decoratorAndOrders.isEmpty()) {
                                decorators.put(methodDefinition, decoratorAndOrders);
                            }
                        }
                    }
                }
            }
            final Map<String, ServerServiceDefinition> services0 = services.build();
            return new HandlerRegistry(ImmutableList.copyOf(services0.values()),
                                       methods.build(),
                                       methodsByRoute.build(),
                                       bareMethodNames.buildKeepingLast(),
                                       decorators.build());
        }
    }

    static final class Entry {
        private final String path;
        private final ServerServiceDefinition service;
        @Nullable
        private final MethodDescriptor<?, ?> method;
        @Nullable
        private final Class<?> type;

        Entry(String path, ServerServiceDefinition service,
              @Nullable MethodDescriptor<?, ?> method, @Nullable Class<?> type) {
            this.path = path;
            this.service = service;
            this.method = method;
            this.type = type;
        }

        String path() {
            return path;
        }

        ServerServiceDefinition service() {
            return service;
        }

        @Nullable
        MethodDescriptor<?, ?> method() {
            return method;
        }

        @Nullable
        Class<?> type() {
            return type;
        }
    }
}
