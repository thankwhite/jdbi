/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jdbi.v3.sqlobject;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.fasterxml.classmate.MemberResolver;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedMethod;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.Factory;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.jdbi.v3.Handle;
import org.jdbi.v3.sqlobject.exceptions.UnableToCreateSqlObjectException;
import org.jdbi.v3.sqlobject.mixins.CloseMe;

class SqlObject
{
    private static final TypeResolver                                  typeResolver  = new TypeResolver();
    private static final Map<Method, Handler>                          mixinHandlers = new HashMap<>();
    private static final ConcurrentMap<Class<?>, Map<Method, Handler>> handlersCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Factory>              factories     = new ConcurrentHashMap<>();

    static {
        mixinHandlers.putAll(TransactionalHelper.handlers());
        mixinHandlers.putAll(GetHandleHelper.handlers());
    }

    @SuppressWarnings("unchecked")
    static <T> T buildSqlObject(final Class<T> sqlObjectType, Supplier<Handle> handle)
    {
        Factory f = factories.computeIfAbsent(sqlObjectType, type -> {
            Enhancer e = new Enhancer();
            e.setClassLoader(sqlObjectType.getClassLoader());

            List<Class<?>> interfaces = new ArrayList<>();
            interfaces.add(CloseMe.class);
            if (sqlObjectType.isInterface()) {
                interfaces.add(sqlObjectType);
            }
            else {
                e.setSuperclass(sqlObjectType);
            }
            e.setInterfaces(interfaces.toArray(new Class[interfaces.size()]));
            final SqlObject so = new SqlObject(buildHandlersFor(sqlObjectType), handle);
            e.setCallback((MethodInterceptor) so::invoke);

            return (Factory) e.create();
        });

        // TODO 3: this is duplicated from the above setCallbacks, can we clean that up?
        final SqlObject so = new SqlObject(buildHandlersFor(sqlObjectType), handle);
        return (T) f.newInstance((MethodInterceptor) so::invoke);
    }

    private static Map<Method, Handler> buildHandlersFor(Class<?> sqlObjectType)
    {
        if (handlersCache.containsKey(sqlObjectType)) {
            return handlersCache.get(sqlObjectType);
        }

        final MemberResolver mr = new MemberResolver(typeResolver);
        final ResolvedType sql_object_type = typeResolver.resolve(sqlObjectType);

        final ResolvedTypeWithMembers d = mr.resolve(sql_object_type, null, null);

        final Map<Method, Handler> handlers = new HashMap<>();
        for (final ResolvedMethod method : d.getMemberMethods()) {
            final Method raw_method = method.getRawMember();

            if (raw_method.isAnnotationPresent(SqlQuery.class)) {
                handlers.put(raw_method, new QueryHandler(sqlObjectType, method, ResultReturnThing.forType(method)));
            }
            else if (raw_method.isAnnotationPresent(SqlUpdate.class)) {
                handlers.put(raw_method, new UpdateHandler(sqlObjectType, method));
            }
            else if (raw_method.isAnnotationPresent(SqlBatch.class)) {
                handlers.put(raw_method, new BatchHandler(sqlObjectType, method));
            }
            else if (raw_method.isAnnotationPresent(SqlCall.class)) {
                handlers.put(raw_method, new CallHandler(sqlObjectType, method));
            }
            else if(raw_method.isAnnotationPresent(CreateSqlObject.class)) {
                handlers.put(raw_method, new CreateSqlObjectHandler(raw_method.getReturnType()));
            }
            else if (raw_method.isAnnotationPresent(Transaction.class)) {
                handlers.put(raw_method, new PassThroughTransactionHandler(raw_method, raw_method.getAnnotation(Transaction.class)));
            }
            else if (mixinHandlers.containsKey(raw_method)) {
                handlers.put(raw_method, mixinHandlers.get(raw_method));
            }
            else {
                handlers.put(raw_method, new PassThroughHandler(raw_method));
            }
        }

        attachClose(handlers, AutoCloseable.class, Closeable.class, CloseMe.class);
        handlers.putAll(EqualsHandler.handler());
        handlers.putAll(ToStringHandler.handler(sqlObjectType.getName()));
        handlers.putAll(HashCodeHandler.handler());

        handlersCache.put(sqlObjectType, handlers);

        return handlers;
    }

    @SafeVarargs
    private static void attachClose(Map<Method, Handler> handlers, Class<? extends AutoCloseable>... classes)
    {
        for (Class<?> klass : classes) {
            Method closeMethod;
            try {
                closeMethod = klass.getMethod("close");
            } catch (NoSuchMethodException | SecurityException e) {
                throw new UnableToCreateSqlObjectException("Could not find AutoCloseable#close");
            }
            handlers.put(closeMethod, new CloseHandler());
        }
    }


    private final Map<Method, Handler> handlers;
    private final Supplier<Handle> handle;

    SqlObject(Map<Method, Handler> handlers, Supplier<Handle> handle)
    {
        this.handlers = handlers;
        this.handle = handle;
    }

    Object invoke(Object proxy, Method method, Object[] args, MethodProxy mp) throws Throwable
    {
        final Handler handler = handlers.get(method);

        // If there is no handler, pretend we are just an Object and don't open a connection (Issue #82)
        if (handler == null) {
            return mp.invokeSuper(proxy, args);
        }

        return handler.invoke(handle, proxy, args, mp);
    }

    static String getSql(SqlCall q, Method m)
    {
        if (SqlQuery.DEFAULT_VALUE.equals(q.value())) {
            return m.getName();
        }
        else {
            return q.value();
        }
    }

    static String getSql(SqlQuery q, Method m)
    {
        if (SqlQuery.DEFAULT_VALUE.equals(q.value())) {
            return m.getName();
        }
        else {
            return q.value();
        }
    }

    static String getSql(SqlUpdate q, Method m)
    {
        if (SqlQuery.DEFAULT_VALUE.equals(q.value())) {
            return m.getName();
        }
        else {
            return q.value();
        }
    }

    static String getSql(SqlBatch q, Method m)
    {
        if (SqlQuery.DEFAULT_VALUE.equals(q.value())) {
            return m.getName();
        }
        else {
            return q.value();
        }
    }
}
