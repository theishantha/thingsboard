/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.server.actors.ruleChain;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.LoggingAdapter;
import com.datastax.driver.core.utils.UUIDs;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.actors.device.DeviceActorToRuleEngineMsg;
import org.thingsboard.server.actors.device.RuleEngineQueuePutAckMsg;
import org.thingsboard.server.actors.service.DefaultActorService;
import org.thingsboard.server.actors.shared.ComponentMsgProcessor;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleState;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.cluster.ClusterEventMsg;
import org.thingsboard.server.common.msg.plugin.ComponentLifecycleMsg;
import org.thingsboard.server.common.msg.system.ServiceToRuleEngineMsg;
import org.thingsboard.server.dao.rule.RuleChainService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Andrew Shvayka
 */
public class RuleChainActorMessageProcessor extends ComponentMsgProcessor<RuleChainId> {

    private static final long DEFAULT_CLUSTER_PARTITION = 0L;
    private final ActorRef parent;
    private final ActorRef self;
    private final Map<RuleNodeId, RuleNodeCtx> nodeActors;
    private final Map<RuleNodeId, List<RuleNodeRelation>> nodeRoutes;
    private final RuleChainService service;

    private RuleNodeId firstId;
    private RuleNodeCtx firstNode;
    private boolean started;

    RuleChainActorMessageProcessor(TenantId tenantId, RuleChainId ruleChainId, ActorSystemContext systemContext
            , LoggingAdapter logger, ActorRef parent, ActorRef self) {
        super(systemContext, logger, tenantId, ruleChainId);
        this.parent = parent;
        this.self = self;
        this.nodeActors = new HashMap<>();
        this.nodeRoutes = new HashMap<>();
        this.service = systemContext.getRuleChainService();
    }

    @Override
    public void start(ActorContext context) throws Exception {
        if (!started) {
            RuleChain ruleChain = service.findRuleChainById(entityId);
            List<RuleNode> ruleNodeList = service.getRuleChainNodes(entityId);
            // Creating and starting the actors;
            for (RuleNode ruleNode : ruleNodeList) {
                ActorRef ruleNodeActor = createRuleNodeActor(context, ruleNode);
                nodeActors.put(ruleNode.getId(), new RuleNodeCtx(tenantId, self, ruleNodeActor, ruleNode));
            }
            initRoutes(ruleChain, ruleNodeList);
            //TODO: read all messages from queues of the actors and push then to the corresponding node actors;
            started = true;
        } else {
            onUpdate(context);
        }
    }

    @Override
    public void onUpdate(ActorContext context) throws Exception {
        RuleChain ruleChain = service.findRuleChainById(entityId);
        List<RuleNode> ruleNodeList = service.getRuleChainNodes(entityId);

        for (RuleNode ruleNode : ruleNodeList) {
            RuleNodeCtx existing = nodeActors.get(ruleNode.getId());
            if (existing == null) {
                ActorRef ruleNodeActor = createRuleNodeActor(context, ruleNode);
                nodeActors.put(ruleNode.getId(), new RuleNodeCtx(tenantId, self, ruleNodeActor, ruleNode));
            } else {
                existing.setSelf(ruleNode);
                existing.getSelfActor().tell(new ComponentLifecycleMsg(tenantId, existing.getSelf().getId(), ComponentLifecycleEvent.UPDATED), self);
            }
        }

        Set<RuleNodeId> existingNodes = ruleNodeList.stream().map(RuleNode::getId).collect(Collectors.toSet());
        List<RuleNodeId> removedRules = nodeActors.keySet().stream().filter(node -> !existingNodes.contains(node)).collect(Collectors.toList());
        removedRules.forEach(ruleNodeId -> {
            RuleNodeCtx removed = nodeActors.remove(ruleNodeId);
            removed.getSelfActor().tell(new ComponentLifecycleMsg(tenantId, removed.getSelf().getId(), ComponentLifecycleEvent.DELETED), self);
        });

        initRoutes(ruleChain, ruleNodeList);
    }

    @Override
    public void stop(ActorContext context) throws Exception {
        nodeActors.values().stream().map(RuleNodeCtx::getSelfActor).forEach(context::stop);
        nodeActors.clear();
        nodeRoutes.clear();
        context.stop(self);
        started = false;
    }

    @Override
    public void onClusterEventMsg(ClusterEventMsg msg) throws Exception {

    }

    private ActorRef createRuleNodeActor(ActorContext context, RuleNode ruleNode) {
        String dispatcherName = tenantId.getId().equals(EntityId.NULL_UUID) ?
                DefaultActorService.SYSTEM_RULE_DISPATCHER_NAME : DefaultActorService.TENANT_RULE_DISPATCHER_NAME;
        return context.actorOf(
                Props.create(new RuleNodeActor.ActorCreator(systemContext, tenantId, entityId, ruleNode.getId()))
                        .withDispatcher(dispatcherName), ruleNode.getId().toString());
    }

