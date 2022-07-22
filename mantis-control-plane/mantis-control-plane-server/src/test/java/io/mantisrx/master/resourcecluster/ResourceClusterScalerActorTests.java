/*
 * Copyright 2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mantisrx.master.resourcecluster;

import static io.mantisrx.master.resourcecluster.ResourceClusterActorTest.actorSystem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.javadsl.TestKit;
import io.mantisrx.common.Ack;
import io.mantisrx.master.resourcecluster.ResourceClusterActor.GetClusterUsageRequest;
import io.mantisrx.master.resourcecluster.ResourceClusterScalerActor.ClusterAvailabilityRule;
import io.mantisrx.master.resourcecluster.ResourceClusterScalerActor.GetRuleSetRequest;
import io.mantisrx.master.resourcecluster.ResourceClusterScalerActor.GetRuleSetResponse;
import io.mantisrx.master.resourcecluster.ResourceClusterScalerActor.ScaleDecision;
import io.mantisrx.master.resourcecluster.ResourceClusterScalerActor.ScaleType;
import io.mantisrx.master.resourcecluster.proto.GetClusterIdleInstancesRequest;
import io.mantisrx.master.resourcecluster.proto.GetClusterIdleInstancesResponse;
import io.mantisrx.master.resourcecluster.proto.GetClusterUsageResponse;
import io.mantisrx.master.resourcecluster.proto.GetClusterUsageResponse.UsageByMachineDefinition;
import io.mantisrx.master.resourcecluster.proto.ResourceClusterScaleSpec;
import io.mantisrx.master.resourcecluster.proto.ScaleResourceRequest;
import io.mantisrx.master.resourcecluster.resourceprovider.ResourceClusterStorageProvider;
import io.mantisrx.master.resourcecluster.writable.ResourceClusterScaleRulesWritable;
import io.mantisrx.runtime.MachineDefinition;
import io.mantisrx.runtime.MachineDefinitionWrapper;
import io.mantisrx.server.master.resourcecluster.ClusterID;
import io.mantisrx.server.master.resourcecluster.TaskExecutorID;
import io.mantisrx.shaded.com.google.common.collect.ImmutableList;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

public class ResourceClusterScalerActorTests {
    private static final ClusterID CLUSTER_ID = ClusterID.of("clusterId");
    private static final String skuSmall = "small";
    private static final String skuMedium = "medium";
    private static final String skuLarge = "large";
    private ActorRef scalerActor;
    private ResourceClusterStorageProvider storageProvider;
    private TestKit clusterActorProbe;
    private TestKit hostActorProbe;

    private static final MachineDefinitionWrapper MACHINE_DEFINITION_S =
        MachineDefinitionWrapper.builder().definitionId(skuSmall).machineDefinition(
            new MachineDefinition(2, 2048, 700, 10240, 5)).build();
    private static final MachineDefinitionWrapper MACHINE_DEFINITION_L =
        MachineDefinitionWrapper.builder().definitionId(skuLarge).machineDefinition(
            new MachineDefinition(4, 16384, 1400, 81920, 5)).build();

    private static final MachineDefinitionWrapper MACHINE_DEFINITION_M =
        MachineDefinitionWrapper.builder().definitionId(skuMedium).machineDefinition(
            new MachineDefinition(3, 4096, 700, 10240, 5)).build();

    @BeforeClass
    public static void setup() {
        actorSystem = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        TestKit.shutdownActorSystem(actorSystem);
        actorSystem = null;
    }

    @Before
    public void setupMocks() {
        clusterActorProbe = new TestKit(actorSystem);
        hostActorProbe = new TestKit(actorSystem);
        this.storageProvider = mock(ResourceClusterStorageProvider.class);

        when(this.storageProvider.getResourceClusterScaleRules(CLUSTER_ID.getResourceID()))
            .thenReturn(CompletableFuture.completedFuture(
                ResourceClusterScaleRulesWritable.builder()
                    .scaleRule(skuSmall, ResourceClusterScaleSpec.builder()
                        .clusterId(CLUSTER_ID.getResourceID())
                        .skuId(skuSmall)
                        .coolDownSecs(10)
                        .maxIdleToKeep(10)
                        .minIdleToKeep(5)
                        .minSize(11)
                        .maxSize(15)
                        .build())
                    .scaleRule(skuLarge, ResourceClusterScaleSpec.builder()
                        .clusterId(CLUSTER_ID.getResourceID())
                        .skuId(skuLarge)
                        .coolDownSecs(10)
                        .maxIdleToKeep(15)
                        .minIdleToKeep(5)
                        .minSize(11)
                        .maxSize(15)
                        .build())
                    .build()
            ));
    }

    @Test
    public void testScaler() {

        final Props props =
            ResourceClusterScalerActor.props(
                CLUSTER_ID,
                Clock.systemDefaultZone(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                this.storageProvider,
                hostActorProbe.getRef(),
                clusterActorProbe.getRef());

        scalerActor = actorSystem.actorOf(props);
        GetClusterUsageRequest req = clusterActorProbe.expectMsgClass(GetClusterUsageRequest.class);
        assertEquals(CLUSTER_ID, req.getClusterID());

        scalerActor.tell(
            GetClusterUsageResponse.builder()
                .clusterID(CLUSTER_ID)
                .usage(
                    UsageByMachineDefinition.builder().def(MACHINE_DEFINITION_S).idleCount(4).totalCount(10).build())
                .usage(
                    UsageByMachineDefinition.builder().def(MACHINE_DEFINITION_L).idleCount(16).totalCount(16).build())
                .usage(
                    UsageByMachineDefinition.builder().def(MACHINE_DEFINITION_M).idleCount(8).totalCount(15).build())
                .build(),
            clusterActorProbe.getRef());

        assertEquals(
            GetClusterIdleInstancesRequest.builder()
                .skuId(skuLarge)
                .machineDefinitionWrapper(MACHINE_DEFINITION_L)
                .clusterID(CLUSTER_ID)
                .desireSize(15)
                .maxInstanceCount(1)
                .build(),
            clusterActorProbe.expectMsgClass(GetClusterIdleInstancesRequest.class));

        assertNotNull(clusterActorProbe.expectMsgClass(Ack.class));

        Set<ScaleResourceRequest> decisions = new HashSet<>();
        decisions.add(hostActorProbe.expectMsgClass(ScaleResourceRequest.class));
        //decisions.add(hostActorProbe.expectMsgClass(ScaleResourceRequest.class));

        int newSize = 11;
        assertTrue(decisions.contains(
            ScaleResourceRequest.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .skuId(skuSmall)
                .desireSize(newSize)
                .build()));

        // Test callback from fetch idle list.
        ImmutableList<TaskExecutorID> idleInstances = ImmutableList.of(TaskExecutorID.of("agent1"));
        scalerActor.tell(
            GetClusterIdleInstancesResponse.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .instanceIds(idleInstances)
                .skuId(skuLarge)
                .desireSize(15)
                .build(),
            clusterActorProbe.getRef());

        newSize = 15;
        assertEquals(
            ScaleResourceRequest.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .skuId(skuLarge)
                .desireSize(newSize)
                .idleInstances(idleInstances)
                .build(),
            hostActorProbe.expectMsgClass(ScaleResourceRequest.class));

        // Test trigger again
        GetClusterUsageRequest req2 = clusterActorProbe.expectMsgClass(GetClusterUsageRequest.class);
        assertEquals(CLUSTER_ID, req2.getClusterID());
    }

    @Test
    public void testScalerRuleSetRefresh() throws InterruptedException {
        final Props props =
            ResourceClusterScalerActor.props(
                CLUSTER_ID,
                Clock.systemDefaultZone(),
                Duration.ofSeconds(100),
                Duration.ofSeconds(1),
                this.storageProvider,
                hostActorProbe.getRef(),
                clusterActorProbe.getRef());

        scalerActor = actorSystem.actorOf(props);
        scalerActor.tell(GetRuleSetRequest.builder().build(), clusterActorProbe.getRef());
        GetRuleSetResponse rules = clusterActorProbe.expectMsgClass(GetRuleSetResponse.class);
        assertEquals(2, rules.getRules().size());

        when(this.storageProvider.getResourceClusterScaleRules(ArgumentMatchers.anyString()))
            .thenReturn(CompletableFuture.completedFuture(
                ResourceClusterScaleRulesWritable.builder()
                    .scaleRule(skuMedium, ResourceClusterScaleSpec.builder()
                        .clusterId(CLUSTER_ID.getResourceID())
                        .skuId(skuMedium)
                        .coolDownSecs(10)
                        .maxIdleToKeep(20)
                        .minIdleToKeep(5)
                        .minSize(11)
                        .maxSize(15)
                        .build())
                    .build()
            ));

        Thread.sleep(1500);

        scalerActor.tell(GetRuleSetRequest.builder().build(), clusterActorProbe.getRef());
        rules = clusterActorProbe.expectMsgClass(GetRuleSetResponse.class);
        assertEquals(1, rules.getRules().size());
        assertTrue(rules.getRules().containsKey(skuMedium));

    }

    @Test
    public void testRuleCoolDown() {
        String skuId = "small";
        ClusterAvailabilityRule rule = new ClusterAvailabilityRule(
            ResourceClusterScaleSpec.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .skuId(skuId)
                .coolDownSecs(10)
                .maxIdleToKeep(10)
                .minIdleToKeep(5)
                .minSize(11)
                .maxSize(15)
                .build(),
            Clock.fixed(Clock.systemUTC().instant(), ZoneId.systemDefault()));

        MachineDefinitionWrapper mDef =
            MachineDefinitionWrapper.builder().machineDefinition(
                new MachineDefinition(2, 2048, 700, 10240, 5))
                .build();

        // Test scale up
        UsageByMachineDefinition usage = UsageByMachineDefinition.builder()
            .def(mDef).idleCount(4).totalCount(10).build();
        Optional<ScaleDecision> decision = rule.apply(usage);
        int newSize = 11;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleUp)
                    .build()),
            decision);

        // test cool down
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(4).totalCount(10).build();
        assertEquals(Optional.empty(), rule.apply(usage));
    }

    @Test
    public void testRuleFinishCoolDown() throws InterruptedException {
        String skuId = "small";
        ClusterAvailabilityRule rule = new ClusterAvailabilityRule(
            ResourceClusterScaleSpec.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .skuId(skuId)
                .coolDownSecs(2)
                .maxIdleToKeep(10)
                .minIdleToKeep(5)
                .minSize(11)
                .maxSize(15)
                .build(),
            Clock.systemUTC());

        MachineDefinitionWrapper mDef =
            MachineDefinitionWrapper.builder().machineDefinition(
                    new MachineDefinition(2, 2048, 700, 10240, 5))
                .build();

        // Test scale up
        UsageByMachineDefinition usage = UsageByMachineDefinition.builder().def(mDef).idleCount(4).totalCount(10).build();
        Optional<ScaleDecision> decision = rule.apply(usage);
        int newSize = 11;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleUp)
                    .build()),
            decision);

        // test cool down
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(4).totalCount(10).build();
        assertEquals(Optional.empty(), rule.apply(usage));

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleUp)
                    .build()),
            rule.apply(usage));
    }

    @Test
    public void testRule() {
        // TestKit probe = new TestKit(actorSystem);
        String skuId = "small";
        ClusterAvailabilityRule rule = new ClusterAvailabilityRule(
            ResourceClusterScaleSpec.builder()
                .clusterId(CLUSTER_ID.getResourceID())
                .skuId(skuId)
                .coolDownSecs(0)
                .maxIdleToKeep(10)
                .minIdleToKeep(5)
                .minSize(11)
                .maxSize(15)
                .build(),
            Clock.fixed(Instant.MIN, ZoneId.systemDefault()));

        MachineDefinitionWrapper mDef =
            MachineDefinitionWrapper.builder().machineDefinition(
                    new MachineDefinition(2, 2048, 700, 10240, 5))
                .build();

        // Test scale up
        UsageByMachineDefinition usage = UsageByMachineDefinition.builder().def(mDef).idleCount(4).totalCount(10).build();
        Optional<ScaleDecision> decision =  rule.apply(usage);
        int newSize = 11;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleUp)
                    .build()),
            decision);

        // Test empty
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(9).totalCount(11).build();
        decision =  rule.apply(usage);
        assertEquals(
            Optional.empty(),
            decision);

        // Test scale up hits max
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(0).totalCount(11).build();
        decision =  rule.apply(usage);
        newSize = 15;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleUp)
                    .build()),
            decision);

        // Test scale down
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(15).totalCount(20).build();
        decision =  rule.apply(usage);
        newSize = 15;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleDown)
                    .build()),
            decision);

        // Test scale down hits min.
        usage = UsageByMachineDefinition.builder().def(mDef).idleCount(15).totalCount(15).build();
        decision =  rule.apply(usage);
        newSize = 11;
        assertEquals(
            Optional.of(
                ScaleDecision.builder()
                    .clusterId(CLUSTER_ID.getResourceID())
                    .skuId(skuId)
                    .desireSize(newSize)
                    .minSize(newSize)
                    .maxSize(newSize)
                    .type(ScaleType.ScaleDown)
                    .build()),
            decision);
    }
}