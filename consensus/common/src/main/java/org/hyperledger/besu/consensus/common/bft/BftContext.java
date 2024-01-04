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
package org.hyperledger.besu.consensus.common.bft;

import org.hyperledger.besu.consensus.common.EpochManager;
import org.hyperledger.besu.consensus.common.PoaContext;
import org.hyperledger.besu.consensus.common.validator.ValidatorProvider;
import org.hyperledger.besu.ethereum.ConsensusContext;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;

/** Holds the BFT specific mutable state. */
public class BftContext implements PoaContext {

  private final ValidatorProvider validatorProvider;
  private final EpochManager epochManager;
  private final BftBlockInterface blockInterface;
  private final ProtocolSchedule protocolSchedule;

  /**
   * Instantiates a new Bft context.
   *
   * @param validatorProvider the validator provider
   * @param epochManager the epoch manager
   * @param blockInterface the block interface
   * @param protocolSchedule the protocol schedule
   */
  public BftContext(
      final ValidatorProvider validatorProvider,
      final EpochManager epochManager,
      final BftBlockInterface blockInterface,
      final ProtocolSchedule protocolSchedule) {
    this.validatorProvider = validatorProvider;
    this.epochManager = epochManager;
    this.blockInterface = blockInterface;
    this.protocolSchedule = protocolSchedule;
  }

  /**
   * Gets validator provider.
   *
   * @return the validator provider
   */
  public ValidatorProvider getValidatorProvider() {
    return validatorProvider;
  }

  /**
   * Gets epoch manager.
   *
   * @return the epoch manager
   */
  public EpochManager getEpochManager() {
    return epochManager;
  }

  /**
   * Gets the protocol schedule.
   *
   * @return the protocol schedule
   */
  public ProtocolSchedule getProtocolSchedule() {
    return protocolSchedule;
  }

  @Override
  public BftBlockInterface getBlockInterface() {
    return blockInterface;
  }

  @Override
  public <C extends ConsensusContext> C as(final Class<C> klass) {
    return klass.cast(this);
  }
}
