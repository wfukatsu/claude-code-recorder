package dev.ccrec.content;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Content-addressed text: SHA-256 of the UTF-8 bytes names it, gzip shrinks it, and fixed-size chunks
 * keep every stored value under the smallest BLOB a supported database offers.
 */
public final class ContentCodec {

  public static final String ENCODING = "gzip";

  /** SQL Server maps ScalarDB BLOB to VARBINARY(8000); 6000 leaves headroom on every adapter. */
  public static final int CHUNK_BYTES = 6000;

  private ContentCodec() {}

  public static String hash(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static List<byte[]> encode(String text) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    byte[] packed = buffer.toByteArray();
    List<byte[]> chunks = new ArrayList<>();
    for (int from = 0; from < packed.length; from += CHUNK_BYTES) {
      int to = Math.min(packed.length, from + CHUNK_BYTES);
      byte[] chunk = new byte[to - from];
      System.arraycopy(packed, from, chunk, 0, chunk.length);
      chunks.add(chunk);
    }
    return chunks;
  }

  public static String decode(List<byte[]> chunks) {
    ByteArrayOutputStream packed = new ByteArrayOutputStream();
    for (byte[] chunk : chunks) {
      packed.writeBytes(chunk);
    }
    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed.toByteArray()))) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
