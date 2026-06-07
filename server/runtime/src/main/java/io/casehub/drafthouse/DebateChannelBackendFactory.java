package io.casehub.drafthouse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;

/**
 * Registers DebateChannelBackend for debate channels on init and startup recovery.
 * Deregisters before registering to be idempotent on restart.
 */
@ApplicationScoped
public class DebateChannelBackendFactory {

    @Inject ChannelGateway gateway;
    @Inject DebateChannelBackend debateBackend;

    void onChannelInitialised(@Observes ChannelInitialisedEvent event) {
        if (!event.channelName().startsWith("drafthouse/debate/")) return;
        gateway.deregisterBackend(event.channelId(), DebateChannelBackend.BACKEND_ID);
        gateway.registerBackend(event.channelId(), debateBackend, DebateChannelBackend.BACKEND_TYPE);
    }
}
