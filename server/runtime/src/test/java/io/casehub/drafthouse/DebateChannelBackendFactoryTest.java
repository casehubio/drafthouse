package io.casehub.drafthouse;

import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.gateway.ChannelInitialisedEvent;
import io.casehub.qhorus.runtime.gateway.ChannelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DebateChannelBackendFactoryTest {

    private ChannelGateway gateway;
    private DebateChannelBackend debateBackend;
    private DebateChannelBackendFactory debateFactory;
    private ReviewerChannelBackendFactory reviewerFactory;
    private ReviewSessionRegistry reviewRegistry;

    @BeforeEach
    void setUp() {
        gateway = mock(ChannelGateway.class);
        debateBackend = new DebateChannelBackend();

        debateFactory = new DebateChannelBackendFactory();
        debateFactory.gateway = gateway;
        debateFactory.debateBackend = debateBackend;

        reviewRegistry = mock(ReviewSessionRegistry.class);
        reviewerFactory = new ReviewerChannelBackendFactory();
        reviewerFactory.gateway = gateway;
        reviewerFactory.registry = reviewRegistry;
        // other ReviewerChannelBackendFactory fields left null — factory returns before using them when debate channel guard fires
    }

    @Test
    void debateChannel_registersDebateBackend_notReviewerBackend() {
        UUID channelId = UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, "drafthouse/debate/d-abc123", false);

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        verify(gateway).deregisterBackend(channelId, DebateChannelBackend.BACKEND_ID);
        verify(gateway).registerBackend(channelId, debateBackend, DebateChannelBackend.BACKEND_TYPE);
        // ReviewerChannelBackendFactory returns early — registry.find() must not have been called
        verifyNoInteractions(reviewRegistry);
    }

    @Test
    void reviewChannel_doesNotRegisterDebateBackend() {
        UUID channelId = UUID.randomUUID();
        String channelName = "drafthouse/r-" + UUID.randomUUID();
        ChannelInitialisedEvent event = new ChannelInitialisedEvent(channelId, channelName, false);

        // ReviewerChannelBackendFactory will call registry.find() — no session → returns early
        when(reviewRegistry.find(channelId)).thenReturn(Optional.empty());

        debateFactory.onChannelInitialised(event);
        reviewerFactory.onChannelInitialised(event);

        // DebateChannelBackendFactory should not register for non-debate channels
        verify(gateway, never()).registerBackend(eq(channelId), eq(debateBackend), anyString());
    }
}
