package io.casehub.drafthouse.debate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PipelineDecisionParser {

    private static final Pattern DECISION_HEADER = Pattern.compile("^## (D\\d+):\\s+(.+)$", Pattern.MULTILINE);

    private PipelineDecisionParser() {}

    public static List<PipelineDecision> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) return List.of();
        var decisions = new ArrayList<PipelineDecision>();
        Matcher m = DECISION_HEADER.matcher(markdown);
        var starts = new ArrayList<int[]>();
        while (m.find()) starts.add(new int[]{m.start(), m.end()});

        for (int i = 0; i < starts.size(); i++) {
            int sectionStart = starts.get(i)[0];
            int sectionEnd = i + 1 < starts.size() ? starts.get(i + 1)[0] : markdown.length();
            String header = markdown.substring(starts.get(i)[0], starts.get(i)[1]);
            Matcher hm = DECISION_HEADER.matcher(header);
            if (!hm.find()) continue;
            String id = hm.group(1);
            String title = hm.group(2).trim();
            String section = markdown.substring(sectionStart, sectionEnd);

            decisions.add(new PipelineDecision(
                    id, title,
                    extractField(section, "Choice"),
                    extractAlternatives(section),
                    extractField(section, "Rationale"),
                    extractField(section, "Trade-offs"),
                    extractField(section, "Status"),
                    extractField(section, "Exploration"),
                    extractField(section, "Depends on")));
        }
        return decisions;
    }

    private static String extractField(String section, String fieldName) {
        Pattern p = Pattern.compile("\\*\\*" + fieldName + ":\\*\\*\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(section);
        return m.find() ? m.group(1).trim() : null;
    }

    private static List<String> extractAlternatives(String section) {
        int idx = section.indexOf("**Alternatives:**");
        if (idx < 0) return List.of();
        var alts = new ArrayList<String>();
        String[] lines = section.substring(idx).split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("- ")) alts.add(line.substring(2).trim());
            else if (line.startsWith("**")) break;
            else if (!line.isEmpty()) break;
        }
        return alts;
    }
}
