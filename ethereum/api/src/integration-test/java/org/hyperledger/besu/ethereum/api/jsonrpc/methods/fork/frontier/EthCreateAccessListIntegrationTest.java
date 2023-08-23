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
package org.hyperledger.besu.ethereum.api.jsonrpc.methods.fork.frontier;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.jsonrpc.BlockchainImporter;
import org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcTestMethodsFactory;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonCallParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.CreateAccessListResult;
import org.hyperledger.besu.testutil.BlockTestUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EthCreateAccessListIntegrationTest {

  private static JsonRpcTestMethodsFactory BLOCKCHAIN;

  private JsonRpcMethod method;

  @BeforeAll
  public static void setUpOnce() throws Exception {
    final String genesisJson =
        Resources.toString(BlockTestUtil.getEthRefTestResources().getGenesisURL(), Charsets.UTF_8);

    BLOCKCHAIN =
        new JsonRpcTestMethodsFactory(
            new BlockchainImporter(
                BlockTestUtil.getEthRefTestResources().getBlocksURL(), genesisJson));
  }

  @BeforeEach
  public void setUp() {
    final Map<String, JsonRpcMethod> methods = BLOCKCHAIN.methods();
    method = methods.get("eth_createAccessList");
  }

  @Test
  public void shouldSucceedWhenCreateAccessListMultipleReads() {
    final long expectedGasUsed = 0x6b0e;
    final List<AccessListEntry> expectedAccessListEntryList =
        List.of(
            new AccessListEntry(
                Address.fromHexString("0xbb00000000000000000000000000000000000000"),
                List.of(
                    UInt256.fromHexString(
                        "0x0000000000000000000000000000000000000000000000000000000000000001"),
                    UInt256.fromHexString(
                        "0x0000000000000000000000000000000000000000000000000000000000000003"))));

    final JsonCallParameter callParameter =
        createAccessListJsonCallParameters(
            "0x658bdf435d810c91414ec09147daa6db62406379",
            "0xbb00000000000000000000000000000000000000",
            null);

    assertAccessListExpectedResult(callParameter, expectedAccessListEntryList, expectedGasUsed);
  }

  @Test
  public void shouldSucceedWhenCreateAccessListMultipleReads_withAccessListParam() {
    final long expectedGasUsed = 0x6b0e;
    final List<AccessListEntry> expectedAccessListEntryList =
        List.of(
            new AccessListEntry(
                Address.fromHexString("0xbb00000000000000000000000000000000000000"),
                List.of(
                    UInt256.fromHexString(
                        "0x0000000000000000000000000000000000000000000000000000000000000001"),
                    UInt256.fromHexString(
                        "0x0000000000000000000000000000000000000000000000000000000000000003"))));

    final JsonCallParameter callParameter =
        createAccessListJsonCallParameters(
            "0x658bdf435d810c91414ec09147daa6db62406379",
            "0xbb00000000000000000000000000000000000000",
            expectedAccessListEntryList);

    assertAccessListExpectedResult(callParameter, expectedAccessListEntryList, expectedGasUsed);
  }

  @Test
  public void shouldSucceedWhenCreateAccessListSimpleTransfer() {
    final long expectedGasUsed = 0x5208;
    final List<AccessListEntry> expectedAccessListEntryList = new ArrayList<>();

    final JsonCallParameter callParameter =
        createAccessListJsonCallParameters(
            "0x658bdf435d810c91414ec09147daa6db62406379",
            "0x0100000000000000000000000000000000000000",
            expectedAccessListEntryList);

    assertAccessListExpectedResult(callParameter, expectedAccessListEntryList, expectedGasUsed);
  }

  @Test
  public void shouldSucceedWhenCreateAccessListSimpleContract() {
    final long expectedGasUsed = 0x520b;
    final List<AccessListEntry> expectedAccessListEntryList = new ArrayList<>();

    final JsonCallParameter callParameter =
        createAccessListJsonCallParameters(
            "0x658bdf435d810c91414ec09147daa6db62406379",
            "0xaa00000000000000000000000000000000000000",
            null);

    assertAccessListExpectedResult(callParameter, expectedAccessListEntryList, expectedGasUsed);
  }

  @Test
  public void shouldReturnExpectedValueForEmptyCallParameter() {
    final JsonCallParameter callParameter =
        new JsonCallParameter(null, null, null, null, null, null, null, null, null, null);
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(new ArrayList<>(), 0xcf08));

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  @Test
  public void shouldReturnExpectedValueForTransfer() {
    final JsonCallParameter callParameter =
        new JsonCallParameter(
            Address.fromHexString("0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b"),
            Address.fromHexString("0x8888f1f195afa192cfee860698584c030f4c9db1"),
            null,
            null,
            null,
            null,
            Wei.ZERO,
            null,
            null,
            null);
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(new ArrayList<>(), 0x5208));

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  @Test
  public void shouldReturnExpectedValueForContractDeploy() {
    final JsonCallParameter callParameter =
        new JsonCallParameter(
            Address.fromHexString("0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b"),
            null,
            null,
            null,
            null,
            null,
            null,
            Bytes.fromHexString(
                "0x608060405234801561001057600080fd5b50610157806100206000396000f30060806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680633bdab8bf146100515780639ae97baa14610068575b600080fd5b34801561005d57600080fd5b5061006661007f565b005b34801561007457600080fd5b5061007d6100b9565b005b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60016040518082815260200191505060405180910390a1565b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60026040518082815260200191505060405180910390a17fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60036040518082815260200191505060405180910390a15600a165627a7a7230582010ddaa52e73a98c06dbcd22b234b97206c1d7ed64a7c048e10c2043a3d2309cb0029"),
            null,
            null);
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(new ArrayList<>(), 0x1f081));

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  @Test
  public void shouldIgnoreSenderBalanceAccountWhenStrictModeDisabledAndReturnExpectedValue() {
    final JsonCallParameter callParameter =
        new JsonCallParameter(
            Address.fromHexString("0x0000000000000000000000000000000000000000"),
            null,
            1L,
            Wei.fromHexString("0x9999999999"),
            null,
            null,
            null,
            Bytes.fromHexString(
                "0x608060405234801561001057600080fd5b50610157806100206000396000f30060806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680633bdab8bf146100515780639ae97baa14610068575b600080fd5b34801561005d57600080fd5b5061006661007f565b005b34801561007457600080fd5b5061007d6100b9565b005b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60016040518082815260200191505060405180910390a1565b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60026040518082815260200191505060405180910390a17fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60036040518082815260200191505060405180910390a15600a165627a7a7230582010ddaa52e73a98c06dbcd22b234b97206c1d7ed64a7c048e10c2043a3d2309cb0029"),
            false,
            null);
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(new ArrayList<>(), 0x1f081));

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  @Test
  public void shouldReturnExpectedValueForInsufficientGas() {
    final JsonCallParameter callParameter =
        new JsonCallParameter(null, null, 1L, null, null, null, null, null, null, null);
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(new ArrayList<>(), 0xcf08));

    final JsonRpcResponse response = method.response(request);

    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  private void assertAccessListExpectedResult(
      final JsonCallParameter callParameter,
      final List<AccessListEntry> accessList,
      final long gasUsed) {
    final JsonRpcRequestContext request = requestWithParams(callParameter);
    final JsonRpcResponse expectedResponse =
        new JsonRpcSuccessResponse(null, new CreateAccessListResult(accessList, gasUsed));

    final JsonRpcResponse response = method.response(request);
    assertThat(response).usingRecursiveComparison().isEqualTo(expectedResponse);
  }

  private JsonCallParameter createAccessListJsonCallParameters(
      final String from, final String to, final List<AccessListEntry> accessList) {
    return new JsonCallParameter(
        Address.fromHexString(from),
        Address.fromHexString(to),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        accessList);
  }

  private JsonRpcRequestContext requestWithParams(final Object... params) {
    return new JsonRpcRequestContext(new JsonRpcRequest("2.0", "eth_createAccessList", params));
  }
}
