/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.consensus.qbft.adaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.ALREADY_IMPORTED;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.IMPORTED;
import static org.hyperledger.besu.ethereum.mainnet.BlockImportResult.BlockImportStatus.NOT_IMPORTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.qbft.adaptor.ValidatedBlockProcessingCache.ValidatedBlockOutputs;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockDataGenerator;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BlockImportResult;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.ethereum.worldstate.WorldStateQueryParams;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QbftBlockImporterAdaptorTest {
  @Mock private BlockImporter blockImporter;
  @Mock private ProtocolContext protocolContext;
  @Mock private MutableBlockchain blockchain;
  @Mock private WorldStateArchive worldStateArchive;
  @Mock private TransactionReceipt receipt;

  private final Block besuBlock = new BlockDataGenerator().block();
  private final QbftBlock block = new QbftBlockAdaptor(besuBlock);
  private final ValidatedBlockProcessingCache validatedBlockCache =
      new ValidatedBlockProcessingCache();

  @Test
  void importsBlockSuccessfullyWhenBesuBlockImports() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);
  }

  @Test
  void importsBlockSuccessfullyWhenBesuBlockAlreadyImported() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(ALREADY_IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);
  }

  @Test
  void importsBlockFailsWhenBesuBlockNotImported() {
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(NOT_IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(false);
  }

  @Test
  void importsBlockFromCachedValidationOutputsWithoutReexecuting() {
    final List<TransactionReceipt> receipts = List.of(receipt);
    validatedBlockCache.put(
        besuBlock.getHash(), new ValidatedBlockOutputs(receipts, Optional.empty()));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    when(blockchain.contains(besuBlock.getHash())).thenReturn(false);
    when(worldStateArchive.isWorldStateAvailable(
            besuBlock.getHeader().getStateRoot(), besuBlock.getHash()))
        .thenReturn(true);

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);

    verify(blockchain).appendBlock(besuBlock, receipts, Optional.empty());
    final ArgumentCaptor<WorldStateQueryParams> paramsCaptor =
        ArgumentCaptor.forClass(WorldStateQueryParams.class);
    verify(worldStateArchive).getWorldState(paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().getBlockHeader()).isEqualTo(besuBlock.getHeader());
    assertThat(paramsCaptor.getValue().shouldWorldStateUpdateHead()).isTrue();
    verifyNoInteractions(blockImporter);
  }

  @Test
  void importsBlockAlreadyOnChainWithoutReexecutingOrReimporting() {
    validatedBlockCache.put(
        besuBlock.getHash(), new ValidatedBlockOutputs(List.of(receipt), Optional.empty()));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(blockchain.contains(besuBlock.getHash())).thenReturn(true);

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);

    verify(blockchain, never()).appendBlock(any(), any(), any());
    verifyNoInteractions(blockImporter);
  }

  @Test
  void fastPathImportWaitsForTheImporterMonitorHeldByAConcurrentImport() throws Exception {
    validatedBlockCache.put(
        besuBlock.getHash(), new ValidatedBlockOutputs(List.of(receipt), Optional.empty()));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    when(blockchain.contains(besuBlock.getHash())).thenReturn(false);
    when(worldStateArchive.isWorldStateAvailable(
            besuBlock.getHeader().getStateRoot(), besuBlock.getHash()))
        .thenReturn(true);

    final java.util.concurrent.CountDownLatch monitorHeld =
        new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch releaseMonitor =
        new java.util.concurrent.CountDownLatch(1);
    final BlockImporter importerMonitor = blockImporter;
    final Thread monitorHolder =
        new Thread(
            () -> {
              synchronized (importerMonitor) {
                monitorHeld.countDown();
                try {
                  releaseMonitor.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    monitorHolder.start();
    assertThat(monitorHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    final QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    final java.util.concurrent.atomic.AtomicBoolean importResult =
        new java.util.concurrent.atomic.AtomicBoolean();
    final Thread importThread =
        new Thread(() -> importResult.set(qbftBlockImporter.importBlock(block, Optional.empty())));
    importThread.start();

    // the import must block on the monitor held above, before appending anything
    final long deadline = System.currentTimeMillis() + 5_000;
    while (importThread.getState() != Thread.State.BLOCKED
        && System.currentTimeMillis() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(importThread.getState()).isEqualTo(Thread.State.BLOCKED);
    verify(blockchain, never()).appendBlock(any(), any(), any());

    releaseMonitor.countDown();
    importThread.join(5_000);
    monitorHolder.join(5_000);
    assertThat(importResult.get()).isTrue();
    verify(blockchain).appendBlock(besuBlock, List.of(receipt), Optional.empty());
  }

  @Test
  void fallsBackToFullImportWhenValidatedWorldStateUnavailable() {
    validatedBlockCache.put(
        besuBlock.getHash(), new ValidatedBlockOutputs(List.of(receipt), Optional.empty()));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(worldStateArchive);
    when(blockchain.contains(besuBlock.getHash())).thenReturn(false);
    when(worldStateArchive.isWorldStateAvailable(
            besuBlock.getHeader().getStateRoot(), besuBlock.getHash()))
        .thenReturn(false);
    when(blockImporter.importBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            Optional.empty()))
        .thenReturn(new BlockImportResult(IMPORTED));

    QbftBlockImporterAdaptor qbftBlockImporter =
        new QbftBlockImporterAdaptor(blockImporter, protocolContext, validatedBlockCache);
    assertThat(qbftBlockImporter.importBlock(block, Optional.empty())).isEqualTo(true);

    verify(blockchain, never()).appendBlock(any(), any(), any());
  }
}
