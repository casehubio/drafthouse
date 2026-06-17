package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocumentSetJsonTest {

    @Test
    void documentsToJson_emptyList() {
        var set = new DocumentSet();
        assertThat(DocumentSetJson.documentsToJson(set.documents())).isEqualTo("[]");
    }

    @Test
    void documentsToJson_singleDocument() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        assertThat(DocumentSetJson.documentsToJson(set.documents()))
                .isEqualTo("[{\"path\":\"/a.md\",\"label\":\"spec\"}]");
    }

    @Test
    void documentsToJson_multipleDocuments() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        String json = DocumentSetJson.documentsToJson(set.documents());
        assertThat(json).startsWith("[{");
        assertThat(json).contains("\"path\":\"/a.md\"");
        assertThat(json).contains("\"path\":\"/b.md\"");
        assertThat(json).endsWith("}]");
    }

    @Test
    void documentsToJson_escapesSpecialChars() {
        var set = new DocumentSet();
        set.add("/path/with \"quotes\".md", "label\nwith\nnewlines");
        String json = DocumentSetJson.documentsToJson(set.documents());
        assertThat(json).contains("\\\"quotes\\\"");
        assertThat(json).contains("\\n");
    }

    @Test
    void comparisonToJson_nonNull() {
        var cp = new DocumentSet.ComparisonPair("/a.md", "/b.md");
        assertThat(DocumentSetJson.comparisonToJson(cp))
                .isEqualTo("{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
    }

    @Test
    void comparisonToJson_null() {
        assertThat(DocumentSetJson.comparisonToJson(null)).isEqualTo("null");
    }

    @Test
    void documentsAndComparisonToJson_withComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        String json = DocumentSetJson.documentsAndComparisonToJson(set);
        assertThat(json).startsWith("{\"documents\":[");
        assertThat(json).contains("\"currentComparison\":{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
        assertThat(json).endsWith("}");
    }

    @Test
    void documentsAndComparisonToJson_withoutComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        String json = DocumentSetJson.documentsAndComparisonToJson(set);
        assertThat(json).contains("\"currentComparison\":null");
    }

    @Test
    void comparisonToJson_pathsWithSpecialChars() {
        var cp = new DocumentSet.ComparisonPair("/path\twith\ttabs.md", "/normal.md");
        String json = DocumentSetJson.comparisonToJson(cp);
        assertThat(json).contains("\\t");
    }
}
