/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hdds.scm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.ratis.shaded.io.netty.bootstrap.Bootstrap;
import org.apache.ratis.shaded.io.netty.channel.Channel;
import org.apache.ratis.shaded.io.netty.channel.EventLoopGroup;
import org.apache.ratis.shaded.io.netty.channel.nio.NioEventLoopGroup;
import org.apache.ratis.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.ratis.shaded.io.netty.handler.logging.LogLevel;
import org.apache.ratis.shaded.io.netty.handler.logging.LoggingHandler;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.hdds.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

/**
 * A Client for the storageContainer protocol.
 */
public class XceiverClient extends XceiverClientSpi {
  static final Logger LOG = LoggerFactory.getLogger(XceiverClient.class);
  private final Pipeline pipeline;
  private final Configuration config;
  private Channel channel;
  private Bootstrap b;
  private EventLoopGroup group;
  private final Semaphore semaphore;
  private boolean closed = false;

  /**
   * Constructs a client that can communicate with the Container framework on
   * data nodes.
   *
   * @param pipeline - Pipeline that defines the machines.
   * @param config -- Ozone Config
   */
  public XceiverClient(Pipeline pipeline, Configuration config) {
    super();
    Preconditions.checkNotNull(pipeline);
    Preconditions.checkNotNull(config);
    this.pipeline = pipeline;
    this.config = config;
    this.semaphore =
        new Semaphore(HddsClientUtils.getMaxOutstandingRequests(config));
  }

  @Override
  public void connect() throws Exception {
    if (closed) {
      throw new IOException("This channel is not connected.");
    }

    if (channel != null && channel.isActive()) {
      throw new IOException("This client is already connected to a host.");
    }

    group = new NioEventLoopGroup();
    b = new Bootstrap();
    b.group(group)
        .channel(NioSocketChannel.class)
        .handler(new LoggingHandler(LogLevel.INFO))
        .handler(new XceiverClientInitializer(this.pipeline, semaphore));
    DatanodeDetails leader = this.pipeline.getLeader();

    // read port from the data node, on failure use default configured
    // port.
    int port = leader.getPort(DatanodeDetails.Port.Name.STANDALONE).getValue();
    if (port == 0) {
      port = config.getInt(OzoneConfigKeys.DFS_CONTAINER_IPC_PORT,
          OzoneConfigKeys.DFS_CONTAINER_IPC_PORT_DEFAULT);
    }
    LOG.debug("Connecting to server Port : " + port);
    channel = b.connect(leader.getHostName(), port).sync().channel();
  }

  public void reconnect() throws IOException {
    try {
      connect();
      if (channel == null || !channel.isActive()) {
        throw new IOException("This channel is not connected.");
      }
    } catch (Exception e) {
      LOG.error("Error while connecting: ", e);
      throw new IOException(e);
    }
  }

  /**
   * Returns if the exceiver client connects to a server.
   *
   * @return True if the connection is alive, false otherwise.
   */
  @VisibleForTesting
  public boolean isConnected() {
    return channel.isActive();
  }

  @Override
  public void close() {
    closed = true;
    if (group != null) {
      group.shutdownGracefully().awaitUninterruptibly();
    }
  }

  @Override
  public Pipeline getPipeline() {
    return pipeline;
  }

  @Override
  public ContainerProtos.ContainerCommandResponseProto sendCommand(
      ContainerProtos.ContainerCommandRequestProto request) throws IOException {
    try {
      if ((channel == null) || (!channel.isActive())) {
        reconnect();
      }
      XceiverClientHandler handler =
          channel.pipeline().get(XceiverClientHandler.class);

      return handler.sendCommand(request);
    } catch (ExecutionException | InterruptedException e) {
      /**
       * In case the netty channel handler throws an exception,
       * the exception thrown will be wrapped within {@link ExecutionException}.
       * Unwarpping here so that original exception gets passed
       * to to the client.
       */
      if (e instanceof ExecutionException) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
      }
      throw new IOException(
          "Unexpected exception during execution:" + e.getMessage());
    }
  }

  /**
   * Sends a given command to server gets a waitable future back.
   *
   * @param request Request
   * @return Response to the command
   * @throws IOException
   */
  @Override
  public CompletableFuture<ContainerProtos.ContainerCommandResponseProto>
      sendCommandAsync(ContainerProtos.ContainerCommandRequestProto request)
      throws IOException, ExecutionException, InterruptedException {
    if ((channel == null) || (!channel.isActive())) {
      reconnect();
    }
    XceiverClientHandler handler =
        channel.pipeline().get(XceiverClientHandler.class);
    return handler.sendCommandAsync(request);
  }

  /**
   * Create a pipeline.
   *
   * @param pipelineID - Name of the pipeline.
   * @param datanodes - Datanodes
   */
  @Override
  public void createPipeline(String pipelineID, List<DatanodeDetails> datanodes)
      throws IOException {
    // For stand alone pipeline, there is no notion called setup pipeline.
    return;
  }

  /**
   * Returns pipeline Type.
   *
   * @return - Stand Alone as the type.
   */
  @Override
  public HddsProtos.ReplicationType getPipelineType() {
    return HddsProtos.ReplicationType.STAND_ALONE;
  }
}
