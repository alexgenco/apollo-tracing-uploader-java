package com.braintreepayments.apollo_tracing_uploader;

import java.time.Instant;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.protobuf.Timestamp;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.language.AstPrinter;
import graphql.language.AstSignature;
import mdg.engine.proto.Reports;

public class TracingUploadInstrumentationState implements InstrumentationState {
  private final Gson gson = new Gson();
  private BiConsumer<Reports.Trace.Builder, Object> customizeTrace;
  private final VariablesSanitizer sanitizeVariables;
  private final Reports.Trace.Builder proto;
  private final long startRequestNs;
  private Object context;
  public final boolean noop;

  public TracingUploadInstrumentationState(BiConsumer<Reports.Trace.Builder, Object> customizeTrace,
                                           VariablesSanitizer sanitizeVariables,
                                           boolean noop) {
    this.customizeTrace = customizeTrace;
    this.sanitizeVariables = sanitizeVariables;
    this.proto = Reports.Trace.newBuilder();
    this.startRequestNs = System.nanoTime();
    this.context = null;
    this.noop = noop;
  }

  public ExecutionInput instrumentExecutionInput(ExecutionInput executionInput) {
    this.context = executionInput.getContext();

    // This signature is overridden after parsing if document is valid
    proto.setSignature(executionInput.getQuery());

    Optional.ofNullable(executionInput.getVariables())
      .map(sanitizeVariables)
      .map(Map::entrySet)
      .map(Collection::stream)
      .map(stream -> stream.collect(Collectors.toMap(Map.Entry::getKey, entry -> gson.toJson(entry.getValue()))))
      .ifPresent(proto.getDetailsBuilder()::putAllVariablesJson);

    return executionInput;
  }

  public InstrumentationContext<ExecutionResult> beginExecution(InstrumentationExecutionParameters parameters) {
    proto.setStartTime(protoTimestamp(Instant.now()));

    return SimpleInstrumentationContext.whenCompleted((executionResult, e) -> {
      long nowNs = System.nanoTime();
      Instant nowInstant = Instant.now();

      proto.setEndTime(protoTimestamp(nowInstant)).setDurationNs(nowNs - startRequestNs);

      Optional.ofNullable(executionResult.getErrors())
        .orElseGet(Collections::emptyList)
        .forEach(graphQLError -> {
          NodePath path = Optional.ofNullable(graphQLError.getPath()).map(NodePath::fromList).orElseGet(NodePath::root);
          Reports.Trace.Node.Builder node = path.getChild(proto.getRootBuilder());

          Reports.Trace.Error.Builder error = node.addErrorBuilder()
            .setMessage(graphQLError.getMessage())
            .setJson(gson.toJson(graphQLError.toSpecification()));

          Optional.ofNullable(graphQLError.getLocations())
            .orElseGet(Collections::emptyList)
            .forEach(location -> error.addLocationBuilder()
              .setColumn(location.getColumn())
              .setLine(location.getLine()));
        });
    });
  }

  public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters) {
    String operationName = parameters.getExecutionContext().getOperationDefinition().getName();

    Optional.ofNullable(operationName).ifPresent(proto.getDetailsBuilder()::setOperationName);

    Optional.ofNullable(parameters.getExecutionContext().getDocument())
      .map(doc -> new AstSignature().signatureQuery(doc, operationName))
      .map(AstPrinter::printAstCompact)
      .ifPresent(proto::setSignature);

    return SimpleInstrumentationContext.noOp();
  }

  public InstrumentationContext<Object> beginFieldFetch(InstrumentationFieldFetchParameters parameters) {
    long startFieldFetchNs = System.nanoTime();
    long offsetNs = startFieldFetchNs - startRequestNs;
    ExecutionStepInfo stepInfo = parameters.getExecutionStepInfo();

    return SimpleInstrumentationContext.whenCompleted((obj, e) -> {
      long now = System.nanoTime();
      long durationNs = now - startFieldFetchNs;

      Reports.Trace.Node.Builder rootNode = proto.getRootBuilder();
      NodePath path = NodePath.fromList(stepInfo.getPath().toList());

      path.getChild(rootNode)
        .setOriginalFieldName(stepInfo.getFieldDefinition().getName())
        .setType(stepInfo.simplePrint())
        .setParentType(stepInfo.getParent().getUnwrappedNonNullType().getName())
        .setStartTime(offsetNs)
        .setEndTime(offsetNs + durationNs);
    });
  }

  public Reports.Trace build() {
    customizeTrace.accept(proto, context);
    return proto.build();
  }

  private Timestamp protoTimestamp(Instant instant) {
    return Timestamp.newBuilder()
      .setSeconds(instant.getLong(ChronoField.INSTANT_SECONDS))
      .setNanos(instant.get(ChronoField.NANO_OF_SECOND))
      .build();
  }
}
