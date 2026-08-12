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
package org.hyperledger.besu.ethereum.eth.sync.fullsync;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.peertask.PeerTaskExecutor;
import org.hyperledger.besu.ethereum.eth.sync.ChainDownloader;
import org.hyperledger.besu.ethereum.eth.sync.SyncMode;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.TrailingPeerRequirements;
import org.hyperledger.besu.ethereum.eth.sync.fullsync.era1prepipeline.Era1ImportPrepipelineFactory;
import org.hyperledger.besu.ethereum.eth.sync.fullsync.era1prepipeline.FileImportChainDownloader;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.metrics.SyncDurationMetrics;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FullSyncDownloader {

  private static final Logger LOG = LoggerFactory.getLogger(FullSyncDownloader.class);

  // The download loop is single-shot -- ChainDownloader.start() throws on a second call -- so
  // recovering from a loop that has exited or wedged means building a replacement. Keep the recipe
  // rather than just the instance.
  private final Supplier<ChainDownloader> chainDownloaderFactory;
  private final AtomicReference<ChainDownloader> chainDownloader = new AtomicReference<>();
  private final AtomicReference<CompletableFuture<Void>> currentDownload = new AtomicReference<>();
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private final StalledChainDownloadWatchdog watchdog;
  private final Optional<ChainDownloader> era1PrepipelineChainDownloader;
  private final SynchronizerConfiguration syncConfig;
  private final ProtocolContext protocolContext;
  private final SyncState syncState;

  public FullSyncDownloader(
      final SynchronizerConfiguration syncConfig,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext,
      final EthContext ethContext,
      final SyncState syncState,
      final MetricsSystem metricsSystem,
      final SyncTerminationCondition terminationCondition,
      final PeerTaskExecutor peerTaskExecutor,
      final SyncDurationMetrics syncDurationMetrics) {
    this.syncConfig = syncConfig;
    this.protocolContext = protocolContext;
    this.syncState = syncState;

    if (syncConfig.getSyncMode() == SyncMode.FULL && syncConfig.era1ImportPrepipelineEnabled()) {
      this.era1PrepipelineChainDownloader =
          Optional.of(
              new FileImportChainDownloader(
                  new Era1ImportPrepipelineFactory(
                      metricsSystem,
                      syncConfig.era1DataUri(),
                      syncConfig.era1ImportPrepipelineConcurrency(),
                      protocolSchedule,
                      protocolContext,
                      ethContext,
                      terminationCondition),
                  protocolContext.getBlockchain(),
                  ethContext.getScheduler()));
    } else {
      this.era1PrepipelineChainDownloader = Optional.empty();
    }

    this.chainDownloaderFactory =
        () ->
            FullSyncChainDownloader.create(
                syncConfig,
                protocolSchedule,
                protocolContext,
                ethContext,
                syncState,
                metricsSystem,
                terminationCondition,
                syncDurationMetrics,
                peerTaskExecutor);
    this.chainDownloader.set(chainDownloaderFactory.get());

    this.watchdog =
        new StalledChainDownloadWatchdog(
            syncState,
            protocolContext.getBlockchain(),
            ethContext.getScheduler(),
            this::downloadLoopFinished,
            // Off the watchdog's own thread: the watchdog check runs on EthScheduler's timer
            // executor, which is single-threaded and shared with the download loop's retry and
            // find-sync-target delays. Restarting there would put a sync-target publish (and every
            // subscriber callback it fans out to) on that one thread.
            () -> ethContext.getScheduler().scheduleServiceTask(this::restartChainDownload),
            StalledChainDownloadWatchdog.DEFAULT_CHECK_INTERVAL,
            StalledChainDownloadWatchdog.DEFAULT_STALLED_CHECKS_BEFORE_RESTART,
            StalledChainDownloadWatchdog.DEFAULT_BEHIND_TOLERANCE,
            metricsSystem);
  }

  public CompletableFuture<Void> start() {
    if (era1PrepipelineChainDownloader.isPresent()) {
      LOG.info(
          "Starting ERA1 file import prepipeline. Full sync will start after prepipeline completion");
      CompletableFuture<Void> era1PipelineFuture = era1PrepipelineChainDownloader.get().start();
      return era1PipelineFuture.thenAccept(
          (v) -> {
            LOG.info("Starting full sync.");
            startChainDownload();
          });

    } else {
      LOG.info("Starting full sync.");
      return startChainDownload();
    }
  }

  public void stop() {
    stopped.set(true);
    watchdog.stop();
    era1PrepipelineChainDownloader.ifPresent((p) -> p.cancel());
    chainDownloader.get().cancel();
  }

  private CompletableFuture<Void> startChainDownload() {
    final CompletableFuture<Void> download = chainDownloader.get().start();
    currentDownload.set(download);
    watchdog.start();
    return download;
  }

  private boolean downloadLoopFinished() {
    final CompletableFuture<Void> download = currentDownload.get();
    return download != null && download.isDone();
  }

  /**
   * The future of the download loop currently running, or null if none has been started. Only set
   * by a {@code start()} that returned normally, so a fresh instance here is evidence that a
   * replacement loop really did start.
   *
   * @return the current download loop's future
   */
  // Visible for testing.
  CompletableFuture<Void> currentDownloadFuture() {
    return currentDownload.get();
  }

  /**
   * Replaces the chain download loop with a fresh one. Called by the watchdog when the current loop
   * has stopped making progress while the node is behind its peers.
   *
   * <p>The sync target is cleared as part of the swap. Consumers watch it to decide whether the
   * node is syncing -- the BFT mining coordinator stops mining while a target is set -- and a
   * target left pointing at an abandoned download would keep them pinned in that state.
   */
  // Visible for testing. Synchronized so two watchdog firings cannot both build a replacement.
  synchronized void restartChainDownload() {
    if (stopped.get()) {
      return;
    }
    try {
      final ChainDownloader previous = chainDownloader.get();
      if (previous != null) {
        previous.cancel();
      }
      syncState.clearSyncTarget();

      final ChainDownloader replacement = chainDownloaderFactory.get();
      chainDownloader.set(replacement);
      currentDownload.set(replacement.start());
      LOG.info("Chain download restarted.");
    } catch (final RuntimeException e) {
      // Nothing observes the future this runs on, so an escaping throw would vanish. Leave the
      // watchdog able to try again on its next stall window instead.
      LOG.error("Failed to restart the chain download; will retry if the stall persists", e);
    }
  }

  public TrailingPeerRequirements calculateTrailingPeerRequirements() {
    return syncState.isInSync()
        ? TrailingPeerRequirements.UNRESTRICTED
        : new TrailingPeerRequirements(
            protocolContext.getBlockchain().getChainHeadBlockNumber(),
            syncConfig.getMaxTrailingPeers());
  }
}
