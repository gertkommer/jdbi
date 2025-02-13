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
package org.jdbi.v3.core;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.extension.ExtensionContext;
import org.jdbi.v3.core.extension.ExtensionMethod;
import org.jdbi.v3.core.extension.HandleSupplier;
import org.jdbi.v3.core.internal.MemoizingSupplier;
import org.jdbi.v3.core.internal.OnDemandHandleSupplier;

class LazyHandleSupplier implements HandleSupplier, AutoCloseable, OnDemandHandleSupplier {

    private final Jdbi db;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Deque<ExtensionContext> extensionContexts = new LinkedList<>();
    private final MemoizingSupplier<Handle> handleHolder = MemoizingSupplier.of(this::createHandle);

    LazyHandleSupplier(Jdbi db) {
        this.db = db;
    }

    @Override
    public ConfigRegistry getConfig() {
        ExtensionContext extensionContext = extensionContexts.peek();
        if (extensionContext != null) {
            return extensionContext.getConfig();
        }

        return db.getConfig();
    }

    @Override
    public Jdbi getJdbi() {
        return db;
    }

    @Override
    public Handle getHandle() {
        return handleHolder.get();
    }

    private Handle createHandle() {
        if (closed.get()) {
            throw new IllegalStateException("Handle is closed");
        }
        return db.open().acceptExtensionContext(extensionContexts.peek());
    }

    @Override
    public <V> V invokeInContext(ExtensionMethod extensionMethod, ConfigRegistry config, Callable<V> task) throws Exception {
        return invokeInContext(new ExtensionContext(config, extensionMethod), task);
    }

    @Override
    public <V> V invokeInContext(ExtensionContext extensionContext, Callable<V> task) throws Exception {
        try {
            pushExtensionContext(extensionContext);
            return task.call();
        } finally {
            popExtensionContext();
        }
    }

    private void pushExtensionContext(ExtensionContext extensionContext) {
        extensionContexts.addFirst(extensionContext);
        handleHolder.ifInitialized(h -> h.acceptExtensionContext(extensionContext));
    }

    private void popExtensionContext() {
        // pop from the stack, then set the new top-of-stack in the handle
        extensionContexts.pollFirst();
        handleHolder.ifInitialized(h -> h.acceptExtensionContext(extensionContexts.peek()));
    }

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            throw new IllegalStateException("Handle is closed");
        }
        handleHolder.ifInitialized(Handle::close);
        extensionContexts.clear();
    }
}
