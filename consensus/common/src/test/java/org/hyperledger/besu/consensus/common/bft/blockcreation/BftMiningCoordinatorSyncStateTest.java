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
package org.hyperledger.besu.consensus.common.bft.blockcreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.hyperledger.besu.consensus.common.bft.BftEventQueue;
import org.hyperledger.besu.consensus.common.bft.BftExecutors;
import org.hyperledger.besu.consensus.common.bft.BftProcessor;
import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier;
import org.hyperledger.besu.consensus.common.bft.EventMultiplexer;
import org.hyperledger.besu.consensus.common.bft.events.BftEvent;
import org.hyperledger.besu.consensus.common.bft.events.BlockTimerExpiry;
import org.hyperledger.besu.consensus.common.bft.statemachine.BftEventHandler;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator.BlockOptions;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.eth.manager.ChainState;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the coordinator's sync-state driven lifecycle: the transitions it performs in response to
 * {@link SyncState} notifications must never run on the notifying thread, must converge on the last
 * requested state whatever order notifications arrive in, and must not let a failure escape into
 * the publisher.
 *
 * <p>The regression these guard against wedged QBFT validators until they were restarted. SyncState
 * published sync-status changes while holding its own monitor, and the coordinator stopped mining
 * inline from that callback -- blocking in {@link BftProcessor#awaitStop()} until the BFT event
 * thread left its dispatch loop. That event thread imports blocks, which fires the block-added
 * observers inline, one of which is SyncState's own observer calling the synchronized {@code
 * checkInSync()}. Publisher waits for event thread, event thread waits for the publisher's monitor:
 * consensus stops and the chain-download loop can never set or clear a sync target again.
 */
@ExtendWith(MockitoExtension.class)
public class BftMiningCoordinatorSyncStateTest {

  @Mock private BftEventHandler eventHandler;
  @Mock private BftBlockCreatorFactory<?> blockCreatorFactory;
  @Mock private EthPeers ethPeers;

  private final BlockDataGenerator gen = new BlockDataGenerator(1);
  private MutableBlockchain blockchain;
  private SyncState syncState;
  private EthPeer syncTargetPeer;
  private BftEventQueue eventQueue;
  private BftExecutors bftExecutors;
  private BftMiningCoordinator coordinator;

  @BeforeEach
  public void setUp() {
    final Block genesisBlock = gen.genesisBlock(new BlockOptions().setDifficulty(Difficulty.ZERO));
    blockchain = InMemoryKeyValueStorageProvider.createInMemoryBlockchain(genesisBlock);

    lenient().when(ethPeers.subscribeConnect(any())).thenReturn(1L);
    lenient().when(ethPeers.bestPeerWithHeightEstimate()).thenReturn(Optional.empty());

    // hasInitialSyncPhase = false, so isInitialSyncPhaseDone() is true from the start and the
    // desired mining state is driven purely by whether a sync target is set.
    syncState = new SyncState(blockchain, ethPeers);

    syncTargetPeer = mock(EthPeer.class);
    lenient().when(syncTargetPeer.chainState()).thenReturn(new ChainState());

    eventQueue = new BftEventQueue(1000);
    bftExecutors = BftExecutors.create(new NoOpMetricsSystem(), BftExecutors.ConsensusType.QBFT);
  }

  @AfterEach
  public void tearDown() throws InterruptedException {
    // Stop the executors first: awaitStop() on a still-running BftExecutors waits out its full
    // 30s shutdown timeout.
    bftExecutors.stop();
    if (coordinator != null) {
      coordinator.awaitStop();
    } else {
      bftExecutors.awaitStop();
    }
  }

