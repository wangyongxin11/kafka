/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.state.internals.ThreadCache;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class AbstractTask {
    protected final TaskId id;
    protected final String applicationId;
    protected final ProcessorTopology topology;
    protected final Consumer consumer;
    protected final ProcessorStateManager stateMgr;
    protected final Set<TopicPartition> partitions;
    protected InternalProcessorContext processorContext;
    protected final ThreadCache cache;
    /**
     * @throws ProcessorStateException if the state manager cannot be created
     */
    protected AbstractTask(TaskId id,
                           String applicationId,
                           Collection<TopicPartition> partitions,
                           ProcessorTopology topology,
                           Consumer<byte[], byte[]> consumer,
                           Consumer<byte[], byte[]> restoreConsumer,
                           boolean isStandby,
                           StateDirectory stateDirectory,
                           final ThreadCache cache) {
        this.id = id;
        this.applicationId = applicationId;
        this.partitions = new HashSet<>(partitions);
        this.topology = topology;
        this.consumer = consumer;
        this.cache = cache;

        // create the processor state manager
        try {
            this.stateMgr = new ProcessorStateManager(applicationId, id, partitions, restoreConsumer, isStandby, stateDirectory, topology.sourceStoreToSourceTopic(), topology.storeToProcessorNodeMap());

        } catch (IOException e) {
            throw new ProcessorStateException("Error while creating the state manager", e);
        }
    }

    protected void initializeStateStores() {
        // set initial offset limits
        initializeOffsetLimits();

        for (StateStore store : this.topology.stateStores()) {
            store.init(this.processorContext, store);
        }
    }

    public final TaskId id() {
        return id;
    }

    public final String applicationId() {
        return applicationId;
    }

    public final Set<TopicPartition> partitions() {
        return this.partitions;
    }

    public final ProcessorTopology topology() {
        return topology;
    }

    public final ProcessorContext context() {
        return processorContext;
    }

    public final ThreadCache cache() {
        return cache;
    }

    public abstract void commit();


    public abstract void close();

    public abstract void commitOffsets();

    /**
     * @throws ProcessorStateException if there is an error while closing the state manager
     */
    void closeStateManager() {
        try {
            stateMgr.close(recordCollectorOffsets());
        } catch (IOException e) {
            throw new ProcessorStateException("Error while closing the state manager", e);
        }
    }

    protected Map<TopicPartition, Long> recordCollectorOffsets() {
        return Collections.emptyMap();
    }

    protected void initializeOffsetLimits() {
        for (TopicPartition partition : partitions) {
            try {
                OffsetAndMetadata metadata = consumer.committed(partition); // TODO: batch API?
                stateMgr.putOffsetLimit(partition, metadata != null ? metadata.offset() : 0L);
            } catch (AuthorizationException e) {
                throw new ProcessorStateException(String.format("AuthorizationException when initializing offsets for %s", partition), e);
            } catch (WakeupException e) {
                throw e;
            } catch (KafkaException e) {
                throw new ProcessorStateException(String.format("Failed to initialize offsets for %s", partition), e);
            }
        }
    }

    public StateStore getStore(final String name) {
        return stateMgr.getStore(name);
    }

    /**
     * Produces a string representation contain useful information about a StreamTask.
     * This is useful in debugging scenarios.
     * @return A string representation of the StreamTask instance.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder("StreamsTask taskId:" + this.id() + "\n");

        // print topology
        if (topology != null) {
            sb.append("\t\t\t" + topology.toString());
        }

        // print assigned partitions
        if (partitions != null && !partitions.isEmpty()) {
            sb.append("\t\t\tPartitions [");
            for (TopicPartition topicPartition : partitions) {
                sb.append(topicPartition.toString() + ",");
            }
            sb.setLength(sb.length() - 1);
            sb.append("]");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * Flush all state stores owned by this task
     */
    public void flushState() {
        stateMgr.flush((InternalProcessorContext) this.context());
    }
}
