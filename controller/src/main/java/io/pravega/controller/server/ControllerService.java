/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.controller.server;

import com.google.common.base.Preconditions;
import io.pravega.client.stream.StreamConfiguration;
import io.pravega.client.stream.impl.ModelHelper;
import io.pravega.common.Exceptions;
import io.pravega.common.Timer;
import io.pravega.common.cluster.Cluster;
import io.pravega.common.cluster.ClusterException;
import io.pravega.common.concurrent.Futures;
import io.pravega.controller.metrics.StreamMetrics;
import io.pravega.controller.metrics.TransactionMetrics;
import io.pravega.controller.store.stream.OperationContext;
import io.pravega.controller.store.stream.ScaleMetadata;
import io.pravega.controller.store.stream.StreamMetadataStore;
import io.pravega.controller.store.stream.VersionedTransactionData;
import io.pravega.controller.store.stream.records.StreamSegmentRecord;
import io.pravega.controller.stream.api.grpc.v1.Controller;
import io.pravega.controller.stream.api.grpc.v1.Controller.CreateScopeStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.CreateStreamStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.DeleteScopeStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.DeleteStreamStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.NodeUri;
import io.pravega.controller.stream.api.grpc.v1.Controller.PingTxnStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.ScaleResponse;
import io.pravega.controller.stream.api.grpc.v1.Controller.ScaleStatusResponse;
import io.pravega.controller.stream.api.grpc.v1.Controller.SegmentId;
import io.pravega.controller.stream.api.grpc.v1.Controller.SegmentRange;
import io.pravega.controller.stream.api.grpc.v1.Controller.TxnId;
import io.pravega.controller.stream.api.grpc.v1.Controller.TxnState;
import io.pravega.controller.stream.api.grpc.v1.Controller.TxnStatus;
import io.pravega.controller.stream.api.grpc.v1.Controller.UpdateStreamStatus;
import io.pravega.controller.task.Stream.StreamMetadataTasks;
import io.pravega.controller.task.Stream.StreamTransactionMetadataTasks;
import io.pravega.shared.NameUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Stream controller RPC server implementation.
 */
@Getter
@AllArgsConstructor
@Slf4j
public class ControllerService {

    private final StreamMetadataStore streamStore;
    private final StreamMetadataTasks streamMetadataTasks;
    private final StreamTransactionMetadataTasks streamTransactionMetadataTasks;
    private final SegmentHelper segmentHelper;
    private final Executor executor;
    private final Cluster cluster;
    private final StreamMetrics streamMetrics;
    private final TransactionMetrics transactionMetrics;

    public ControllerService(StreamMetadataStore streamStore, StreamMetadataTasks streamMetadataTasks,
                             StreamTransactionMetadataTasks streamTransactionMetadataTasks, SegmentHelper segmentHelper,
                             Executor executor, Cluster cluster) {
        this.streamStore = streamStore;
        this.streamMetadataTasks = streamMetadataTasks;
        this.streamTransactionMetadataTasks = streamTransactionMetadataTasks;
        this.segmentHelper = segmentHelper;
        this.executor = executor;
        this.cluster = cluster;
        this.streamMetrics = new StreamMetrics();
        this.transactionMetrics = new TransactionMetrics();
    }

    public CompletableFuture<List<NodeUri>> getControllerServerList() {
        if (cluster == null) {
            return Futures.failedFuture(new IllegalStateException("Controller cluster not initialized"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return cluster.getClusterMembers().stream()
                        .map(host -> NodeUri.newBuilder().setEndpoint(host.getIpAddr()).setPort(host.getPort()).build())
                        .collect(Collectors.toList());
            } catch (ClusterException e) {
                // cluster implementation throws checked exceptions which cannot be thrown inside completable futures.
                throw Exceptions.sneakyThrow(e);
            }
        }, executor);
    }

