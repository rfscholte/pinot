/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.query.service.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pinot.common.exception.QueryException;
import org.apache.pinot.common.proto.PinotQueryWorkerGrpc;
import org.apache.pinot.common.proto.Worker;
import org.apache.pinot.common.utils.NamedThreadFactory;
import org.apache.pinot.query.routing.QueryPlanSerDeUtils;
import org.apache.pinot.query.routing.StageMetadata;
import org.apache.pinot.query.routing.StagePlan;
import org.apache.pinot.query.routing.WorkerMetadata;
import org.apache.pinot.query.runtime.QueryRunner;
import org.apache.pinot.query.service.dispatch.QueryDispatcher;
import org.apache.pinot.spi.accounting.ThreadExecutionContext;
import org.apache.pinot.spi.trace.Tracing;
import org.apache.pinot.spi.utils.CommonConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * {@link QueryServer} is the GRPC server that accepts query plan requests sent from {@link QueryDispatcher}.
 */
public class QueryServer extends PinotQueryWorkerGrpc.PinotQueryWorkerImplBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(QueryServer.class);
  // TODO: Inbound messages can get quite large because we send the entire stage metadata map in each call.
  // See https://github.com/apache/pinot/issues/10331
  private static final int MAX_INBOUND_MESSAGE_SIZE = 64 * 1024 * 1024;

  private final int _port;
  private final QueryRunner _queryRunner;
  // query submission service is only used for plan submission for now.
  // TODO: with complex query submission logic we should allow asynchronous query submission return instead of
  //   directly return from submission response observer.
  private final ExecutorService _querySubmissionExecutorService;

  private Server _server = null;

  public QueryServer(int port, QueryRunner queryRunner) {
    _port = port;
    _queryRunner = queryRunner;
    _querySubmissionExecutorService =
        Executors.newCachedThreadPool(new NamedThreadFactory("query_submission_executor_on_" + _port + "_port"));
  }

  public void start() {
    LOGGER.info("Starting QueryServer");
    try {
      if (_server == null) {
        _server = ServerBuilder.forPort(_port).addService(this).maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE).build();
        LOGGER.info("Initialized QueryServer on port: {}", _port);
      }
      _queryRunner.start();
      _server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void shutdown() {
    LOGGER.info("Shutting down QueryServer");
    try {
      _queryRunner.shutDown();
      if (_server != null) {
        _server.shutdown();
        _server.awaitTermination();
      }
      _querySubmissionExecutorService.shutdown();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void submit(Worker.QueryRequest request, StreamObserver<Worker.QueryResponse> responseObserver) {
    Map<String, String> requestMetadata;
    try {
      requestMetadata = QueryPlanSerDeUtils.fromProtoProperties(request.getMetadata());
    } catch (Exception e) {
      LOGGER.error("Caught exception while deserializing request metadata", e);
      responseObserver.onNext(Worker.QueryResponse.newBuilder()
          .putMetadata(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR,
              QueryException.getTruncatedStackTrace(e)).build());
      responseObserver.onCompleted();
      return;
    }
    long requestId = Long.parseLong(requestMetadata.get(CommonConstants.Query.Request.MetadataKeys.REQUEST_ID));
    long timeoutMs = Long.parseLong(requestMetadata.get(CommonConstants.Broker.Request.QueryOptionKey.TIMEOUT_MS));
    long deadlineMs = System.currentTimeMillis() + timeoutMs;

    Tracing.ThreadAccountantOps.setupRunner(Long.toString(requestId), ThreadExecutionContext.TaskType.MSE);

    List<Worker.StagePlan> protoStagePlans = request.getStagePlanList();
    int numStages = protoStagePlans.size();
    CompletableFuture<?>[] stageSubmissionStubs = new CompletableFuture[numStages];
    ThreadExecutionContext parentContext = Tracing.getThreadAccountant().getThreadExecutionContext();
    for (int i = 0; i < numStages; i++) {
      Worker.StagePlan protoStagePlan = protoStagePlans.get(i);
      stageSubmissionStubs[i] = CompletableFuture.runAsync(() -> {
        StagePlan stagePlan;
        try {
          stagePlan = QueryPlanSerDeUtils.fromProtoStagePlan(protoStagePlan);
        } catch (Exception e) {
          throw new RuntimeException(
              String.format("Caught exception while deserializing stage plan for request: %d, stage: %d", requestId,
                  protoStagePlan.getStageMetadata().getStageId()), e);
        }
        StageMetadata stageMetadata = stagePlan.getStageMetadata();
        List<WorkerMetadata> workerMetadataList = stageMetadata.getWorkerMetadataList();
        int numWorkers = workerMetadataList.size();
        CompletableFuture<?>[] workerSubmissionStubs = new CompletableFuture[numWorkers];
        for (int j = 0; j < numWorkers; j++) {
          WorkerMetadata workerMetadata = workerMetadataList.get(j);
          workerSubmissionStubs[j] =
              CompletableFuture.runAsync(() -> _queryRunner.processQuery(workerMetadata, stagePlan, requestMetadata,
                      parentContext), _querySubmissionExecutorService);
        }
        try {
          CompletableFuture.allOf(workerSubmissionStubs)
              .get(deadlineMs - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
          throw new RuntimeException(
              String.format("Caught exception while submitting request: %d, stage: %d", requestId,
                  stageMetadata.getStageId()), e);
        } finally {
          for (CompletableFuture<?> future : workerSubmissionStubs) {
            if (!future.isDone()) {
              future.cancel(true);
            }
          }
        }
      }, _querySubmissionExecutorService);
    }
    try {
      CompletableFuture.allOf(stageSubmissionStubs).get(deadlineMs - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      LOGGER.error("Caught exception while submitting request: {}", requestId, e);
      responseObserver.onNext(Worker.QueryResponse.newBuilder()
          .putMetadata(CommonConstants.Query.Response.ServerResponseStatus.STATUS_ERROR,
              QueryException.getTruncatedStackTrace(e)).build());
      responseObserver.onCompleted();
      return;
    } finally {
      for (CompletableFuture<?> future : stageSubmissionStubs) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
      Tracing.getThreadAccountant().clear();
    }
    responseObserver.onNext(
        Worker.QueryResponse.newBuilder().putMetadata(CommonConstants.Query.Response.ServerResponseStatus.STATUS_OK, "")
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void cancel(Worker.CancelRequest request, StreamObserver<Worker.CancelResponse> responseObserver) {
    try {
      _queryRunner.cancel(request.getRequestId());
    } catch (Throwable t) {
      LOGGER.error("Caught exception while cancelling opChain for request: {}", request.getRequestId(), t);
    }
    // we always return completed even if cancel attempt fails, server will self clean up in this case.
    responseObserver.onCompleted();
  }
}
