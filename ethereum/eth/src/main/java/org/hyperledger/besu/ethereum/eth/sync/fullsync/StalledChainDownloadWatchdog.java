/*
 * Copyright contributors to Hyperledger Besu.
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

import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.eth.manager.ChainHeadEstimate;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches for a full-sync chain download that has stopped making progress while peers are
 * demonstrably ahead, and restarts it.
 *
 * <p>The chain download loop is meant to run for the life of the process: for a PoA/BFT chain the
 * termination condition is {@code never()}, so {@link
 * org.hyperledger.besu.ethereum.eth.sync.PipelineChainDownloader} loops forever, acquiring a sync
 * target, downloading, clearing the target, and going round again. Nothing supervises that loop,
 * and {@code ChainDownloader.start()} is single-shot -- it throws on a second call -- so if the
 * loop ever exits or wedges, the node cannot get back to the chain head under its own power. It
 * stays a healthy-looking process, peered and serving RPC, on a chain head that never moves, until
 * an operator restarts it.
 *
 * <p>Two ways that has been observed to happen, both silent:
 *
 * <ul>
 *   <li>The loop's future completes exceptionally. {@code repeatUnlessDownloadComplete()} is not
 *       covered by the {@code exceptionallyCompose} that guards the download itself, so a throw
 *       from {@code clearSyncTarget()} -- for instance out of a subscriber's callback -- ends the
 *       loop, and the resulting failed future has no handler attached.
 *   <li>The loop blocks. A consensus engine stopping its block-producing thread from inside a
 *       sync-status callback used to deadlock against the thread setting the sync target.
 * </ul>
 *
 * <p>Detection is deliberately conservative: a restart is only attempted while the best peer's
 * estimated height is more than {@code syncTolerance} above the local chain head, and either the
 * download loop's future is already done or the local chain head has not moved across several
 * consecutive checks. A node that is behind but importing is progressing and is left alone, and a
 * node at the chain head is never touched -- which matters for BFT, where the head advances through
 * consensus rather than through this downloader.
 */
public class StalledChainDownloadWatchdog {

  private static final Logger LOG = LoggerFactory.getLogger(StalledChainDownloadWatchdog.class);

  /** How often to evaluate whether the download has stalled. */
  public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(30);

  /**
   * Consecutive checks with no chain-head progress, while behind, before restarting. With the
   * default interval that is three minutes of provable inactivity.
   */
  public static final int DEFAULT_STALLED_CHECKS_BEFORE_RESTART = 6;

  /**
   * How far behind the best peer we must be before a stall counts. Generous relative to {@code
   * Synchronizer.DEFAULT_IN_SYNC_TOLERANCE} so that ordinary lag on a fast-block chain, or a peer
   * over-reporting its height, never triggers a restart.
   */
  public static final long DEFAULT_BEHIND_TOLERANCE = 50;

  private final SyncState syncState;
  private final Blockchain blockchain;
  private final EthScheduler scheduler;
  private final BooleanSupplier downloadLoopFinished;
  private final Runnable restartChainDownload;
  private final Duration checkInterval;
  private final int stalledChecksBeforeRestart;
  private final long behindTolerance;
  private final Counter restartCounter;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile ScheduledFuture<?> scheduledCheck;

  // Only touched from the scheduler thread that runs check().
  private long lastObservedChainHeight = -1;
  private int stalledChecks = 0;

  /**
   * Instantiates a new watchdog.
   *
   * @param syncState the sync state, for the best peer's height estimate
   * @param blockchain the blockchain, for local chain-head progress
   * @param scheduler the scheduler the periodic check runs on
   * @param downloadLoopFinished whether the download loop's future has completed
   * @param restartChainDownload cancels the current download loop and starts a fresh one
   * @param checkInterval how often to evaluate
   * @param stalledChecksBeforeRestart consecutive no-progress checks tolerated before restarting
   * @param behindTolerance how far behind the best peer we must be for a stall to count
   * @param metricsSystem the metrics system
   */
  public StalledChainDownloadWatchdog(
      final SyncState syncState,
      final Blockchain blockchain,
      final EthScheduler scheduler,
      final BooleanSupplier downloadLoopFinished,
      final Runnable restartChainDownload,
      final Duration checkInterval,
      final int stalledChecksBeforeRestart,
      final long behindTolerance,
      final MetricsSystem metricsSystem) {
    this.syncState = syncState;
    this.blockchain = blockchain;
    this.scheduler = scheduler;
    this.downloadLoopFinished = downloadLoopFinished;
    this.restartChainDownload = restartChainDownload;
    this.checkInterval = checkInterval;
    this.stalledChecksBeforeRestart = stalledChecksBeforeRestart;
    this.behindTolerance = behindTolerance;
    this.restartCounter =
        metricsSystem.createCounter(
            BesuMetricCategory.SYNCHRONIZER,
            "chain_download_watchdog_restarts_total",
            "Number of times the watchdog restarted a stalled chain download");
  }

