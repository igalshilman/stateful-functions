/*
 * Copyright 2019 Ververica GmbH.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.statefun.flink.core.translation;

import com.ververica.statefun.flink.core.StatefulFunctionsJobConstants;
import com.ververica.statefun.flink.core.StatefulFunctionsUniverse;
import com.ververica.statefun.flink.core.common.KeyBy;
import com.ververica.statefun.flink.core.common.SerializableFunction;
import com.ververica.statefun.flink.core.common.SerializablePredicate;
import com.ververica.statefun.flink.core.feedback.FeedbackKey;
import com.ververica.statefun.flink.core.feedback.FeedbackSinkOperator;
import com.ververica.statefun.flink.core.feedback.FeedbackUnionOperatorFactory;
import com.ververica.statefun.flink.core.functions.FunctionGroupDispatchFactory;
import com.ververica.statefun.flink.core.message.Message;
import com.ververica.statefun.flink.core.message.MessageKeySelector;
import com.ververica.statefun.sdk.io.EgressIdentifier;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.OutputTag;

public final class FlinkUniverse {
  private static final FeedbackKey<Message> FEEDBACK_KEY =
      new FeedbackKey<>("stateful-functions-pipeline", 1);
  private final StatefulFunctionsUniverse universe;

  public FlinkUniverse(StatefulFunctionsUniverse universe) {
    this.universe = Objects.requireNonNull(universe);
  }

  public void configure(StreamExecutionEnvironment env) {
    Sources sources = Sources.create(env, universe);
    Sinks sinks = Sinks.create(universe);

    SingleOutputStreamOperator<Message> feedbackUnionOperator =
        feedbackUnionOperator(sources.unionStream());

    SingleOutputStreamOperator<Message> functionOutputStream =
        functionOperator(feedbackUnionOperator, sinks.sideOutputTags());

    SingleOutputStreamOperator<Void> writeBackOut = feedbackOperator(functionOutputStream);

    coLocate(feedbackUnionOperator, functionOutputStream, writeBackOut);

    sinks.consumeFrom(functionOutputStream);
  }

  private SingleOutputStreamOperator<Message> feedbackUnionOperator(DataStream<Message> input) {
    TypeInformation<Message> typeInfo = input.getType();

    FeedbackUnionOperatorFactory<Message> factory =
        new FeedbackUnionOperatorFactory<>(
            FEEDBACK_KEY, new IsCheckpointBarrier(), new FeedbackKeySelector());

    return input
        .keyBy(new MessageKeySelector())
        .transform(StatefulFunctionsJobConstants.FEEDBACK_UNION_OPERATOR_NAME, typeInfo, factory)
        .uid(StatefulFunctionsJobConstants.FEEDBACK_UNION_OPERATOR_UID);
  }

  private SingleOutputStreamOperator<Message> functionOperator(
      DataStream<Message> input, Map<EgressIdentifier<?>, OutputTag<Object>> sideOutputs) {

    TypeInformation<Message> typeInfo = input.getType();

    FunctionGroupDispatchFactory operatorFactory = new FunctionGroupDispatchFactory(sideOutputs);

    return DataStreamUtils.reinterpretAsKeyedStream(input, new MessageKeySelector())
        .transform(StatefulFunctionsJobConstants.FUNCTION_OPERATOR_NAME, typeInfo, operatorFactory)
        .uid(StatefulFunctionsJobConstants.FUNCTION_OPERATOR_UID);
  }

  private SingleOutputStreamOperator<Void> feedbackOperator(
      SingleOutputStreamOperator<Message> functionOut) {

    LongFunction<Message> toMessage = new CheckpointToMessage(universe.messageFactoryType());

    FeedbackSinkOperator<Message> sinkOperator =
        new FeedbackSinkOperator<>(FEEDBACK_KEY, toMessage);

    return functionOut
        .keyBy(new MessageKeySelector())
        .transform(
            StatefulFunctionsJobConstants.WRITE_BACK_OPERATOR_NAME,
            TypeInformation.of(Void.class),
            sinkOperator)
        .uid(StatefulFunctionsJobConstants.WRITE_BACK_OPERATOR_UID);
  }

  private static void coLocate(DataStream<?> a, DataStream<?> b, DataStream<?> c) {
    String stringKey = FEEDBACK_KEY.asColocationKey();
    a.getTransformation().setCoLocationGroupKey(stringKey);
    b.getTransformation().setCoLocationGroupKey(stringKey);
    c.getTransformation().setCoLocationGroupKey(stringKey);

    a.getTransformation().setParallelism(b.getParallelism());
    c.getTransformation().setParallelism(b.getParallelism());
  }

  private static final class IsCheckpointBarrier implements SerializablePredicate<Message> {

    private static final long serialVersionUID = 1;

    @Override
    public boolean test(Message message) {
      return message.isBarrierMessage();
    }
  }

  private static final class FeedbackKeySelector implements SerializableFunction<Message, String> {

    private static final long serialVersionUID = 1;

    @Override
    public String apply(Message message) {
      return KeyBy.apply(message.target());
    }
  }
}
