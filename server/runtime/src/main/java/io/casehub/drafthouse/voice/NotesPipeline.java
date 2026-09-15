package io.casehub.drafthouse.voice;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class NotesPipeline {

    private static final Logger LOG = Logger.getLogger(NotesPipeline.class.getName());

    interface CleanupService {
        @SystemMessage("""
                       You are a transcript cleanup assistant. Given a raw voice transcript:
                       1. Remove filler words (um, uh, ah, like, you know)
                       2. Remove repeated words and sentences
                       3. Fix broken sentence fragments
                       4. Extract a concise title, summary, and relevant tags
                       5. Detect the language
                       Do NOT change the meaning or add new content. Preserve the speaker's intent.""")
        @UserMessage("Clean up this transcript and extract metadata:\n\n{{it}}")
        CleanupResult cleanup(String rawText);
    }

    private final CleanupService cleanupService;

    @Inject
    public NotesPipeline(Instance<ChatModel> modelInstance) {
        if (modelInstance.isResolvable()) {
            this.cleanupService = AiServices.create(CleanupService.class, modelInstance.get());
            LOG.info("NotesPipeline initialized with ChatModel");
        } else {
            this.cleanupService = null;
            LOG.info("NotesPipeline initialized in stub mode — no ChatModel available");
        }
    }

    NotesPipeline(ChatModel model) {
        this.cleanupService = model != null
                              ? AiServices.create(CleanupService.class, model)
                              : null;
    }

    public CleanupResult process(String rawText, String goalTag) {
        if (cleanupService == null) {
            var title = rawText.length() > 40
                        ? rawText.substring(0, 40).trim() + "..."
                        : rawText.trim();
            return new CleanupResult(rawText, title, null, List.of(), "en");
        }
        return cleanupService.cleanup(rawText);
    }
}