    public CompletableFuture<CreateStreamStatus> createStream(String scope, String stream, final StreamConfiguration streamConfig,
            final long createTimestamp) {
        Preconditions.checkNotNull(streamConfig, "streamConfig");
        Preconditions.checkArgument(createTimestamp >= 0);
        Timer timer = new Timer();
        try {
            NameUtils.validateStreamName(stream);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Create stream failed due to invalid stream name {}", stream);
            return CompletableFuture.completedFuture(
                    CreateStreamStatus.newBuilder().setStatus(CreateStreamStatus.Status.INVALID_STREAM_NAME).build());
        }

        return streamMetadataTasks.createStream(scope,
                                                stream,
                                                streamConfig,
                                                createTimestamp)
                  .thenApplyAsync(status -> {
                       reportCreateStreamMetrics(scope, stream, streamConfig.getScalingPolicy().getMinNumSegments(), status,
                                timer.getElapsed());
                       return CreateStreamStatus.newBuilder().setStatus(status).build();
                  }, executor);
    }

    public CompletableFuture<UpdateStreamStatus> updateStream(String scope, String stream, final StreamConfiguration streamConfig) {
        Preconditions.checkNotNull(streamConfig, "streamConfig");
        Timer timer = new Timer();
        return streamMetadataTasks.updateStream(scope, stream, streamConfig, null)
                  .thenApplyAsync(status -> {
                      reportUpdateStreamMetrics(scope, stream, status, timer.getElapsed());
                      return UpdateStreamStatus.newBuilder().setStatus(status).build();
                  }, executor);
    }

    public CompletableFuture<UpdateStreamStatus> truncateStream(final String scope, final String stream,
                                                                final Map<Long, Long> streamCut) {
        Preconditions.checkNotNull(scope, "scope");
        Preconditions.checkNotNull(stream, "stream");
        Preconditions.checkNotNull(streamCut, "streamCut");
        Timer timer = new Timer();
        return streamMetadataTasks.truncateStream(scope, stream, streamCut, null)
                .thenApplyAsync(status -> {
                    reportTruncateStreamMetrics(scope, stream, status, timer.getElapsed());
                    return UpdateStreamStatus.newBuilder().setStatus(status).build();
                }, executor);
    }

    public CompletableFuture<StreamConfiguration> getStream(final String scopeName, final String streamName) {
        return streamStore.getConfiguration(scopeName, streamName, null, executor);
    }