    private void initRoutes(RuleChain ruleChain, List<RuleNode> ruleNodeList) {
        nodeRoutes.clear();
        // Populating the routes map;
        for (RuleNode ruleNode : ruleNodeList) {
            List<EntityRelation> relations = service.getRuleNodeRelations(ruleNode.getId());
            if (relations.size() == 0) {
                nodeRoutes.put(ruleNode.getId(), Collections.emptyList());
            } else {
                for (EntityRelation relation : relations) {
                    if (relation.getTo().getEntityType() == EntityType.RULE_NODE) {
                        RuleNodeCtx ruleNodeCtx = nodeActors.get(new RuleNodeId(relation.getTo().getId()));
                        if (ruleNodeCtx == null) {
                            throw new IllegalArgumentException("Rule Node [" + relation.getFrom() + "] has invalid relation to Rule node [" + relation.getTo() + "]");
                        }
                    }
                    nodeRoutes.computeIfAbsent(ruleNode.getId(), k -> new ArrayList<>())
                            .add(new RuleNodeRelation(ruleNode.getId(), relation.getTo(), relation.getType()));
                }
            }
        }

        firstId = ruleChain.getFirstRuleNodeId();
        firstNode = nodeActors.get(ruleChain.getFirstRuleNodeId());
        state = ComponentLifecycleState.ACTIVE;
    }

    void onServiceToRuleEngineMsg(ServiceToRuleEngineMsg envelope) {
        checkActive();
        putToQueue(enrichWithRuleChainId(envelope.getTbMsg()), msg -> pushMsgToNode(firstNode, msg));
    }

    void onDeviceActorToRuleEngineMsg(DeviceActorToRuleEngineMsg envelope) {
        checkActive();
        putToQueue(enrichWithRuleChainId(envelope.getTbMsg()), msg -> {
            pushMsgToNode(firstNode, msg);
            envelope.getCallbackRef().tell(new RuleEngineQueuePutAckMsg(msg.getId()), self);
        });
    }

    void onTellNext(RuleNodeToRuleChainTellNextMsg envelope) {
        checkActive();
        RuleNodeId originator = envelope.getOriginator();
        String targetRelationType = envelope.getRelationType();
        List<RuleNodeRelation> relations = nodeRoutes.get(originator).stream()
                .filter(r -> targetRelationType == null || targetRelationType.equalsIgnoreCase(r.getType()))
                .collect(Collectors.toList());

        TbMsg msg = envelope.getMsg();
        int relationsCount = relations.size();
        if (relationsCount == 0) {
            queue.ack(msg, msg.getRuleNodeId().getId(), msg.getClusterPartition());
        } else if (relationsCount == 1) {
            for (RuleNodeRelation relation : relations) {
                pushToTarget(msg, relation.getOut());
            }
        } else {
            for (RuleNodeRelation relation : relations) {
                EntityId target = relation.getOut();
                switch (target.getEntityType()) {
                    case RULE_NODE:
                        RuleNodeId targetId = new RuleNodeId(target.getId());
                        RuleNodeCtx targetNodeCtx = nodeActors.get(targetId);
                        TbMsg copy = msg.copy(UUIDs.timeBased(), entityId, targetId, DEFAULT_CLUSTER_PARTITION);
                        putToQueue(copy, queuedMsg -> pushMsgToNode(targetNodeCtx, queuedMsg));
                        break;
                    case RULE_CHAIN:
                        parent.tell(new RuleChainToRuleChainMsg(new RuleChainId(target.getId()), entityId, msg, true), self);
                        break;
                }
            }
            //TODO: Ideally this should happen in async way when all targets confirm that the copied messages are successfully written to corresponding target queues.
            EntityId ackId = msg.getRuleNodeId() != null ? msg.getRuleNodeId() : msg.getRuleChainId();
            queue.ack(msg, ackId.getId(), msg.getClusterPartition());
        }
    }

    private void pushToTarget(TbMsg msg, EntityId target) {
        switch (target.getEntityType()) {
            case RULE_NODE:
                pushMsgToNode(nodeActors.get(new RuleNodeId(target.getId())), msg);
                break;
            case RULE_CHAIN:
                parent.tell(new RuleChainToRuleChainMsg(new RuleChainId(target.getId()), entityId, msg, false), self);
                break;
        }
    }

    private void pushMsgToNode(RuleNodeCtx nodeCtx, TbMsg msg) {
        if (nodeCtx != null) {
            nodeCtx.getSelfActor().tell(new RuleChainToRuleNodeMsg(new DefaultTbContext(systemContext, nodeCtx), msg), self);
        }
    }

    private TbMsg enrichWithRuleChainId(TbMsg tbMsg) {
        // We don't put firstNodeId because it may change over time;
        return new TbMsg(tbMsg.getId(), tbMsg.getType(), tbMsg.getOriginator(), tbMsg.getMetaData(), tbMsg.getData(), entityId, null, 0L);
    }
}
