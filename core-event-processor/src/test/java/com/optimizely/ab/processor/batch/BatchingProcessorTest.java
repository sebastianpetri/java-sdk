package com.optimizely.ab.processor.batch;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.optimizely.ab.common.internal.Assert;
import com.optimizely.ab.processor.Processor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.fail;

public class BatchingProcessorTest {
    public static final Logger logger = LoggerFactory.getLogger(BatchingProcessorTest.class);

    private ExecutorService executor;
    private List<Collection<Object>> batches;
    private AtomicInteger batchCount;

    @Before
    public void setUp() throws Exception {
        executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
            .setNameFormat("batch-test-%d")
            .build());
        batchCount = new AtomicInteger();
        batches = Collections.synchronizedList(new ArrayList<>());
    }

    @After
    public void tearDown() throws Exception {
        executor.shutdown();
        executor.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    public void testProcess_maxBatchSize1() throws Exception {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(1)
            .maxBatchOpen(Duration.ofDays(1))); // wont reach timeout

        buffer.process("one");
        assertBatchCount(1);
        buffer.process("two");
        assertBatchCount(2);
        buffer.process("three");
        assertBatchCount(3);
        assertThat(batches.get(0), equalTo(newArrayList("one")));
        assertThat(batches.get(1), equalTo(newArrayList("two")));
        assertThat(batches.get(2), equalTo(newArrayList("three")));
    }

    @Test
    public void testProcess_maxBatchSize2() throws Exception {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(2)
            .maxBatchOpen(Duration.ofDays(1))); // wont reach timeout

        buffer.process("one");
        buffer.process("two");
        assertBatchCount(1);
        buffer.process("three");
        buffer.process("four");
        assertBatchCount(2);
        assertThat(batches.get(0), equalTo(newArrayList("one", "two")));
        assertThat(batches.get(1), equalTo(newArrayList("three", "four")));
    }

    @Test
    public void testProcess_timeout() throws Exception {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(10)
            .maxBatchOpen(Duration.ofMillis(500)));

        buffer.process(0);
        assertBatchCount(1);
        assertThat(batches.get(0), equalTo(newArrayList(0)));

        for (int i = 1; i <= 10; i++) {
            buffer.process(i);
        }
        assertBatchCount(2);
        assertThat(batches.get(1), equalTo(newArrayList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
    }

    @Test
    public void testProcessBatch_maxBatchSize1() {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(1)
            .maxBatchOpen(Duration.ofDays(1))); // wont reach timeout

        buffer.processBatch(Lists.newArrayList("one", "two", "three"));
        assertBatchCount(3);
        assertThat(batches.get(0), equalTo(newArrayList("one")));
        assertThat(batches.get(1), equalTo(newArrayList("two")));
        assertThat(batches.get(2), equalTo(newArrayList("three")));
    }

    @Test
    public void testProcessBatch_maxBatchSize2() {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(2)
            .maxBatchOpen(Duration.ofDays(1))); // wont reach timeout

        buffer.processBatch(Lists.newArrayList("one", "two", "three", "four"));
        assertBatchCount(2);
        assertThat(batches.get(0), equalTo(newArrayList("one", "two")));
        assertThat(batches.get(1), equalTo(newArrayList("three", "four")));
    }

    @Test
    public void testProcessBatch_timeout() {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(2)
            .maxBatchOpen(Duration.ofMillis(500)));

        buffer.processBatch(Lists.newArrayList("one", "two", "three"));
        assertBatchCount(2);
        assertThat(batches.get(0), equalTo(newArrayList("one", "two")));
        assertThat(batches.get(1), equalTo(newArrayList("three")));
    }

    @Test
    public void testFlushingOpenBatch() throws Exception {
        // flush before bounds are reached
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchSize(100)
            .maxBatchOpen(Duration.ofHours(1)));

        for (int i = 0; i < 10; i++) {
            buffer.process(i);
        }

        buffer.flush();

        assertThat(batchCount.get(), equalTo(1));
        assertThat(batches.get(0).size(), equalTo(10));

        // idempotent
        buffer.flush();
        assertThat(batchCount.get(), equalTo(1));
    }

    @Test
    public void testDeadlineZero() throws Exception {
        BatchingProcessor<Object> buffer = batchingQueue(config -> config
            .maxBatchOpen(Duration.ZERO));

        buffer.process("one");
        assertBatchCount(0, 500, MILLISECONDS);
        buffer.process("two");
        assertBatchCount(0, 500, MILLISECONDS);
        buffer.process("three");
        assertBatchCount(0, 500, MILLISECONDS);
        buffer.flush();
        assertBatchCount(1);
        assertThat(batches.get(0), equalTo(newArrayList("one", "two", "three")));
    }

    @Test
    public void testMaxInflight1() {
        testMaxInflight(1, 1);
    }

    @Test
    public void testMaxInflight3() {
        testMaxInflight(3, 10);
    }

    private void testMaxInflight(int maxInflight, int maxBatchSize) {
        int numBatches = 3;
        ExecutorService producerExecutor = Executors.newFixedThreadPool(numBatches, new ThreadFactoryBuilder()
            .setNameFormat("test-producer-%d")
            .build());
        try {
            InstrumentedExecutorService executor = new InstrumentedExecutorService(this.executor);

            BatchingProcessor<Object> buffer = batchingQueue(config -> config
                    .maxBatchSize(maxBatchSize)
                    .maxBatchOpen(Duration.ofDays(1)) // wont reach timeout
                    .maxInflightBatches(maxInflight)
                    .executor(executor),
                delayConsumer(Duration.ofMillis(250)));

            // produce batches in parallel
            CompletableFuture[] futures = IntStream.range(0, numBatches)
                .mapToObj(n ->
                    CompletableFuture.runAsync(() -> {
                        for (int i = 0; i < maxBatchSize; i++) {
                            String element = String.format("%s-%s", n, i);
                            buffer.process(element);
                            logger.info("processed {}", element);
                            try {
                                Thread.sleep(10L);
                            } catch (InterruptedException e) {
                                // ignore
                            }
                        }
                    }, producerExecutor))
                .toArray(CompletableFuture[]::new);

            try {
                logger.info("Waiting for producers to stop");
                CompletableFuture.allOf(futures).get();
                logger.info("Producers done");
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }

            assertBatchCount(numBatches, 5, SECONDS);

            assertThat(executor.getExecuteCount().intValue(), equalTo(3));
            assertThat(executor.getExecutingCount().intValue(), equalTo(0));
            assertThat(executor.getExecutingMax().intValue(), equalTo(maxInflight));
        } finally {
            producerExecutor.shutdownNow();
        }

    }

    private Consumer<Collection<Object>> delayConsumer(Duration delay) {
        return c -> {
            logger.info("Sleeping for {}", delay);
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            recordBatch(c);
        };
    }

    private BatchingProcessorConfig.Builder batchingQueueConfig() {
        return BatchingProcessorConfig.builder().executor(executor);
    }

    private BatchingProcessor<Object> batchingQueue(Consumer<BatchingProcessorConfig.Builder> configure) {
        return batchingQueue(configure, this::recordBatch);
    }

    private BatchingProcessor<Object> batchingQueue(
        Consumer<BatchingProcessorConfig.Builder> configure,
        Consumer<Collection<Object>> consumer
    ) {
        BatchingProcessorConfig.Builder config = batchingQueueConfig();

        // per-test config
        configure.accept(config);

        return new BatchingProcessor<>(config.build(), new Processor<Object>() {
            @Override
            public void process(@Nonnull Object element) {
                fail("Not expecting process to be invoked");
            }

            @Override
            public void processBatch(@Nonnull Collection<?> elements) {
                consumer.accept((Collection<Object>) elements);
            }
        });
    }

    // receives batching output
    private void recordBatch(Collection<Object> batch) {
        int n = batchCount.incrementAndGet();
        batches.add(batch);
        logger.info("Received batch #{}: {}", n, batch);
    }

    private void assertBatchCount(int n) {
        assertBatchCount(n, 1, SECONDS);
    }

    private void assertBatchCount(int n, int timeout, TimeUnit unit) {
        Integer actual = await().atMost(timeout, unit)
            .untilAtomic(batchCount, greaterThanOrEqualTo(n));

        assertThat(actual, equalTo(n));
    }

    private static class InstrumentedExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final LongAdder executeCount;
        private final LongAdder executingCount;
        private final LongAccumulator executingMax;

        InstrumentedExecutorService(ExecutorService delegate) {
            this.delegate = Assert.notNull(delegate, "delegate");
            this.executeCount = new LongAdder();
            this.executingCount = new LongAdder();
            this.executingMax = new LongAccumulator(Long::max, Long.MIN_VALUE);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(final Runnable command) {
            executeCount.increment();
            delegate.execute(() -> {
                executingCount.increment();
                executingMax.accumulate(executingCount.longValue());
                logger.info("executingCount={}, executingMax={}", executingCount, executingMax);
                try {
                    command.run();
                } finally {
                    executingCount.decrement();
                }
            });
        }

        public LongAdder getExecuteCount() {
            return executeCount;
        }

        public Long getExecutingCount() {
            return executingCount.longValue();
        }

        public Long getExecutingMax() {
            return executingMax.longValue();
        }
    }
}