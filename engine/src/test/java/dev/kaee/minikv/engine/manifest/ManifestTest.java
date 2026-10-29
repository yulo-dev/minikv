package dev.kaee.minikv.engine.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestTest {

  @TempDir Path dir;

  private void writeHistory() throws IOException {
    try (Manifest m = Manifest.open(dir)) {
      m.apply(Edit.flush(m.nextFileId(), 5));
      m.apply(Edit.flush(m.nextFileId(), 9));
      long merged = m.nextFileId();
      m.apply(new Edit(List.of(merged), List.of(1L, 2L), 0));
    }
  }

  @Test
  void editsSurviveReopen() throws IOException {
    writeHistory();
    try (Manifest m = Manifest.open(dir)) {
      Manifest.State s = m.state();
      assertThat(s.liveTables()).containsExactlyElementsOf(List.of(3L));
      assertThat(s.walRetiredThrough()).isEqualTo(9L);
      assertThat(m.nextFileId()).isEqualTo(4L);
    }
  }

  @Test
  void tornLastRecordIsDropped() throws IOException {
    writeHistory();
    Path file = dir.resolve(Manifest.FILE_NAME);
    long goodSize = Files.size(file);
    // A frame header promising 40 body bytes, followed by only 3 of them.
    Files.write(file, new byte[] {0, 0, 0, 40, 1, 2, 3, 4, 9, 9, 9}, StandardOpenOption.APPEND);

    try (Manifest m = Manifest.open(dir)) {
      assertThat(m.state().liveTables()).containsExactlyElementsOf(List.of(3L));
      m.apply(Edit.flush(m.nextFileId(), 12));
    }
    assertThat(Files.size(file) > goodSize).isTrue();
    try (Manifest m = Manifest.open(dir)) {
      assertThat(m.state().liveTables()).containsExactlyElementsOf(List.of(3L, 4L));
      assertThat(m.state().walRetiredThrough()).isEqualTo(12L);
    }
  }

  @Test
  void damageBeforeTheLastRecordIsAHardError() throws IOException {
    writeHistory();
    Path file = dir.resolve(Manifest.FILE_NAME);
    byte[] bytes = Files.readAllBytes(file);
    bytes[10] ^= 0x01; // inside the first record's body
    Files.write(file, bytes);

    assertThatThrownBy(() -> Manifest.open(dir))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("corrupt manifest");
  }
}
