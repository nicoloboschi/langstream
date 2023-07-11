package com.datastax.oss.sga.impl.common;

import com.datastax.oss.sga.api.model.AgentConfiguration;
import com.datastax.oss.sga.api.model.ApplicationInstance;
import com.datastax.oss.sga.api.model.Connection;
import com.datastax.oss.sga.api.model.Module;
import com.datastax.oss.sga.api.model.Pipeline;
import com.datastax.oss.sga.api.model.TopicDefinition;
import com.datastax.oss.sga.api.runtime.AgentImplementation;
import com.datastax.oss.sga.api.runtime.AgentImplementationProvider;
import com.datastax.oss.sga.api.runtime.ClusterRuntime;
import com.datastax.oss.sga.api.runtime.ConnectionImplementation;
import com.datastax.oss.sga.api.runtime.PhysicalApplicationInstance;
import com.datastax.oss.sga.api.runtime.PluginsRegistry;
import com.datastax.oss.sga.api.runtime.StreamingClusterRuntime;
import com.datastax.oss.sga.api.runtime.TopicImplementation;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * Basic class with common utility methods for a ClusterRuntime.
 */
@Slf4j
public abstract class BasicClusterRuntime implements ClusterRuntime {
    @Override
    public PhysicalApplicationInstance createImplementation(ApplicationInstance applicationInstance,
                                                            PluginsRegistry pluginsRegistry, StreamingClusterRuntime streamingClusterRuntime) {

        PhysicalApplicationInstance result =
                new PhysicalApplicationInstance(applicationInstance);

        detectTopics(result, streamingClusterRuntime);

        detectAgents(result, streamingClusterRuntime, pluginsRegistry);


        return result;
    }

    /**
     * Detects topics that are explicitly defined in the application instance.
     * @param result
     * @param streamingClusterRuntime
     */
    protected void detectTopics(PhysicalApplicationInstance result,
                              StreamingClusterRuntime streamingClusterRuntime) {
        ApplicationInstance applicationInstance = result.getApplicationInstance();
        for (Module module : applicationInstance.getModules().values()) {
            for (TopicDefinition topic : module.getTopics().values()) {
                TopicImplementation topicImplementation = streamingClusterRuntime.createTopicImplementation(topic, result);
                result.registerTopic(topic, topicImplementation);
            }
        }
    }

    /**
     * Detects the Agaents and build connections to them.
     * This operation may implicitly declare additional topics.
     * @param result
     * @param streamingClusterRuntime
     * @param pluginsRegistry
     */

    protected void detectAgents(PhysicalApplicationInstance result,
                                StreamingClusterRuntime streamingClusterRuntime,
                                PluginsRegistry pluginsRegistry) {
        ApplicationInstance applicationInstance = result.getApplicationInstance();
        for (Module module : applicationInstance.getModules().values()) {
            if (module.getPipelines() == null) {
                return;
            }
            for (Pipeline pipeline : module.getPipelines().values()) {
                log.info("Pipeline: {}", pipeline.getName());
                AgentImplementation previousAgent = null;
                for (AgentConfiguration agentConfiguration : pipeline.getAgents().values()) {
                    previousAgent = buildAgent(module, agentConfiguration, result, pluginsRegistry,
                            streamingClusterRuntime, previousAgent);
                }
            }
        }
    }


    protected AgentImplementation buildAgent(Module module, AgentConfiguration agentConfiguration,
                            PhysicalApplicationInstance result,
                            PluginsRegistry pluginsRegistry,
                            StreamingClusterRuntime streamingClusterRuntime,
                            AgentImplementation previousAgent) {
        log.info("Processing agent {} id={} type={}", agentConfiguration.getName(), agentConfiguration.getId(),
                agentConfiguration.getType());
        AgentImplementationProvider agentImplementationProvider =
                pluginsRegistry.lookupAgentImplementation(agentConfiguration.getType(), this);

        AgentImplementation agentImplementation = agentImplementationProvider
                .createImplementation(agentConfiguration, module, result, this, pluginsRegistry, streamingClusterRuntime );

        if (previousAgent != null && agentImplementationProvider.canMerge(previousAgent, agentImplementation)) {
            agentImplementation = agentImplementationProvider.mergeAgents(previousAgent, agentImplementation, result);
        }

        result.registerAgent(module, agentConfiguration.getId(), agentImplementation);

        return agentImplementation;
    }

    @Override
    public ConnectionImplementation getConnectionImplementation(Module module, Connection connection,
                                                                PhysicalApplicationInstance physicalApplicationInstance,
                                                                StreamingClusterRuntime streamingClusterRuntime) {
        Connection.Connectable endpoint = connection.endpoint();
        if (endpoint instanceof TopicDefinition topicDefinition) {
            // compare by name
            ConnectionImplementation result =
                    physicalApplicationInstance.getTopicByName(topicDefinition.getName());
            if (result == null) {
                throw new IllegalArgumentException("Topic " + topicDefinition.getName() + " not found, " +
                        "only " + physicalApplicationInstance.getTopics().keySet()
                        .stream()
                        .map(TopicDefinition::getName)
                        .collect(Collectors.toList()) + " are available");
            }
            return result;
        } else if (endpoint instanceof AgentConfiguration agentConfiguration) {
            return buildImplicitTopicForAgent(physicalApplicationInstance, agentConfiguration, streamingClusterRuntime);
        }
        throw new UnsupportedOperationException("Not implemented yet, connection with " + endpoint);
    }

    protected ConnectionImplementation buildImplicitTopicForAgent(PhysicalApplicationInstance physicalApplicationInstance,
                                                                  AgentConfiguration agentConfiguration,
                                                                  StreamingClusterRuntime streamingClusterRuntime) {
        // connecting two agents requires an intermediate topic
        String name = "agent-" + agentConfiguration.getId() + "-output";
        log.info("Automatically creating topic {} in order to connect agent {}", name, agentConfiguration.getId());
        // short circuit...the Pulsar Runtime works only with Pulsar Topics on the same Pulsar Cluster
        String creationMode = TopicDefinition.CREATE_MODE_CREATE_IF_NOT_EXISTS;
        TopicDefinition topicDefinition = new TopicDefinition(name, creationMode, null);
        TopicImplementation topicImplementation = streamingClusterRuntime.createTopicImplementation(topicDefinition, physicalApplicationInstance);
        physicalApplicationInstance.registerTopic(topicDefinition, topicImplementation);

        return topicImplementation;
    }

    @Override
    public void deploy(PhysicalApplicationInstance applicationInstance, StreamingClusterRuntime streamingClusterRuntime) {
        streamingClusterRuntime.deploy(applicationInstance);
        log.warn("ClusterType " + getClusterType() + " doesn't actually deploy agents, it's just a logical representation");
    }

    @Override
    public void delete(PhysicalApplicationInstance applicationInstance, StreamingClusterRuntime streamingClusterRuntime) {
        streamingClusterRuntime.delete(applicationInstance);
        log.warn("ClusterType " + getClusterType() + " doesn't actually deploy agents, it's just a logical representation");
    }
}