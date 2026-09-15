package io.casehub.drafthouse.voice;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotesPipelineTest {

    @Test
    void stubModeReturnsPassthroughResult() {
        var pipeline = new NotesPipeline((ChatModel) null);
        var result   = pipeline.process("um hello uh world", null);

        assertNotNull(result);
        assertEquals("um hello uh world", result.cleanedText());
        assertNotNull(result.title());
        assertEquals("en", result.language());
    }

    @Test
    void stubModeTruncatesLongTitles() {
        var pipeline = new NotesPipeline((ChatModel) null);
        var longText = "This is a really long transcript that goes on and on and on for quite a while";
        var result   = pipeline.process(longText, null);

        assertTrue(result.title().length() <= 44);
        assertTrue(result.title().endsWith("..."));
    }

    @Test
    void constructorAcceptsModel() {
        var mockModel = mock(ChatModel.class);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                                        .aiMessage(AiMessage.from("test"))
                                        .build());

        var pipeline = new NotesPipeline(mockModel);
        assertNotNull(pipeline);
    }
}
