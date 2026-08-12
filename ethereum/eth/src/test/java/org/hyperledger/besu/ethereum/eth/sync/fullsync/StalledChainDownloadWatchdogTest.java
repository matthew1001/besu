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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.eth.manager.ChainHeadEstimate;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The watchdog is the last line of defence for a node that can no longer reach the chain head under
 * its own power, so these tests care as much about it staying quiet as about it firing: an
 * over-eager restart tears down a working download pipeline.
 */
@ExtendWith(MockitoExtension.class)
public class StalledChainDownloadWatchdogTest {

  private static final int STALLED_CHECKS_BEFORE_RESTART = 3;
  private static final long BEHIND_TOLERANCE = 50;

  @Mock private SyncState syncState;
  @Mock private Blockchain blockchain;
  @Mock private EthScheduler scheduler;

  private final AtomicLong localChainHeight = new AtomicLong(1_000);
  private final AtomicBoolean downloadLoopFinished = new AtomicBoolean(false);
  private final AtomicInteger restarts = new AtomicInteger(0);

  private StalledChainDownloadWatchdog watchdog;

  @BeforeEach
  public void setUp() {
    lenient().when(blockchain.getChainHeadBlockNumber()).thenAnswer(_ -> localChainHeight.get());
    lenient().when(syncState.isInitialSyncPhaseDone()).thenReturn(true);
    lenient().when(syncState.hasReachedTerminalDifficulty()).thenReturn(Optional.empty());
    lenient()
        .when(scheduler.scheduleFutureTaskWithFixedDelay(any(), any(), any()))
        .thenReturn(null);

    watchdog =
        new StalledChainDownloadWatchdog(
            syncState,
            blockchain,
            scheduler,
            downloadLoopFinished::get,
            restarts::incrementAndGet,
            Duration.ofSeconds(30),
            STALLED_CHECKS_BEFORE_RESTART,
            BEHIND_TOLERANCE,
            new NoOpMetricsSystem());
    watchdog.start();
  }

  @Test
  public void restartsWhenBehindAndTheChainHeadStopsMoving() {
    bestPeerAt(localChainHeight.get() + 20_000);

    // First check only establishes the baseline height.
    runChecks(1 + STALLED_CHECKS_BEFORE_RESTART - 1);
    assertThat(restarts).hasValue(0);

    watchdog.check();
    assertThat(restarts).hasValue(1);
  }

  @Test
  public void doesNotRestartWhenBehindButStillImporting() {
    bestPeerAt(localChainHeight.get() + 20_000);

    for (int i = 0; i < STALLED_CHECKS_BEFORE_RESTART * 4; i++) {
      localChainHeight.addAndGet(10);
      watchdog.check();
    }

    assertThat(restarts).hasValue(0);
  }

  @Test
  public void doesNotRestartWhenAtTheChainHead() {
    bestPeerAt(localChainHeight.get());

    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  @Test
  public void doesNotRestartWhenOnlyMarginallyBehind() {
    // Ordinary lag on a fast-block chain must not look like a stall.
    bestPeerAt(localChainHeight.get() + BEHIND_TOLERANCE);

    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  @Test
  public void doesNotRestartWhenThereIsNoPeerToCompareAgainst() {
    when(syncState.getBestPeerChainHead()).thenReturn(Optional.empty());

    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  /**
   * With a {@code never()} termination condition the loop should still be running, so a settled
   * future while behind is unambiguous and does not need the full stall window.
   */
  @Test
  public void restartsPromptlyWhenTheDownloadLoopHasFinishedWhileBehind() {
    bestPeerAt(localChainHeight.get() + 20_000);
    downloadLoopFinished.set(true);

    watchdog.check();

    assertThat(restarts).hasValue(1);
  }

  /** Post-merge the loop is meant to be finished and the consensus layer drives the chain. */
  @Test
  public void doesNotRestartOnceTerminalDifficultyIsReached() {
    bestPeerAt(localChainHeight.get() + 20_000);
    downloadLoopFinished.set(true);
    when(syncState.hasReachedTerminalDifficulty()).thenReturn(Optional.of(Boolean.TRUE));

    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  /** During an initial (snap) sync the full-sync chain head is not expected to move. */
  @Test
  public void doesNotRestartDuringTheInitialSyncPhase() {
    bestPeerAt(localChainHeight.get() + 20_000);
    when(syncState.isInitialSyncPhaseDone()).thenReturn(false);

    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  @Test
  public void restartingResetsTheStallWindowSoItDoesNotRestartEveryCheck() {
    bestPeerAt(localChainHeight.get() + 20_000);

    runChecks(1 + STALLED_CHECKS_BEFORE_RESTART);
    assertThat(restarts).hasValue(1);

    // A replacement download loop deserves the same grace period as the original.
    runChecks(STALLED_CHECKS_BEFORE_RESTART - 1);
    assertThat(restarts).hasValue(1);

    watchdog.check();
    assertThat(restarts).hasValue(2);
  }

  @Test
  public void stoppedWatchdogDoesNotRestart() {
    bestPeerAt(localChainHeight.get() + 20_000);
    downloadLoopFinished.set(true);

    watchdog.stop();
    runChecks(STALLED_CHECKS_BEFORE_RESTART * 4);

    assertThat(restarts).hasValue(0);
  }

  /**
   * scheduleWithFixedDelay stops re-scheduling a task that throws, so a check that blows up must
   * not take the watchdog with it.
   */
  @Test
  public void aFailingCheckDoesNotStopTheWatchdog() {
    when(syncState.getBestPeerChainHead()).thenThrow(new IllegalStateException("boom"));

    assertThatCode(() -> watchdog.check()).doesNotThrowAnyException();

    // Still functional once the underlying problem clears.
    org.mockito.Mockito.reset(syncState);
    when(syncState.isInitialSyncPhaseDone()).thenReturn(true);
    when(syncState.hasReachedTerminalDifficulty()).thenReturn(Optional.empty());
    bestPeerAt(localChainHeight.get() + 20_000);
    downloadLoopFinished.set(true);

    watchdog.check();
    assertThat(restarts).hasValue(1);
  }

  private void bestPeerAt(final long height) {
    final ChainHeadEstimate estimate = mock(ChainHeadEstimate.class);
    lenient().when(estimate.getEstimatedHeight()).thenReturn(height);
    lenient().when(syncState.getBestPeerChainHead()).thenReturn(Optional.of(estimate));
  }

  private void runChecks(final int count) {
    for (int i = 0; i < count; i++) {
      watchdog.check();
    }
  }
}
