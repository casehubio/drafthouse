package io.casehub.drafthouse;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelBackend;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * No-op ChannelBackend for debate channels.
 *
 * Debate is peer-to-peer: REV and IMP agents post directly via DebateMcpTools.
 * No backend processing is triggered on message arrival.
 * The backend's only role is as a registration fence — its presence prevents
 * ReviewerChannelBackendFactory from attaching an LLM backend to debate channels.
 *
 * Stateless and @ApplicationScoped: the same instance handles all debate channels.
 */
@ApplicationScoped
public class DebateChannelBackend implements ChannelBackend {

    static final String BACKEND_ID   = "drafthouse-debate";
    static final String BACKEND_TYPE = "agent";

    @Override public String backendId() { return BACKEND_ID; }
    @Override public ActorType actorType() { return ActorType.AGENT; }
    @Override public void open(ChannelRef channel, Map<String, String> metadata) {}
    @Override public void close(ChannelRef channel) {}
    @Override public void post(ChannelRef channel, OutboundMessage message) {}
}