    public CompletableFuture<UpdateStreamStatus> sealStream(final String scope, final String stream) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Timer timer = new Timer();
        return streamMetadataTasks.sealStream(scope, stream, null)
                .thenApplyAsync(status -> {
                    reportSealStreamMetrics(scope, stream, status, timer.getElapsed());
                    return UpdateStreamStatus.newBuilder().setStatus(status).build();
                }, executor);
    }

    public CompletableFuture<DeleteStreamStatus> deleteStream(final String scope, final String stream) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Timer timer = new Timer();
        return streamMetadataTasks.deleteStream(scope, stream, null)
                .thenApplyAsync(status -> {
                    reportDeleteStreamMetrics(scope, stream, status, timer.getElapsed());
                    return DeleteStreamStatus.newBuilder().setStatus(status).build();
                }, executor);
    }

    public CompletableFuture<List<SegmentRange>> getCurrentSegments(final String scope, final String stream) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");

        // Fetch active segments from segment store.
        return streamStore.getActiveSegments(scope, stream, null, executor)
                .thenApplyAsync(activeSegments -> getSegmentRanges(activeSegments, scope, stream), executor);
    }

    public CompletableFuture<Map<SegmentId, Long>> getSegmentsAtHead(final String scope, final String stream) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");

        // First fetch segments active at specified timestamp from the specified stream.
        // Divide current segments in segmentFutures into at most count positions.
        return streamStore.getSegmentsAtHead(scope, stream, null, executor).thenApply(segments -> {
            return segments.entrySet().stream()
                           .collect(Collectors.toMap(entry -> ModelHelper.createSegmentId(scope, stream, entry.getKey().segmentId()),
                                   Map.Entry::getValue));
        });
    }

    public CompletableFuture<Map<SegmentRange, List<Long>>> getSegmentsImmediatelyFollowing(SegmentId segment) {
        Preconditions.checkNotNull(segment, "segment");
        OperationContext context = streamStore.createContext(segment.getStreamInfo().getScope(), segment
                .getStreamInfo().getStream());
        return streamStore.getSuccessors(segment.getStreamInfo().getScope(),
                segment.getStreamInfo().getStream(),
                segment.getSegmentId(),
                context,
                executor)
                .thenApply(successors -> successors.entrySet().stream()
                        .collect(Collectors.toMap(
                                entry -> ModelHelper.createSegmentRange(segment.getStreamInfo().getScope(),
                                                segment.getStreamInfo().getStream(), entry.getKey().segmentId(),
                                                entry.getKey().getKeyStart(),
                                                entry.getKey().getKeyEnd()),
                                Map.Entry::getValue)));
    }

    public CompletableFuture<List<StreamSegmentRecord>> getSegmentsBetweenStreamCuts(Controller.StreamCutRange range) {
        Preconditions.checkNotNull(range, "segment");
        Preconditions.checkArgument(!(range.getFromMap().isEmpty() && range.getToMap().isEmpty()));

        String scope = range.getStreamInfo().getScope();
        String stream = range.getStreamInfo().getStream();
        OperationContext context = streamStore.createContext(scope, stream);
        return streamStore.getSegmentsBetweenStreamCuts(scope,
                stream,
                range.getFromMap(),
                range.getToMap(),
                context,
                executor);
    }

    public CompletableFuture<ScaleResponse> scale(final String scope,
                                                  final String stream,
                                                  final List<Long> segmentsToSeal,
                                                  final Map<Double, Double> newKeyRanges,
                                                  final long scaleTimestamp) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(segmentsToSeal, "sealedSegments");
        Preconditions.checkNotNull(newKeyRanges, "newKeyRanges");

        return streamMetadataTasks.manualScale(scope,
                                         stream,
                                         segmentsToSeal,
                                         new ArrayList<>(ModelHelper.encode(newKeyRanges)),
                                         scaleTimestamp,
                                         null);
    }

    public CompletableFuture<ScaleStatusResponse> checkScale(final String scope, final String stream, final int epoch) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Exceptions.checkArgument(epoch >= 0, "epoch", "Epoch cannot be less than 0");

        return streamMetadataTasks.checkScale(scope, stream, epoch, null);
    }

    public CompletableFuture<List<ScaleMetadata>> getScaleRecords(final String scope, final String stream, final long from, final long to) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        return streamStore.getScaleMetadata(scope, stream, from, to, null, executor);
    }

    public CompletableFuture<NodeUri> getURI(final SegmentId segment) {
        Preconditions.checkNotNull(segment, "segment");

        return CompletableFuture.completedFuture(
                segmentHelper.getSegmentUri(segment.getStreamInfo().getScope(), segment.getStreamInfo().getStream(),
                        segment.getSegmentId())
        );
    }

    private SegmentRange convert(final String scope,
                                 final String stream,
                                 final StreamSegmentRecord segment) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(segment, "segment");
        return ModelHelper.createSegmentRange(
                scope, stream, segment.segmentId(), segment.getKeyStart(), segment.getKeyEnd());
    }

    public CompletableFuture<Boolean> isSegmentValid(final String scope,
                                                     final String stream,
                                                     final long segmentId) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        return streamStore.getActiveSegments(scope, stream, null, executor)
                .thenApplyAsync(x -> x.stream().anyMatch(z -> z.segmentId() == segmentId), executor);
    }

    public CompletableFuture<Boolean> isStreamCutValid(final String scope,
                                                     final String stream,
                                                     final Map<Long, Long> streamCut) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        return streamStore.isStreamCutValid(scope, stream, streamCut, null, executor);
    }

    @SuppressWarnings("ReturnCount")
    public CompletableFuture<Pair<UUID, List<SegmentRange>>> createTransaction(final String scope, final String stream,
                                                                               final long lease) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Timer timer = new Timer();
        return streamTransactionMetadataTasks.createTxn(scope, stream, lease, null)
                .thenApply(pair -> {
                    VersionedTransactionData data = pair.getKey();
                    List<StreamSegmentRecord> segments = pair.getValue();
                    return new ImmutablePair<>(data.getId(), getSegmentRanges(segments, scope, stream));
                }).handle((result, ex) -> {
                    if (ex != null) {
                        transactionMetrics.createTransactionFailed(scope, stream);
                        throw new CompletionException(ex);
                    }
                    transactionMetrics.createTransaction(scope, stream, timer.getElapsed());
                    return result;
                });
    }

    private List<SegmentRange> getSegmentRanges(List<StreamSegmentRecord> activeSegments, String scope, String stream) {
        List<SegmentRange> listOfSegment = activeSegments
                .stream()
                .map(segment -> convert(scope, stream, segment))
                .collect(Collectors.toList());
        listOfSegment.sort(Comparator.comparingDouble(SegmentRange::getMinKey));
        return listOfSegment;
    }

    public CompletableFuture<TxnStatus> commitTransaction(final String scope, final String stream, final TxnId
            txnId) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(txnId, "txnId");
        Timer timer = new Timer();
        UUID txId = ModelHelper.encode(txnId);
        return streamTransactionMetadataTasks.commitTxn(scope, stream, txId, null)
                .handle((ok, ex) -> {
                    if (ex != null) {
                        log.warn("Transaction commit failed", ex);
                        // TODO: return appropriate failures to user.
                        transactionMetrics.commitTransactionFailed(scope, stream, txId.toString());
                        return TxnStatus.newBuilder().setStatus(TxnStatus.Status.FAILURE).build();
                    } else {
                        transactionMetrics.commitTransaction(scope, stream, timer.getElapsed());
                        return TxnStatus.newBuilder().setStatus(TxnStatus.Status.SUCCESS).build();
                    }
                });
    }

    public CompletableFuture<TxnStatus> abortTransaction(final String scope, final String stream, final TxnId txnId) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(txnId, "txnId");
        Timer timer = new Timer();
        UUID txId = ModelHelper.encode(txnId);
        return streamTransactionMetadataTasks.abortTxn(scope, stream, txId, null, null)
                .handle((ok, ex) -> {
                    if (ex != null) {
                        log.warn("Transaction abort failed", ex);
                        // TODO: return appropriate failures to user.
                        transactionMetrics.abortTransactionFailed(scope, stream, txId.toString());
                        return TxnStatus.newBuilder().setStatus(TxnStatus.Status.FAILURE).build();
                    } else {
                        transactionMetrics.abortTransaction(scope, stream, timer.getElapsed());
                        return TxnStatus.newBuilder().setStatus(TxnStatus.Status.SUCCESS).build();
                    }
                });
    }

    public CompletableFuture<PingTxnStatus> pingTransaction(final String scope,
                                                            final String stream,
                                                            final TxnId txnId,
                                                            final long lease) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(txnId, "txnId");
        UUID txId = ModelHelper.encode(txnId);

        return streamTransactionMetadataTasks.pingTxn(scope, stream, txId, lease, null);
    }

    public CompletableFuture<TxnState> checkTransactionStatus(final String scope, final String stream,
            final TxnId txnId) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        Exceptions.checkNotNullOrEmpty(stream, "stream");
        Preconditions.checkNotNull(txnId, "txnId");
        return streamStore.transactionStatus(scope, stream, ModelHelper.encode(txnId), null, executor)
                .thenApplyAsync(res -> TxnState.newBuilder().setState(TxnState.State.valueOf(res.name())).build(), executor);
    }

    /**
     * Controller Service API to create scope.
     *
     * @param scope Name of scope to be created.
     * @return Status of create scope.
     */
    public CompletableFuture<CreateScopeStatus> createScope(final String scope) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        try {
            NameUtils.validateScopeName(scope);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Create scope failed due to invalid scope name {}", scope);
            return CompletableFuture.completedFuture(CreateScopeStatus.newBuilder().setStatus(
                    CreateScopeStatus.Status.INVALID_SCOPE_NAME).build());
        }
        return streamStore.createScope(scope);
    }

    /**
     * Controller Service API to delete scope.
     *
     * @param scope Name of scope to be deleted.
     * @return Status of delete scope.
     */
    public CompletableFuture<DeleteScopeStatus> deleteScope(final String scope) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        return streamStore.deleteScope(scope);
    }

    /**
     * List existing streams in scopes.
     *
     * @param scope Name of the scope.
     * @return List of streams in scope.
     */
    public CompletableFuture<Map<String, StreamConfiguration>> listStreamsInScope(final String scope) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        return streamStore.listStreamsInScope(scope);
    }

    /**
     * List existing streams in scopes.
     *
     * @param scope Name of the scope.
     * @param token continuation token
     * @param limit limit for number of streams to return. 
     * @return List of streams in scope.
     */
    public CompletableFuture<Pair<List<String>, String>> listStreams(final String scope, final String token, final int limit) {
        Exceptions.checkNotNullOrEmpty(scope, "scope");
        return streamStore.listStream(scope, token, limit, executor);
    }

    /**
     * List Scopes in cluster.
     *
     * @return List of scopes.
     */
    public CompletableFuture<List<String>> listScopes() {
        return streamStore.listScopes();
    }

    /**
     * Retrieve a scope.
     *
     * @param scopeName Name of Scope.
     * @return Scope if it exists.
     */
    public CompletableFuture<String> getScope(final String scopeName) {
        Preconditions.checkNotNull(scopeName);
        return streamStore.getScopeConfiguration(scopeName);
    }

    // Metrics reporting region

    private void reportCreateStreamMetrics(String scope, String streamName, int initialSegments, CreateStreamStatus.Status status,
                                           Duration latency) {
        if (status.equals(CreateStreamStatus.Status.SUCCESS)) {
            streamMetrics.createStream(scope, streamName, initialSegments, latency);
        } else if (status.equals(CreateStreamStatus.Status.FAILURE)) {
            streamMetrics.createStreamFailed(scope, streamName);
        }
    }

    private void reportUpdateStreamMetrics(String scope, String streamName, UpdateStreamStatus.Status status, Duration latency) {
        if (status.equals(UpdateStreamStatus.Status.SUCCESS)) {
            streamMetrics.updateStream(scope, streamName, latency);
        } else if (status.equals(UpdateStreamStatus.Status.FAILURE)) {
            streamMetrics.updateStreamFailed(scope, streamName);
        }
    }

    private void reportTruncateStreamMetrics(String scope, String streamName, UpdateStreamStatus.Status status, Duration latency) {
        if (status.equals(UpdateStreamStatus.Status.SUCCESS)) {
            streamMetrics.truncateStream(scope, streamName, latency);
        } else if (status.equals(UpdateStreamStatus.Status.FAILURE)) {
            streamMetrics.truncateStreamFailed(scope, streamName);
        }
    }

    private void reportSealStreamMetrics(String scope, String streamName, UpdateStreamStatus.Status status, Duration latency) {
        if (status.equals(UpdateStreamStatus.Status.SUCCESS)) {
            streamMetrics.sealStream(scope, streamName, latency);
        } else if (status.equals(UpdateStreamStatus.Status.FAILURE)) {
            streamMetrics.sealStreamFailed(scope, streamName);
        }
    }

    private void reportDeleteStreamMetrics(String scope, String streamName, DeleteStreamStatus.Status status, Duration latency) {
        if (status.equals(DeleteStreamStatus.Status.SUCCESS)) {
            streamMetrics.deleteStream(scope, streamName, latency);
        } else if (status.equals(DeleteStreamStatus.Status.FAILURE)) {
            streamMetrics.deleteStreamFailed(scope, streamName);
        }
    }

    // End metrics reporting region
}
