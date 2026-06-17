package io.casehub.drafthouse;

import java.util.List;

class DocumentSetJson {

    private DocumentSetJson() {}

    static String documentsToJson(List<DocumentSet.DocumentEntry> docs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"path\":").append(escapeAndQuote(docs.get(i).path()))
              .append(",\"label\":").append(escapeAndQuote(docs.get(i).label())).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    static String comparisonToJson(DocumentSet.ComparisonPair cp) {
        if (cp == null) return "null";
        return "{\"pathA\":" + escapeAndQuote(cp.pathA())
                + ",\"pathB\":" + escapeAndQuote(cp.pathB()) + "}";
    }

    static String documentsAndComparisonToJson(DocumentSet documentSet) {
        return "{\"documents\":" + documentsToJson(documentSet.documents())
                + ",\"currentComparison\":" + comparisonToJson(documentSet.currentComparison()) + "}";
    }

    private static String escapeAndQuote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