  /**
   * The deadlock regression. The BFT event thread is busy importing blocks (which takes SyncState's
   * monitor via its block-added observer) while a sync target is set on another thread. Before the
   * fix, {@code setSyncTarget} never returned.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void settingASyncTargetWhileTheEventThreadImportsBlocksDoesNotDeadlock() throws Exception {
    eventQueue.start();
    final AtomicBoolean keepImporting = new AtomicBoolean(true);
    final CountDownLatch importing = new CountDownLatch(1);

    // Stands in for QBFT importing its own sealed blocks inline on the event thread. Each append
    // fires the block-added observers, including SyncState's synchronized checkInSync().
    final EventMultiplexer eventMultiplexer =
        new EventMultiplexer(eventHandler) {
          @Override
          public void handleBftEvent(final BftEvent bftEvent) {
            importing.countDown();
            while (keepImporting.get()) {
              appendBlock();
            }
          }
        };

    final BftProcessor bftProcessor = new BftProcessor(eventQueue, eventMultiplexer);
    createCoordinator(bftProcessor);
    coordinator.subscribe();
    coordinator.enable();
    coordinator.start();

    eventQueue.add(new BlockTimerExpiry(new ConsensusRoundIdentifier(1, 0)));
    assertThat(importing.await(10, TimeUnit.SECONDS)).isTrue();

    // Run the publish on its own thread so a regression fails the assertion promptly instead of
    // hanging this test until the @Timeout fires.
    final CountDownLatch published = new CountDownLatch(1);
    final Thread publisher =
        new Thread(
            () -> {
              syncState.setSyncTarget(syncTargetPeer, blockchain.getChainHeadHeader());
              published.countDown();
            },
            "test-sync-target-publisher");
    publisher.setDaemon(true);
    publisher.start();

    assertThat(published.await(5, TimeUnit.SECONDS))
        .withFailMessage(
            "setSyncTarget() did not return: a sync-status listener is blocking the publishing "
                + "thread, most likely while SyncState's monitor is held")
        .isTrue();

    // And the coordinator must actually have acted on it, off the publisher's thread.
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !coordinator.isMining());

    keepImporting.set(false);
  }

  /** Notifications must hand off rather than perform the (blocking) transition inline. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void syncStatusNotificationDoesNotBlockThePublishingThread() throws Exception {
    final BftProcessor bftProcessor = mock(BftProcessor.class);
    // A slow teardown: this is what used to be paid by the publishing thread.
    lenient()
        .when(bftProcessor.awaitStop())
        .thenAnswer(
            _ -> {
              Thread.sleep(3_000);
              return true;
            });

    createCoordinator(bftProcessor);
    coordinator.subscribe();
    coordinator.enable();
    coordinator.start();

    final long startedAt = System.nanoTime();
    syncState.setSyncTarget(syncTargetPeer, blockchain.getChainHeadHeader());
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(elapsed)
        .withFailMessage(
            "setSyncTarget() took %s: the coordinator is performing its teardown on the "
                + "publishing thread",
            elapsed)
        .isLessThan(Duration.ofSeconds(1));

    // The transition still happens, just not inline.
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !coordinator.isMining());
  }

  /**
   * The property that makes ordering irrelevant: whatever sequence of notifications arrives, the
   * coordinator settles on the state the last one asked for.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void coordinatorSettlesOnTheLastRequestedStateWhenNotificationsInterleave()
      throws Exception {
    final BftProcessor bftProcessor = mock(BftProcessor.class);
    lenient().when(bftProcessor.awaitStop()).thenReturn(true);

    createCoordinator(bftProcessor);
    coordinator.subscribe();
    coordinator.enable();
    coordinator.start();

    final BlockHeader commonAncestor = blockchain.getChainHeadHeader();
    for (int i = 0; i < 25; i++) {
      syncState.setSyncTarget(syncTargetPeer, commonAncestor);
      syncState.clearSyncTarget();
    }

    // Last notification was "no sync target", i.e. mine.
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(coordinator::isMining);

    syncState.setSyncTarget(syncTargetPeer, commonAncestor);
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !coordinator.isMining());
  }

  /** A notification for a state we are already in must not churn the processor. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void repeatedInSyncNotificationsDoNotRestartTheProcessor() throws Exception {
    final BftProcessor bftProcessor = mock(BftProcessor.class);
    lenient().when(bftProcessor.awaitStop()).thenReturn(true);

    createCoordinator(bftProcessor);
    coordinator.subscribe();
    coordinator.enable();
    coordinator.start();
    verify(bftProcessor).start();

    // setSyncProgress publishes a sync-status change without changing the sync target, so the
    // coordinator is told "in sync" repeatedly while it is already mining.
    for (int i = 0; i < 10; i++) {
      syncState.setSyncProgress(0, i, 100);
    }

    // Let every queued reconcile run, then assert nothing churned.
    Awaitility.await()
        .pollDelay(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(coordinator.isMining()).isTrue());
    verify(bftProcessor, never()).stop();
    // Still exactly the one start() from the explicit start() above.
    verify(bftProcessor).start();
  }

  /**
   * A failing transition must stay inside the coordinator. It used to propagate out of {@code
   * Subscribers.forEach} (created without suppressCallbackExceptions) into the caller -- for {@code
   * clearSyncTarget} that is PipelineChainDownloader.repeatUnlessDownloadComplete, which is not
   * covered by its own exceptionallyCompose, so the download loop died silently and permanently.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void aFailingTransitionDoesNotPropagateToThePublisher() throws Exception {
    final BftProcessor bftProcessor = mock(BftProcessor.class);
    lenient().doThrow(new IllegalStateException("boom")).when(bftProcessor).start();

    createCoordinator(bftProcessor);
    coordinator.subscribe();

    assertThatCode(() -> syncState.clearSyncTarget()).doesNotThrowAnyException();
    // Publishing an actual transition (empty -> present -> empty) also has to stay contained.
    syncState.setSyncTarget(syncTargetPeer, blockchain.getChainHeadHeader());
    assertThatCode(() -> syncState.clearSyncTarget()).doesNotThrowAnyException();
  }

  private void createCoordinator(final BftProcessor bftProcessor) {
    coordinator =
        new BftMiningCoordinator(
            bftExecutors,
            eventHandler,
            bftProcessor,
            blockCreatorFactory,
            blockchain,
            eventQueue,
            syncState);
  }

  private void appendBlock() {
    final BlockHeader parent = blockchain.getChainHeadHeader();
    final Block block =
        gen.block(
            BlockOptions.create()
                .setDifficulty(Difficulty.ONE)
                .setParentHash(parent.getHash())
                .setBlockNumber(parent.getNumber() + 1L)
                .transactionCount(0));
    final List<TransactionReceipt> receipts = gen.receipts(block);
    blockchain.appendBlock(block, receipts);
  }
}
