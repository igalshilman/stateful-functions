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

package com.ververica.statefun.flink.core.logger;

import static com.ververica.statefun.flink.core.TestUtils.DUMMY_PAYLOAD;
import static com.ververica.statefun.flink.core.TestUtils.integerAddress;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import com.ververica.statefun.flink.core.common.MessageKeyGroupAssigner;
import com.ververica.statefun.flink.core.di.ObjectContainer;
import com.ververica.statefun.flink.core.message.Message;
import com.ververica.statefun.flink.core.message.MessageFactory;
import com.ververica.statefun.flink.core.message.MessageFactoryType;
import com.ververica.statefun.flink.core.message.MessageTypeInformation;
import java.io.*;
import java.util.ArrayList;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

@SuppressWarnings("SameParameterValue")
public class UnboundedFeedbackLoggerTest {
  private static IOManagerAsync IO_MANAGER;

  @BeforeClass
  public static void beforeClass() {
    IO_MANAGER = new IOManagerAsync();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (IO_MANAGER != null) {
      IO_MANAGER.close();
      IO_MANAGER = null;
    }
  }

  @Test
  public void sanity() {
    UnboundedFeedbackLogger logger = instanceUnderTest(128, 1);

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    logger.startLogging(output);
    logger.commit();

    assertThat(output.size(), is(0));
  }

  @Test(expected = IllegalStateException.class)
  public void commitWithoutStartLoggingShouldBeIllegal() {
    UnboundedFeedbackLogger logger = instanceUnderTest(128, 1);

    logger.commit();
  }

  @Test
  public void roundTrip() throws Exception {
    roundTrip(100, 1024);
  }

  @Ignore
  @Test
  public void roundTripWithSpill() throws Exception {
    roundTrip(1_000_000, 0);
  }

  private void roundTrip(int numElements, int maxMemoryInBytes) throws Exception {
    InputStream input = serializeKeyGroup(1, maxMemoryInBytes, numElements);

    ArrayList<Message> envelopes = new ArrayList<>();

    UnboundedFeedbackLogger<Message> loggerUnderTest = instanceUnderTest(1, 0);
    loggerUnderTest.replyLoggedEnvelops(input, envelopes::add);

    MessageFactory factory = MessageFactory.forType(MessageFactoryType.WITH_PROTOBUF_PAYLOADS);
    for (int i = 0; i < numElements; i++) {
      Message adaptor = envelopes.get(i);

      assertThat(adaptor.source(), is(integerAddress(2 * i)));
      assertThat(adaptor.target(), is(integerAddress(2 * i + 1)));

      Object payload = adaptor.payload(factory, getClass().getClassLoader());
      assertThat(payload, is(DUMMY_PAYLOAD));
    }
  }

  private ByteArrayInputStream serializeKeyGroup(int maxParallelism, long maxMemory, int numItems) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    UnboundedFeedbackLogger loggerUnderTest = instanceUnderTest(maxParallelism, maxMemory);

    loggerUnderTest.startLogging(output);
    MessageFactory factory = MessageFactory.forType(MessageFactoryType.WITH_PROTOBUF_PAYLOADS);
    for (int i = 0; i < numItems; i++) {

      Message envelope =
          factory.from(integerAddress(2 * i), integerAddress(2 * i + 1), DUMMY_PAYLOAD);

      loggerUnderTest.append(envelope);
    }

    loggerUnderTest.commit();

    return new ByteArrayInputStream(output.toByteArray());
  }

  private UnboundedFeedbackLogger<Message> instanceUnderTest(int maxParallelism, long totalMemory) {
    TypeSerializer<Message> serializer =
        new MessageTypeInformation(MessageFactoryType.WITH_PROTOBUF_PAYLOADS)
            .createSerializer(new ExecutionConfig());

    ObjectContainer container =
        Loggers.unboundedSpillableLoggerContainer(
            IO_MANAGER,
            maxParallelism,
            totalMemory,
            serializer,
            new MessageKeyGroupAssigner(maxParallelism));

    container.add("checkpoint-stream-ops", CheckpointedStreamOperations.class, NOOP.INSTANCE);
    return container.get(UnboundedFeedbackLogger.class);
  }

  enum NOOP implements CheckpointedStreamOperations {
    INSTANCE;

    @Override
    public void requireKeyedStateCheckpointed(OutputStream keyedStateCheckpointOutputStream) {
      // noop
    }

    @Override
    public void startNewKeyGroup(OutputStream stream, int keyGroup) {}

    @Override
    public Closeable acquireLease(OutputStream keyedStateCheckpointOutputStream) {
      return () -> {}; // NOOP
    }
  }
}
