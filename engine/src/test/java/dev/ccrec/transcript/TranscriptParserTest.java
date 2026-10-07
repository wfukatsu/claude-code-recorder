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
}
