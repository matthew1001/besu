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

import org.hyperledger.besu.consensus.qbft.adaptor.ValidatedBlockProcessingCache.ValidatedBlockOutputs;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlock;
import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockImporter;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockImporter;
import org.hyperledger.besu.ethereum.mainnet.BlockImportResult;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.worldstate.WorldStateQueryParams;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adaptor class to allow a {@link BlockImporter} to be used as a {@link QbftBlockImporter}. */
public class QbftBlockImporterAdaptor implements QbftBlockImporter {

  private static final Logger LOG = LoggerFactory.getLogger(QbftBlockImporterAdaptor.class);

  private final BlockImporter blockImporter;
  private final ProtocolContext context;
  private final ValidatedBlockProcessingCache validatedBlockCache;

  /**
   * Constructs a new Qbft block importer.
   *
   * @param blockImporter The Besu block importer
   * @param context The protocol context
   * @param validatedBlockCache Cache of processing outputs for blocks this node already executed
   *     when it validated the proposal, allowing the import to skip re-execution
   */
  public QbftBlockImporterAdaptor(
      final BlockImporter blockImporter,
      final ProtocolContext context,
      final ValidatedBlockProcessingCache validatedBlockCache) {
    this.blockImporter = blockImporter;
    this.context = context;
    this.validatedBlockCache = validatedBlockCache;
  }

  @Override
  public boolean importBlock(
      final QbftBlock block, final Optional<BlockAccessList> blockAccessList) {
    final Block besuBlock = AdaptorUtil.toBesuBlock(block);
    if (importWithoutReexecution(besuBlock)) {
      return true;
    }
    final BlockImportResult blockImportResult =
        blockImporter.importBlock(
            context,
            besuBlock,
            HeaderValidationMode.FULL,
            HeaderValidationMode.FULL,
            blockAccessList);
    return blockImportResult.isImported();
  }

  /**
   * Appends a block that this node fully executed when it validated the proposal, skipping the
   * re-execution a full import would perform. The commit seals added since then are excluded from
   * the block hash, so the hash still identifies the executed block. This is only possible while
   * the world state persisted by that first execution remains available under this block's hash;
   * otherwise the caller must fall back to the full, re-executing import.
   *
   * @param block the sealed block to import
   * @return true if the block was imported from the cached outputs, false if the full import must
   *     run
   */
  private boolean importWithoutReexecution(final Block block) {
    final Optional<ValidatedBlockOutputs> cached = validatedBlockCache.get(block.getHash());
    if (cached.isEmpty()) {
      return false;
    }

    // The same block can arrive concurrently via gossip, imported on an EthScheduler thread
    // through the same (synchronized) BlockImporter this adaptor wraps. Rolling the head world
    // state is only safe for one importer at a time, so hold the importer's monitor for the
    // append-and-roll, and re-check the chain inside it: whichever import loses the race must
    // return without touching the head world state.
    synchronized (blockImporter) {
      final MutableBlockchain blockchain = context.getBlockchain();
      if (blockchain.contains(block.getHash())) {
        return true;
      }
      if (!context
          .getWorldStateArchive()
          .isWorldStateAvailable(block.getHeader().getStateRoot(), block.getHash())) {
        return false;
      }

      blockchain.appendBlock(block, cached.get().receipts(), cached.get().blockAccessList());

      // Move the head world state to the new block, as MainnetBlockImporter does after
      // processing. No transactions run here: the archive rolls forward using the trie log
      // persisted when the proposal was validated.
      context
          .getWorldStateArchive()
          .getWorldState(
              WorldStateQueryParams.newBuilder()
                  .withBlockHeader(block.getHeader())
                  .withShouldWorldStateUpdateHead(true)
                  .build());
    }

    LOG.debug(
        "Imported block {} reusing the processing outputs of its proposal validation",
        block.getHash());
    return true;
  }
}
