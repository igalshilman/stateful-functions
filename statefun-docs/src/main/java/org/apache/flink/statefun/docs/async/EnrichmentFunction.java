/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.statefun.docs.async;

import java.util.concurrent.CompletableFuture;
import org.apache.flink.statefun.sdk.AsyncOperationResult;
import org.apache.flink.statefun.sdk.Context;
import org.apache.flink.statefun.sdk.StatefulFunction;

@SuppressWarnings("unchecked")
public class EnrichmentFunction implements StatefulFunction {

  private final QueryService client;

  public EnrichmentFunction(QueryService client) {
    this.client = client;
  }

  @Override
  public void invoke(Context context, Object input) {
    if (input instanceof User) {
      onUser(context, (User) input);
    } else if (input instanceof AsyncOperationResult) {
      onAsyncResult((AsyncOperationResult) input);
    }
  }

  private void onUser(Context context, User user) {
    CompletableFuture<UserEnrichment> future = client.getDataAsync(user.getUserId());
    context.registerAsyncOperation(user, future);
  }

  private void onAsyncResult(AsyncOperationResult<User, UserEnrichment> result) {
    if (result.successful()) {
      User metadata = result.metadata();
      UserEnrichment value = result.value();
      System.out.println(String.format("Successfully completed future: %s %s", metadata, value));
    } else if (result.failure()) {
      System.out.println(String.format("Something has gone terribly wrong %s", result.throwable()));
    } else {
      System.out.println("Not sure what happened, maybe retry");
    }
  }
}
