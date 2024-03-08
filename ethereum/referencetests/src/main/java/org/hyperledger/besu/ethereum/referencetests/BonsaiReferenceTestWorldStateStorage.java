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
package org.hyperledger.besu.ethereum.referencetests;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.BonsaiAccount;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiPreImageProxy;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.storage.BonsaiWorldStateLayerStorage;
import org.hyperledger.besu.ethereum.trie.diffbased.common.worldview.DiffBasedWorldView;
import org.hyperledger.besu.evm.account.AccountStorageEntry;
import org.hyperledger.besu.evm.worldstate.WorldState;
import org.hyperledger.besu.plugin.services.storage.KeyValueStorage;
import org.hyperledger.besu.plugin.services.storage.SnappedKeyValueStorage;

import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.rlp.RLP;
import org.apache.tuweni.units.bigints.UInt256;

public class BonsaiReferenceTestWorldStateStorage extends BonsaiWorldStateLayerStorage {
  private final BonsaiWorldStateKeyValueStorage parent;
  private final BonsaiPreImageProxy preImageProxy;

  public BonsaiReferenceTestWorldStateStorage(
      final BonsaiWorldStateKeyValueStorage parent, final BonsaiPreImageProxy preImageProxy) {
    super(parent);
    this.parent = parent;
    this.preImageProxy = preImageProxy;
  }

  private BonsaiReferenceTestWorldStateStorage(
      final SnappedKeyValueStorage composedWorldStateStorage,
      final KeyValueStorage trieLogStorage,
      final BonsaiWorldStateKeyValueStorage parent,
      final BonsaiPreImageProxy preImageProxy) {
    super(composedWorldStateStorage, trieLogStorage, parent);
    this.parent = parent;
    this.preImageProxy = preImageProxy;
  }

  @Override
  public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(
      final Hash addressHash, final Bytes32 startKeyHash, final int limit) {
    return streamFlatStorages(addressHash, startKeyHash, UInt256.MAX_VALUE, limit)
        .entrySet()
        // map back to slot keys using preImage provider:
        .stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                e ->
                    AccountStorageEntry.create(
                        UInt256.fromBytes(RLP.decodeValue(e.getValue())),
                        Hash.wrap(e.getKey()),
                        preImageProxy.getStorageTrieKeyPreimage(e.getKey())),
                (a, b) -> a,
                TreeMap::new));
  }

  public Stream<WorldState.StreamableAccount> streamAccounts(
      final DiffBasedWorldView context, final Bytes32 startKeyHash, final int limit) {
    return streamFlatAccounts(startKeyHash, UInt256.MAX_VALUE, limit)
        .entrySet()
        // map back to addresses using preImage provider:
        .stream()
        .map(
            entry ->
                preImageProxy
                    .getAccountTrieKeyPreimage(entry.getKey())
                    .map(
                        address ->
                            new WorldState.StreamableAccount(
                                Optional.of(address),
                                BonsaiAccount.fromRLP(context, address, entry.getValue(), false))))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(acct -> context.updater().getAccount(acct.getAddress().orElse(null)) != null)
        .sorted(Comparator.comparing(account -> account.getAddress().orElse(Address.ZERO)));
  }

  @Override
  public BonsaiWorldStateKeyValueStorage getContextSafeCopy() {
    return new BonsaiReferenceTestWorldStateStorage(
        (SnappedKeyValueStorage) composedWorldStateStorage, trieLogStorage, parent, preImageProxy);
  }
}
