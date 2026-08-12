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

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.BlockchainSetupUtil;
import org.hyperledger.besu.ethereum.eth.EthProtocolConfiguration;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestBuilder;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.RespondingEthPeer;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.TrailingPeerRequirements;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.metrics.SyncDurationMetrics;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.data.SyncStatus;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class FullSyncDownloaderTest {

  protected ProtocolSchedule protocolSchedule;
  protected EthProtocolManager ethProtocolManager;
  protected EthContext ethContext;
  protected ProtocolContext protocolContext;
  private SyncState syncState;

  private BlockchainSetupUtil localBlockchainSetup;
  protected MutableBlockchain localBlockchain;
  private final MetricsSystem metricsSystem = new NoOpMetricsSystem();

  static class FullSyncDownloaderTestArguments implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
      return Stream.of(
          Arguments.of(DataStorageFormat.BONSAI), Arguments.of(DataStorageFormat.FOREST));
    }
  }

  public void setupTest(final DataStorageFormat storageFormat) {
    localBlockchainSetup = BlockchainSetupUtil.forTesting(storageFormat);
    localBlockchain = localBlockchainSetup.getBlockchain();

    protocolSchedule = localBlockchainSetup.getProtocolSchedule();
    protocolContext = localBlockchainSetup.getProtocolContext();
    ethProtocolManager =
        EthProtocolManagerTestBuilder.builder()
            .setProtocolSchedule(protocolSchedule)
            .setBlockchain(localBlockchain)
            .setEthScheduler(new EthScheduler(1, 1, 1, 1, new NoOpMetricsSystem()))
            .setWorldStateArchive(localBlockchainSetup.getWorldArchive())
            .setTransactionPool(localBlockchainSetup.getTransactionPool())
            .setEthereumWireProtocolConfiguration(EthProtocolConfiguration.DEFAULT)
            .build();
    ethContext = ethProtocolManager.ethContext();
    syncState = new SyncState(protocolContext.getBlockchain(), ethContext.getEthPeers());
  }

  @AfterEach
  public void tearDown() {
    if (ethProtocolManager != null) {
      ethProtocolManager.stop();
    }
  }

  private FullSyncDownloader downloader(final SynchronizerConfiguration syncConfig) {
    return new FullSyncDownloader(
        syncConfig,
        protocolSchedule,
        protocolContext,
        ethContext,
        syncState,
        metricsSystem,
        SyncTerminationCondition.never(),
        null,
        SyncDurationMetrics.NO_OP_SYNC_DURATION_METRICS);
  }

  @ParameterizedTest
  @ArgumentsSource(FullSyncDownloaderTestArguments.class)
  public void shouldLimitTrailingPeersWhenBehindChain(final DataStorageFormat storageFormat) {
    setupTest(storageFormat);
    localBlockchainSetup.importFirstBlocks(2);
    final int maxTailingPeers = 5;
    final FullSyncDownloader synchronizer =
        downloader(SynchronizerConfiguration.builder().maxTrailingPeers(maxTailingPeers).build());

    final RespondingEthPeer bestPeer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 100);
    syncState.setSyncTarget(bestPeer.getEthPeer(), localBlockchain.getChainHeadHeader());

    final TrailingPeerRequirements expected =
        new TrailingPeerRequirements(localBlockchain.getChainHeadBlockNumber(), maxTailingPeers);
    assertThat(synchronizer.calculateTrailingPeerRequirements()).isEqualTo(expected);
  }

  @ParameterizedTest
  @ArgumentsSource(FullSyncDownloaderTestArguments.class)
  public void shouldNotLimitTrailingPeersWhenInSync(final DataStorageFormat storageFormat) {
    setupTest(storageFormat);
    localBlockchainSetup.importFirstBlocks(2);
    final int maxTailingPeers = 5;
    final FullSyncDownloader synchronizer =
        downloader(SynchronizerConfiguration.builder().maxTrailingPeers(maxTailingPeers).build());

    final RespondingEthPeer bestPeer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 2);
    syncState.setSyncTarget(bestPeer.getEthPeer(), localBlockchain.getChainHeadHeader());

    assertThat(synchronizer.calculateTrailingPeerRequirements())
        .isEqualTo(TrailingPeerRequirements.UNRESTRICTED);
  }

  /**
   * What the watchdog invokes when a download loop has stopped making progress. ChainDownloader is
   * single-shot -- start() throws on a second call -- so the restart has to build a replacement,
   * and it has to release the sync target: consumers gate on it to decide whether the node is
   * syncing (the BFT mining coordinator stops mining while one is set) and would otherwise stay
   * pinned to a download that no longer exists.
   */
  @ParameterizedTest
  @ArgumentsSource(FullSyncDownloaderTestArguments.class)
  public void restartingTheDownloadReleasesTheSyncTargetAndBuildsAReplacement(
      final DataStorageFormat storageFormat) {
    setupTest(storageFormat);
    localBlockchainSetup.importFirstBlocks(2);
    final FullSyncDownloader synchronizer = downloader(SynchronizerConfiguration.builder().build());

    final List<Optional<SyncStatus>> publishedStatuses = new CopyOnWriteArrayList<>();
    syncState.subscribeSyncStatus(publishedStatuses::add);

    // A peer level with us, so the replacement loop parks in its "caught up" wait rather than
    // starting a real download pipeline.
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 2);
    syncState.setSyncTarget(peer.getEthPeer(), localBlockchain.getChainHeadHeader());
    assertThat(syncState.syncTarget()).isPresent();

    assertThat(synchronizer.currentDownloadFuture()).isNull();

    synchronizer.restartChainDownload();
    final CompletableFuture<Void> firstReplacement = synchronizer.currentDownloadFuture();
    // Non-null only if the replacement's start() returned normally.
    assertThat(firstReplacement).isNotNull();
    assertThat(publishedStatuses).contains(Optional.empty());

    // Restarting again must build another downloader: reusing the previous one would throw
    // "Cannot start a chain download twice" and leave the recorded future untouched.
    syncState.setSyncTarget(peer.getEthPeer(), localBlockchain.getChainHeadHeader());
    synchronizer.restartChainDownload();
    assertThat(synchronizer.currentDownloadFuture()).isNotNull().isNotSameAs(firstReplacement);

    synchronizer.stop();
  }

  /** After stop() the watchdog must not resurrect the download loop. */
  @ParameterizedTest
  @ArgumentsSource(FullSyncDownloaderTestArguments.class)
  public void restartIsIgnoredOnceStopped(final DataStorageFormat storageFormat) {
    setupTest(storageFormat);
    localBlockchainSetup.importFirstBlocks(2);
    final FullSyncDownloader synchronizer = downloader(SynchronizerConfiguration.builder().build());

    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 2);
    syncState.setSyncTarget(peer.getEthPeer(), localBlockchain.getChainHeadHeader());

    synchronizer.stop();
    synchronizer.restartChainDownload();

    // The target set above is untouched and no loop was started: nothing was resurrected.
    assertThat(syncState.syncTarget()).isPresent();
    assertThat(synchronizer.currentDownloadFuture()).isNull();
  }
}
