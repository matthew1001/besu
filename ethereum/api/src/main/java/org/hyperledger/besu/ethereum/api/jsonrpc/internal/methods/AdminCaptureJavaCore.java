/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.util.LogConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;

import java.lang.management.ManagementFactory;
import java.security.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class AdminCaptureJavaCore implements JsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(AdminCaptureJavaCore.class);
  private static final String DEST_STDOUT = "STDOUT";
  private static final String DEST_FILE = "FILE";

  private static final Set<String> VALID_PARAMS =
          Set.of(DEST_STDOUT, DEST_FILE);

  @Override
  public String getName() {
    return RpcMethod.ADMIN_CAPTURE_JAVA_CORE.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final String outputDest = requestContext.getRequiredParameter(0, String.class);
    if (VALID_PARAMS.contains(outputDest)) {
      captureJavaCore(outputDest);
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId());
    } else {
      return new JsonRpcErrorResponse(
              requestContext.getRequest().getId(), JsonRpcError.INVALID_PARAMS);
    }
  }

  private void captureJavaCore(final String outputDest) {
    System.out.println("Capturing a java core");

    if (outputDest.equals(DEST_STDOUT)) {
      LOG.info(buildDumpInfo());
    } else {
      final Path targetFilePath;
      try {
        targetFilePath = Files.createTempFile("javacore", ".core");
        System.out.println("Writing to file " + targetFilePath);
        try (final BufferedWriter writer = Files.newBufferedWriter(targetFilePath)) {
          writer.write(buildDumpInfo());
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      } catch (final IOException e) {
        System.out.println("Exception caught writing javacore: " + e.getMessage());
      }
    }
  }

  private static String buildDumpInfo() {
    Map<Thread, StackTraceElement[]> threadStacks = Thread.getAllStackTraces();
    StringBuilder info = new StringBuilder();
    info.append("\n***************************************************\n");
    info.append("* System time: " + Instant.now() + "\n");
    info.append("* PID: " + ManagementFactory.getRuntimeMXBean().getPid() + "\n");
    info.append("* Cores: " + ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors() + "\n");
    info.append("* OS: " + ManagementFactory.getOperatingSystemMXBean().getName() + " (" + ManagementFactory.getOperatingSystemMXBean().getArch() + ")\n");
    info.append("* Args: " + ProcessHandle.current().info().commandLine() + "\n");
    info.append("* Thread count: " + ManagementFactory.getThreadMXBean().getThreadCount() + "\n");
    info.append("* Heap usage: " + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage() + "\n");
    info.append("****************************************************\n");
    info.append("Thread stacks:\n\n");
    for (StackTraceElement[] nextThreadStack: threadStacks.values()) {
      if (nextThreadStack.length > 0) {
        for (StackTraceElement nextStackElement : nextThreadStack) {
          info.append(nextStackElement + "\n");
        }
        info.append("\n");
      }
    }
    return info.toString();
  }
}
