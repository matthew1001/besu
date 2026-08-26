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
package org.hyperledger.besu.consensus.common;

import java.util.Comparator;
import java.util.Objects;

/**
 * The Fork spec.
 *
 * @param <C> the type parameter
 */
public class ForkSpec<C> {

  /** The constant COMPARATOR. */
  public static final Comparator<ForkSpec<?>> COMPARATOR = Comparator.comparing(ForkSpec::getBlock);

  /** Fork schedule type (block or time) */
  public enum ForkScheduleType {
    /** Block fork type (i.e pre-Shanghai) */
    BLOCK,
    /** Time fork type (i.e. Shanghai and beyond) */
    TIME
  }

  /**
   * We use a pragmatic approach to determine whether a transition is a timestamp or a block number.
   * Future iterations on genesis structure may introduce a more deterministic differentiation
   * between them but existing genesis files need supporting for the time being. This threshold is
   * the timestamp of the shanghai epoch, the first timestamp-based EVM spec.
   */
  public static final long TIMESTAMP_THRESHOLD = 1_681_338_455L;

  private final long block;
  private ForkScheduleType forkType = ForkScheduleType.BLOCK; // Default type
  private final C value;

  /**
   * Instantiates a new Fork spec.
   *
   * @param block the block
   * @param value the value
   */
  public ForkSpec(final long block, final C value) {
    this.block = block;
    this.value = value;
  }

  /**
   * Gets block.
   *
   * @return the block
   */
  public long getBlock() {
    return block;
  }

  /**
   * Gets the fork type (block number or timestamp).
   *
   * @param forkType the fork type
   */
  public void setForkType(final ForkScheduleType forkType) {
    this.forkType = forkType;
  }

  /**
   * Gets the fork type (block number or timestamp).
   *
   * @return the fork type
   */
  public ForkScheduleType getForkType() {
    return forkType;
  }

  /**
   * Simple classification of a transition value as block-based or timestamp-based purely by
   * magnitude.
   *
   * @param blockNumberOrTimestamp the raw transition value
   * @return TIME if the value is at or above the threshold, otherwise BLOCK
   */
  public static ForkScheduleType scheduleTypeFor(final long blockNumberOrTimestamp) {
    return blockNumberOrTimestamp >= TIMESTAMP_THRESHOLD
        ? ForkScheduleType.TIME
        : ForkScheduleType.BLOCK;
  }

  /**
   * Gets value.
   *
   * @return the value
   */
  public C getValue() {
    return value;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final ForkSpec<?> that = (ForkSpec<?>) o;
    return block == that.block && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(block, value);
  }
}
