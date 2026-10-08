package dev.ccrec.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ccrec.transcript.TranscriptParser.Block;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptParserTest {

  private final TranscriptParser parser = new TranscriptParser();

  private Block only(String raw) {
    List<Block> blocks = parser.parse(raw).blocks();
    assertEquals(1, blocks.size(), raw);
    return blocks.get(0);
  }

  @Test
  void aSystemRecordWithoutContentIsKeptWhole() {
    String duration = "{\"type\":\"system\",\"subtype\":\"turn_duration\",\"durationMs\":4200}";
    Block block = only(duration);
    assertEquals(TranscriptParser.SYSTEM, block.kind());
    assertEquals("turn_duration", block.subtype());
    assertEquals(duration, block.text());

    String empty = "{\"type\":\"system\",\"subtype\":\"compact_boundary\",\"content\":\"\",\"level\":\"info\"}";
    assertEquals(empty, only(empty).text(), "an empty content says nothing either");

    Block notice = only("{\"type\":\"system\",\"subtype\":\"away_summary\",\"content\":\"while you were away\"}");
    assertEquals("while you were away", notice.text(), "content, where there is some, is the text");
  }

  @Test
  void aKnownRecordInAShapeNotReadHereIsKeptWholeAsUnknown() {
    List<String> odd =
        List.of(
            "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":{\"parts\":[\"hello\"]}}}",
            "{\"type\":\"user\",\"prompt\":\"hello\"}",
            "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":\"hello\"}}",
            "{\"type\":\"attachment\",\"attachment\":\"a note\"}");
    for (String raw : odd) {
      Block block = only(raw);
      assertEquals(TranscriptParser.UNKNOWN, block.kind());
      assertEquals(raw, block.text());
    }
    assertEquals("m1", parser.parse(odd.get(2)).messageId(), "what can be read of it still is");
  }

  @Test
  void aRecordWithNothingToKeepLeavesNoBlock() {
    // A thinking block whose text was withheld: only its signature is in the transcript.
    assertTrue(
        parser
            .parse(
                "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":"
                    + "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"c2ln\"}]}}")
            .blocks()
            .isEmpty());
    assertTrue(parser.parse("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"\"}}").blocks().isEmpty());
    assertTrue(parser.parse("{\"type\":\"mode\",\"mode\":\"default\"}").blocks().isEmpty());
  }

  @Test
  void whatAnMcpServerReturnsBesideTheTextIsKeptWithItsCall() {
    String result =
        "{\"type\":\"user\",\"mcpMeta\":%s,\"message\":{\"role\":\"user\",\"content\":["
            + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\"2 records\"}]}]}}";
    String meta = "{\"structuredContent\":{\"totalSize\":2,\"records\":[{\"id\":1},{\"id\":2}]},\"_meta\":{\"tags\":[\"read\"]}}";

    List<Block> blocks = parser.parse(String.format(result, meta)).blocks();
    assertEquals(List.of(TranscriptParser.TOOL_RESULT, TranscriptParser.MCP_META), blocks.stream().map(Block::kind).toList());
    assertEquals("2 records", blocks.get(0).text());
    assertEquals(meta, blocks.get(1).text());
    assertEquals("t1", blocks.get(1).toolUseId());

    assertEquals(1, parser.parse(String.format(result, "{}")).blocks().size(), "an empty one says nothing");
    assertEquals(1, parser.parse(String.format(result, "null")).blocks().size());
  }

  @Test
  void anAttributeIsAShortFactOrAbsent() {
    TranscriptParser.Line plain = parser.parse("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}");
    assertEquals(null, plain.attributes(), "a line that says nothing about itself has none");

    String path = "/x".repeat(400);
    TranscriptParser.Line line =
        parser.parse(
            "{\"type\":\"user\",\"toolDenialKind\":\"permission-rule\",\"interruptedMessageId\":\"m1\","
                + "\"permissionMode\":null,\"toolUseResult\":{\"filePath\":\"" + path + "\",\"status\":{\"nested\":1},"
                + "\"interrupted\":false},\"message\":{\"role\":\"user\",\"content\":\"hi\"}}");
    assertTrue(line.attributes().contains("\"tool_denial\":\"permission-rule\""));
    assertTrue(line.attributes().contains("\"interrupted\":true"));
    assertTrue(!line.attributes().contains("permission_mode") && !line.attributes().contains("status"));
    assertTrue(!line.attributes().contains("tool_interrupted"));
    assertTrue(line.attributes().length() < 400, "a long value is cut");
  }
}
