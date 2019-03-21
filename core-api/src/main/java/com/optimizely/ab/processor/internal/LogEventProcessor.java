/**
 *    Copyright 2019, Optimizely Inc. and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.processor.internal;

import com.optimizely.ab.common.callback.AggregateCallback;
import com.optimizely.ab.common.callback.Callback;
import com.optimizely.ab.common.internal.Assert;
import com.optimizely.ab.common.plugin.Plugin;
import com.optimizely.ab.common.plugin.PluginSupport;
import com.optimizely.ab.event.EventHandler;
import com.optimizely.ab.event.LogEvent;
import com.optimizely.ab.event.internal.EventFactory;
import com.optimizely.ab.event.internal.payload.EventBatch;
import com.optimizely.ab.processor.AbstractProcessor;
import com.optimizely.ab.processor.InterceptingStage.InterceptHandler;
import com.optimizely.ab.processor.ProcessingStage;
import com.optimizely.ab.processor.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Configures the processing flow for dispatching Optimizely events to an {@link EventHandler}.
 *
 * Consists of the following main processing stages:
 *
 * <ol>
 *   <li>Transform</li>
 *   <li>Intercept</li>
 *   <li>Buffer</li>
 *   <li>Batch</li>
 * </ol>
 *
 * @param <T> the type of elements fed into the processor
 */
public class LogEventProcessor<T> implements PluginSupport {
    private static final Logger logger = LoggerFactory.getLogger(LogEventProcessor.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 1024;
    private static final int DEFAULT_MAX_BATCH_SIZE = 50;

    private EventHandler eventHandler;

    /**
     * List of consumers to be invoked (in natural order) during the Transform Stage.
     */
    private List<Consumer<? super T>> transformers = new ArrayList<>();

    /**
     * List of interceptors to be invoked (in natural order) during the Intercept Stage.
     */
    private List<InterceptHandler<EventBatch>> interceptors = new ArrayList<>();

    /**
     * Converts output elements of Transform Stage to input elements of Intercept Stage.
     */
    private Function<? super T, ? extends EventBatch> converter;

    /**
     * Buffering stage that receives elements from (application) threads producing into this processor.
     *
     * This stage can be asynchronous to enable batching of events.
     *
     * By default, no buffer is used in order to be consistent with previous releases. This may change in the future.
     */
    private ProcessingStage<EventBatch, EventBatch> bufferStage = ProcessingStage.identity();

    /**
     * Callbacks to be invoked when an event is finished being dispatched to report success or failure status.
     */
    private AggregateCallback<EventBatch> callbacks = new AggregateCallback<>();

    private Function<EventBatch, LogEvent> eventFactory = (new EventFactory())::createLogEvent; // TODO do not default

    private BiConsumer<LogEvent, Throwable> logEventExceptionHandler = (logEvent, err) -> {
        logger.error("Error dispatching event: {}", logEvent, err);
    };

    public LogEventProcessor<T> plugin(Plugin<LogEventProcessor<T>> plugin) {
        Assert.notNull(plugin, "plugin");
        plugin.configure(this);
        return this;
    }

    public LogEventProcessor<T> transformer(Consumer<T> transformer) {
        this.transformers.add(Assert.notNull(transformer, "transformer"));
        return this;
    }

    public LogEventProcessor<T> interceptor(Predicate<EventBatch> filter) {
        Assert.notNull(filter, "interceptor");
        this.interceptors.add(input -> {
            if (!filter.test(input)) {
                return null;
            }
            return input;
        });
        return this;
    }

    public LogEventProcessor<T> callback(Callback<EventBatch> callback) {
        this.callbacks.add(Assert.notNull(callback, "callback"));
        return this;
    }

    public LogEventProcessor<T> callback(Consumer<EventBatch> success) {
        return callback(Callback.from(success, (c, ex) -> {}));
    }

    public LogEventProcessor<T> eventFactory(Function<EventBatch, LogEvent> eventFactory) {
        this.eventFactory = Assert.notNull(eventFactory, "eventFactory");
        return this;
    }

    public LogEventProcessor<T> eventFactory(EventFactory eventFactory) {
        return eventFactory(Assert.notNull(eventFactory, "eventFactory")::createLogEvent);
    }

    public LogEventProcessor<T> eventHandler(EventHandler eventHandler) {
        this.eventHandler = Assert.notNull(eventHandler, "eventHandler");
        return this;
    }

    /**
     * Configures the wait strategy for ring buffer consumer
     */
    public LogEventProcessor<T> bufferStage(ProcessingStage<EventBatch, EventBatch> bufferStage) {
        this.bufferStage = Assert.notNull(bufferStage, "bufferStage");
        return this;
    }

    /**
     * Configures the conversion stage between transformers and interceptors.
     */
    LogEventProcessor<T> converter(Function<? super T, ? extends EventBatch> converter) {
        this.converter = Assert.notNull(converter, "converter");
        return this;
    }

    public Processor<T> build() {
        return ProcessingStage
            .transformers(transformers)
            .map(converter)
            .andThen(ProcessingStage.interceptors(interceptors))
            .andThen(bufferStage)
            .andThen(new EventBatchMergeStage(eventFactory, () -> callbacks))
            .create(new EventHandlerSink(eventHandler, logEventExceptionHandler));
    }

    static class LegacyEventOperator extends AbstractProcessor<EventBatch, LogEvent> {
        private final Function<EventBatch, LogEvent> eventFactory;
        private final Supplier<Callback<EventBatch>> callbackSupplier;

        public LegacyEventOperator(
            Processor<? super LogEvent> sink,
            Function<EventBatch, LogEvent> eventFactory,
            Supplier<Callback<EventBatch>> callbackSupplier
        ) {
            super(sink);
            this.eventFactory = Assert.notNull(eventFactory, "eventFactory");
            this.callbackSupplier = Assert.notNull(callbackSupplier, "callbackSupplier");
        }

        @Override
        public void process(EventBatch input) {
            if (input == null) {
                return;
            }

            LogEvent output = eventFactory.apply(input);
            if (output == null) {
                return;
            }

            setCallback(output);

            getSink().process(output);
        }

        private void setCallback(LogEvent logEvent) {
            Callback<EventBatch> callback = callbackSupplier.get();
            if (callback != null) {
                logEvent.setCallback(callback);
            }
        }
    }

    /**
     * Adapts an {@link EventHandler} to the {@link Processor} interface.
     */
    static class EventHandlerSink implements Processor<LogEvent> {
        private static final Logger logger = LoggerFactory.getLogger(EventHandlerSink.class);

        private final EventHandler eventHandler;
        private final BiConsumer<LogEvent, Throwable> exceptionHandler;

        EventHandlerSink(
            EventHandler eventHandler,
            @Nullable BiConsumer<LogEvent, Throwable> exceptionHandler
        ) {
            this.eventHandler = Assert.notNull(eventHandler, "eventHandler");
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        public void process(LogEvent logEvent) {
            handle(logEvent);
        }

        @Override
        public void processBatch(Collection<? extends LogEvent> batch) {
            for (final LogEvent logEvent : batch) {
                handle(logEvent);
            }
        }

        private void handle(LogEvent logEvent) {
            try {
                logger.trace("Invoking {}", eventHandler);

                eventHandler.dispatchEvent(logEvent);

                logger.trace("Finished invoking event handler");
            } catch (Exception e) {
                if (exceptionHandler != null) {
                    exceptionHandler.accept(logEvent, e);
                } else {
                    logger.warn("Error while dispatching to {}", eventHandler, e);
                    throw new RuntimeException("Failed to invoke EventHandler", e);
                }
            }
        }
    }
}