  /** Starts the periodic check. Repeated calls are ignored. */
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    lastObservedChainHeight = -1;
    stalledChecks = 0;
    scheduledCheck =
        scheduler.scheduleFutureTaskWithFixedDelay(this::check, checkInterval, checkInterval);
    LOG.debug(
        "Stalled chain download watchdog started: checking every {}, restarting after {} "
            + "consecutive checks with no progress while more than {} blocks behind",
        checkInterval,
        stalledChecksBeforeRestart,
        behindTolerance);
  }

  /** Stops the periodic check. */
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    final ScheduledFuture<?> current = scheduledCheck;
    if (current != null) {
      current.cancel(false);
      scheduledCheck = null;
    }
  }

  /**
   * Runs one evaluation. Package-private so tests can drive it without waiting on the scheduler.
   */
  void check() {
    if (!running.get()) {
      return;
    }
    try {
      evaluate();
    } catch (final RuntimeException e) {
      // scheduleWithFixedDelay silently stops re-scheduling if the task throws, which would leave
      // us with a watchdog that itself needs watching.
      LOG.warn("Stalled chain download watchdog check failed; will retry next interval", e);
    }
  }

  private void evaluate() {
    final long localHeight = blockchain.getChainHeadBlockNumber();

    if (!syncState.isInitialSyncPhaseDone()
        || syncState.hasReachedTerminalDifficulty().orElse(Boolean.FALSE)) {
      // Either an initial (snap) sync is still running, in which case the full-sync chain head is
      // not expected to be moving and peers being ahead is normal, or we are past the merge
      // terminal block, where the download loop is *meant* to have finished and the consensus
      // layer drives the chain from here. Restarting in either case would be wrong.
      resetProgressTracking(localHeight);
      return;
    }

    final OptionalLong bestPeerHeight = bestPeerHeight();
    if (bestPeerHeight.isEmpty() || bestPeerHeight.getAsLong() <= localHeight + behindTolerance) {
      // At (or near enough) the chain head, or nothing to measure against.
      resetProgressTracking(localHeight);
      return;
    }

    // The loop's future settling is unambiguous: with a never() termination condition, and having
    // ruled out the merge above, it should still be running. No need to wait out the stall window.
    if (downloadLoopFinished.getAsBoolean()) {
      restart(
          localHeight,
          bestPeerHeight.getAsLong(),
          "the chain download loop has finished while the node is still behind");
      return;
    }

    if (localHeight != lastObservedChainHeight) {
      // Behind, but importing. Nothing to do.
      resetProgressTracking(localHeight);
      return;
    }

    stalledChecks++;
    if (stalledChecks < stalledChecksBeforeRestart) {
      LOG.debug(
          "Chain download has not progressed past block {} while best peer is at {} ({}/{} checks)",
          localHeight,
          bestPeerHeight.getAsLong(),
          stalledChecks,
          stalledChecksBeforeRestart);
      return;
    }

    restart(
        localHeight,
        bestPeerHeight.getAsLong(),
        "the chain download has made no progress for "
            + checkInterval.multipliedBy(stalledChecks)
            + " while the node is behind");
  }

  private void restart(final long localHeight, final long bestPeerHeight, final String reason) {
    LOG.warn(
        "Restarting chain download: {}. Local chain head {}, best peer estimated height {}.",
        reason,
        localHeight,
        bestPeerHeight);
    resetProgressTracking(localHeight);
    restartCounter.inc();
    restartChainDownload.run();
  }

  private void resetProgressTracking(final long localHeight) {
    lastObservedChainHeight = localHeight;
    stalledChecks = 0;
  }

  private OptionalLong bestPeerHeight() {
    return syncState
        .getBestPeerChainHead()
        .map(ChainHeadEstimate::getEstimatedHeight)
        .map(OptionalLong::of)
        .orElseGet(OptionalLong::empty);
  }
}
