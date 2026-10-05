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
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.qbft.core.types.QbftBlockValidator;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.BlockValidator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QbftBlockValidatorAdaptorTest {
  @Mock private BlockValidator blockValidator;
  @Mock private ProtocolContext protocolContext;
  @Mock private Block besuBlock;
  @Mock private QbftBlockAdaptor qbftBlock;
  @Mock private BlockProcessingOutputs processingOutputs;
  @Mock private TransactionReceipt receipt;

  private final ValidatedBlockProcessingCache validatedBlockCache =
      new ValidatedBlockProcessingCache();

  @Test
  void validateSuccessfullyWhenBesuValidatorSuccessful() {
    when(qbftBlock.getBesuBlock()).thenReturn(besuBlock);
    when(blockValidator.validateAndProcessBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.LIGHT,
            HeaderValidationMode.FULL,
            Optional.empty(),
            false))
        .thenReturn(new BlockProcessingResult(Optional.empty()));

    QbftBlockValidatorAdaptor qbftBlockValidator =
        new QbftBlockValidatorAdaptor(blockValidator, protocolContext, validatedBlockCache);
    QbftBlockValidator.ValidationResult validationResult =
        qbftBlockValidator.validateBlock(qbftBlock, Optional.empty());
    assertThat(validationResult.success()).isTrue();
    assertThat(validationResult.errorMessage()).isEmpty();
  }

  @Test
  void successfulValidationCachesProcessingOutputsByBlockHash() {
    final Hash blockHash = Hash.hash(org.apache.tuweni.bytes.Bytes.of(1));
    final List<TransactionReceipt> receipts = List.of(receipt);
    when(qbftBlock.getBesuBlock()).thenReturn(besuBlock);
    when(besuBlock.getHash()).thenReturn(blockHash);
    when(processingOutputs.getReceipts()).thenReturn(receipts);
    when(processingOutputs.getBlockAccessList()).thenReturn(Optional.empty());
    when(blockValidator.validateAndProcessBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.LIGHT,
            HeaderValidationMode.FULL,
            Optional.empty(),
            false))
        .thenReturn(new BlockProcessingResult(Optional.of(processingOutputs)));

    QbftBlockValidatorAdaptor qbftBlockValidator =
        new QbftBlockValidatorAdaptor(blockValidator, protocolContext, validatedBlockCache);
    QbftBlockValidator.ValidationResult validationResult =
        qbftBlockValidator.validateBlock(qbftBlock, Optional.empty());

    assertThat(validationResult.success()).isTrue();
    assertThat(validatedBlockCache.get(blockHash))
        .hasValueSatisfying(
            outputs -> {
              assertThat(outputs.receipts()).isEqualTo(receipts);
              assertThat(outputs.blockAccessList()).isEmpty();
            });
  }

  @Test
  void validateFailsWhenBesuValidatorFails() {
    when(qbftBlock.getBesuBlock()).thenReturn(besuBlock);
    when(blockValidator.validateAndProcessBlock(
            protocolContext,
            besuBlock,
            HeaderValidationMode.LIGHT,
            HeaderValidationMode.FULL,
            Optional.empty(),
            false))
        .thenReturn(new BlockProcessingResult("failed"));

    QbftBlockValidatorAdaptor qbftBlockValidator =
        new QbftBlockValidatorAdaptor(blockValidator, protocolContext, validatedBlockCache);
    QbftBlockValidator.ValidationResult validationResult =
        qbftBlockValidator.validateBlock(qbftBlock, Optional.empty());
    assertThat(validationResult.success()).isFalse();
    assertThat(validationResult.errorMessage()).contains("failed");
  }
}
