/*
 * Copyright 2015, The OpenNMS Group
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opennms.newts.graphite;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.opennms.newts.api.Timestamp.fromEpochSeconds;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.opennms.newts.api.MetricType;
import org.opennms.newts.api.Resource;
import org.opennms.newts.api.Sample;
import org.opennms.newts.api.SampleRepository;
import org.opennms.newts.api.Timestamp;
import org.opennms.newts.api.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public class GraphiteHandler extends SimpleChannelInboundHandler<String> {

    private static final Logger LOG = LoggerFactory.getLogger(GraphiteHandler.class);

    private static final int DEFAULT_LINES_BUFFER = 50;

    private final ThreadPoolExecutor m_executor;
    private final SampleRepository m_repository;
    private final GraphiteInitializer m_parent;

    private List<String> m_lines;
    private AtomicInteger m_enQueued = new AtomicInteger(0);
    private Set<String> m_seenRoots = Sets.newHashSet();

    public GraphiteHandler(SampleRepository repository, GraphiteInitializer parent) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
        int concurrency = Runtime.getRuntime().availableProcessors();
        m_executor = new ThreadPoolExecutor(concurrency, concurrency, 0L, MILLISECONDS, queue);
        m_executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        m_repository = repository;
        m_parent = parent;
        m_lines = Lists.newArrayList();
        LOG.debug("Using storage concurrency of {}", concurrency);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        enqueue(msg);
    }

    private void enqueue(String msg) {
        m_lines.add(msg);

        if (m_enQueued.incrementAndGet() >= DEFAULT_LINES_BUFFER) {
            final List<String> batch = m_lines;
            m_lines = Lists.newArrayList();
            m_enQueued.set(0);
            m_executor.execute(new Runnable() {

                @Override
                public void run() {
                    List<Sample> samples = Lists.newArrayList();
                    Set<String> roots = Sets.newHashSet();
                    for (String line : batch) {
                        try {
                            Sample sample = parseSample(line);
                            roots.add(sample.getResource().getAttributes().get().get(index(0)));
                            samples.add(sample);
                        }
                        catch (Exception e) {
                            m_parent.protocolErrorsInc();
                        }
                    }

                    roots.removeAll(m_seenRoots);

                    // Generate bogus samples to seed search index (see: http://issues.opennms.org/browse/NEWTS-57)
                    Map<String, String> rootAttrs = Maps.newHashMap();
                    rootAttrs.put("_tree", index(0));
                    Timestamp now = Timestamp.now();
                    for (String root : roots) {
                        samples.add(sample(now, new Resource(root, Optional.of(rootAttrs)), "bogus", Double.NaN));
                    }

                    try {
                        m_repository.insert(samples);
                        m_seenRoots.addAll(roots);
                    }
                    catch (Exception e) {
                        LOG.warn("Unable to commit batch of {} samples ({})", samples.size(), e.getMessage());
                        m_parent.storageErrorsInc();
                    }
                }
            });
        }
    }

    private static final Splitter s_lineTokenizer = Splitter.on(CharMatcher.WHITESPACE).limit(3).trimResults();
    private static final Splitter s_pathTokenizer = Splitter.on('.').trimResults();
    private static final Joiner s_pathJoiner = Joiner.on(':');

    static Resource parseResource(String[] path) {
        Map<String, String> attributes = Maps.newHashMap();
        for (int i = 0; i < path.length; i++) {
            attributes.put(index(i), path[i]);
        }
        return new Resource(s_pathJoiner.join(path), Optional.of(attributes));
    }

    static Sample parseSample(String line) {
        List<String> parts = s_lineTokenizer.splitToList(line);
        String[] path = s_pathTokenizer.splitToList(parts.get(0)).toArray(new String[] {});
        Resource resource = parseResource(path.length > 1 ? Arrays.copyOf(path, path.length - 1) : path);
        String name = path.length > 1 ? path[path.length - 1] : "value";
        Double value = Double.parseDouble(parts.get(1));
        Long stamp = Long.parseLong(parts.get(2));

        return sample(fromEpochSeconds(stamp), resource, name, value);
    }

    private static Sample sample(Timestamp timestamp, Resource resource, String name, Double value) {
        return new Sample(timestamp, resource, name, MetricType.GAUGE, ValueType.compose(value, MetricType.GAUGE));
    }

    private static String index(int index) {
        return String.format("_%d", index);
    }

}
