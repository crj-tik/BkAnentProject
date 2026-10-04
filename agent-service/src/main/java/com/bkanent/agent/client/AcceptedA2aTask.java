package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentTaskInvokeRequest;

/** Durable original association, never resolved through a later Agent endpoint. */
public record AcceptedA2aTask(RegisteredAgentDescriptor descriptor, String remoteTaskId, AgentTaskInvokeRequest request) {}
