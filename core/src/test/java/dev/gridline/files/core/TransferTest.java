package dev.gridline.files.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class TransferTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  static class Disk implements Store {
    final Path root;
    boolean corrupt, failWrite, failPublish, createRace;
    Disk(Path root) {
      this.root = root;
    }
    public String id() {
      return root.toString();
    }
    public String label() {
      return id();
    }
    public String root() {
      return root.toString();
    }
    public List<Entry> list(String path) throws IOException {
      List<Entry> r = new ArrayList<>();
      try (var paths = Files.list(Path.of(path))) {
        for (Path p : paths.toList()) r.add(stat(p.toString()));
      }
      return r;
    }
    public Entry stat(String path) throws IOException {
      Path p = Path.of(path);
      return new Entry(path, p.getFileName().toString(),
          Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS), Files.size(p),
          Files.getLastModifiedTime(p).toMillis(), Files.isSymbolicLink(p));
    }
    public String child(String dir, String name) {
      Path p = Path.of(dir, Store.name(name));
      return Files.exists(p, LinkOption.NOFOLLOW_LINKS) ? p.toString() : null;
    }
    public String mkdir(String dir, String name) throws IOException {
      return Files.createDirectory(Path.of(dir, Store.name(name))).toString();
    }
    public String create(String dir, String name) throws IOException {
      return Files.createFile(Path.of(dir, Store.name(name))).toString();
    }
    public InputStream read(String path) throws IOException {
      if (corrupt && path.endsWith(".partial"))
        return new ByteArrayInputStream(new byte[] {42});
      return Files.newInputStream(Path.of(path));
    }
    public OutputStream write(String path) throws IOException {
      if (failWrite)
        throw new IOException("Injected disk full");
      return Files.newOutputStream(Path.of(path));
    }
    public String rename(String path, String dir, String name) throws IOException {
      if (failPublish && path.endsWith(".partial"))
        throw new IOException("Injected publication failure");
      if (createRace && path.endsWith(".partial"))
        Files.writeString(Path.of(dir, name), "concurrent writer");
      return Files.move(Path.of(path), Path.of(dir, name)).toString();
    }
    public void delete(String path, boolean dir) throws IOException {
      Files.delete(Path.of(path));
    }
  }
  Disk src, dst;
  @Before
  public void setup() throws Exception {
    src = new Disk(temp.newFolder("source").toPath());
    dst = new Disk(temp.newFolder("dest").toPath());
  }
  private Path file(String name, String text) throws Exception {
    return Files.writeString(src.root.resolve(name), text);
  }
  private Transfer.Job job(boolean move, Transfer.Conflict conflict) throws Exception {
    return new Transfer.Job(src, src.list(src.root()), dst, dst.root(), move, conflict, 8, 0);
  }
  private void cleanPartials() throws Exception {
    assertTrue(dst.list(dst.root()).stream().noneMatch(e -> e.name().endsWith(".partial")));
  }
  @Test
  public void verifiesMultipleFilesAndEmptyDirectories() throws Exception {
    for (int i = 0; i < 24; i++) file("file-" + i, "data " + i);
    Files.createDirectory(src.root.resolve("empty"));
    Transfer.Job j = job(false, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.DONE, j.state);
    assertEquals(24, j.completed.get());
    assertTrue(Files.isDirectory(dst.root.resolve("empty")));
    for (int i = 0; i < 24; i++)
      assertEquals("data " + i, Files.readString(dst.root.resolve("file-" + i)));
    cleanPartials();
  }
  @Test
  public void corruptDestinationNeverDeletesMoveSource() throws Exception {
    Path source = file("important.txt", "original");
    dst.corrupt = true;
    Transfer.Job j = job(true, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertEquals("original", Files.readString(source));
    assertFalse(Files.exists(dst.root.resolve("important.txt")));
    cleanPartials();
  }
  @Test
  public void diskFullNeverDeletesSource() throws Exception {
    Path source = file("file", "source");
    dst.failWrite = true;
    Transfer.Job j = job(true, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertTrue(Files.exists(source));
    cleanPartials();
  }
  @Test
  public void verifiedMoveDeletesOnlyCompletedSource() throws Exception {
    Path source = file("file", "verified content");
    Transfer.Job j = job(true, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.DONE, j.state);
    assertFalse(Files.exists(source));
    assertEquals("verified content", Files.readString(dst.root.resolve("file")));
  }
  @Test
  public void conflictsPreserveExistingData() throws Exception {
    file("file", "new");
    Files.writeString(dst.root.resolve("file"), "old");
    Transfer.Job j = job(true, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertEquals("old", Files.readString(dst.root.resolve("file")));
    assertTrue(Files.exists(src.root.resolve("file")));
  }
  @Test
  public void skipMoveRetainsSourceAndReportsAttention() throws Exception {
    file("file", "new");
    Files.writeString(dst.root.resolve("file"), "old");
    Transfer.Job j = job(true, Transfer.Conflict.SKIP);
    new Transfer().run(j);
    assertEquals(1, j.skipped.get());
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertTrue(Files.exists(src.root.resolve("file")));
  }
  @Test
  public void keepBothUsesNewName() throws Exception {
    file("file.txt", "new");
    Files.writeString(dst.root.resolve("file.txt"), "old");
    Transfer.Job j = job(false, Transfer.Conflict.KEEP_BOTH);
    new Transfer().run(j);
    assertEquals("old", Files.readString(dst.root.resolve("file.txt")));
    assertEquals("new", Files.readString(dst.root.resolve("file (1).txt")));
    assertEquals(Transfer.State.DONE, j.state);
  }
  @Test
  public void replaceRollsBackWhenPublicationFails() throws Exception {
    file("file", "new");
    Files.writeString(dst.root.resolve("file"), "old");
    dst.failPublish = true;
    Transfer.Job j = job(true, Transfer.Conflict.REPLACE);
    new Transfer().run(j);
    assertEquals("old", Files.readString(dst.root.resolve("file")));
    assertTrue(Files.exists(src.root.resolve("file")));
    assertEquals(Transfer.State.ATTENTION, j.state);
    cleanPartials();
  }
  @Test
  public void replaceVerifiesBeforeRemovingOldDestination() throws Exception {
    file("file", "new");
    Files.writeString(dst.root.resolve("file"), "old");
    dst.corrupt = true;
    Transfer.Job j = job(false, Transfer.Conflict.REPLACE);
    new Transfer().run(j);
    assertEquals("old", Files.readString(dst.root.resolve("file")));
    assertEquals(Transfer.State.ATTENTION, j.state);
  }
  @Test
  public void replaceSuccessful() throws Exception {
    file("file", "new");
    Files.writeString(dst.root.resolve("file"), "old");
    Transfer.Job j = job(false, Transfer.Conflict.REPLACE);
    new Transfer().run(j);
    assertEquals("new", Files.readString(dst.root.resolve("file")));
    assertEquals(Transfer.State.DONE, j.state);
    assertEquals(1, dst.list(dst.root()).size());
  }
  @Test
  public void concurrentDestinationCreationIsNeverOverwritten() throws Exception {
    file("file", "new");
    dst.createRace = true;
    Transfer.Job j = job(false, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertEquals("concurrent writer", Files.readString(dst.root.resolve("file")));
    cleanPartials();
  }
  @Test
  public void canceledMoveKeepsSourceAndCleansPartial() throws Exception {
    Files.write(src.root.resolve("large"), new byte[1024 * 1024]);
    Transfer.Job j = new Transfer.Job(
        src, src.list(src.root()), dst, dst.root(), true, Transfer.Conflict.STOP, 4, 64 * 1024);
    Thread worker = new Thread(() -> new Transfer().run(j));
    worker.start();
    Thread.sleep(200);
    j.cancel();
    worker.join(3000);
    assertFalse(worker.isAlive());
    assertEquals(Transfer.State.CANCELED, j.state);
    assertTrue(Files.exists(src.root.resolve("large")));
    assertFalse(Files.exists(dst.root.resolve("large")));
    cleanPartials();
  }
  @Test
  public void pauseBlocksPublicationUntilResumed() throws Exception {
    file("file", "content");
    Transfer.Job j = job(false, Transfer.Conflict.STOP);
    j.pause = true;
    Thread worker = new Thread(() -> new Transfer().run(j));
    worker.start();
    Thread.sleep(150);
    assertFalse(Files.exists(dst.root.resolve("file")));
    j.pause = false;
    worker.join(3000);
    assertEquals(Transfer.State.DONE, j.state);
  }
  @Test
  public void selfCopyAndNestedDestinationAreRefused() throws Exception {
    Path dir = Files.createDirectory(src.root.resolve("folder"));
    Path child = Files.createDirectory(dir.resolve("nested"));
    Transfer.Job j = new Transfer.Job(src, List.of(src.stat(dir.toString())), src, child.toString(),
        false, Transfer.Conflict.STOP, 8, 0);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertFalse(Files.exists(child.resolve("folder")));
  }
  @Test
  public void symbolicLinksAreNotTraversed() throws Exception {
    Path external = temp.newFile("outside").toPath();
    Files.createSymbolicLink(src.root.resolve("link"), external);
    Transfer.Job j = job(false, Transfer.Conflict.STOP);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertEquals(0, j.completed.get());
  }
  @Test
  public void overlappingSelectionsAreRejectedBeforeCreatingDestinationFolders() throws Exception {
    Path dir = Files.createDirectory(src.root.resolve("folder"));
    Path nested = Files.writeString(dir.resolve("file"), "contents");
    Transfer.Job j =
        new Transfer.Job(src, List.of(src.stat(dir.toString()), src.stat(nested.toString())), dst,
            dst.root(), true, Transfer.Conflict.STOP, 8, 0);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertTrue(Files.exists(nested));
    assertTrue(dst.list(dst.root()).isEmpty());
  }
  @Test
  public void sourceMutationDuringCopyIsDetected() throws Exception {
    Path path = file("file", "before");
    Disk changing = new Disk(src.root) {
      @Override
      public InputStream read(String p) throws IOException {
        InputStream original = super.read(p);
        return new FilterInputStream(original) {
          @Override
          public void close() throws IOException {
            super.close();
            Files.writeString(Path.of(p), "after with a changed size");
          }
        };
      }
    };
    Transfer.Job j = new Transfer.Job(changing, changing.list(changing.root()), dst, dst.root(),
        true, Transfer.Conflict.STOP, 8, 0);
    new Transfer().run(j);
    assertEquals(Transfer.State.ATTENTION, j.state);
    assertTrue(Files.exists(path));
    assertFalse(Files.exists(dst.root.resolve("file")));
    cleanPartials();
  }
  @Test
  public void tuningRespondsToBrowseLatencyAndStaysBounded() {
    AdaptiveController c = new AdaptiveController(8);
    for (int i = 1; i < 40; i++) c.sample(i * 1000, 10, 0, false);
    assertEquals(8, c.workers());
    c.sample(41000, 10, 800, false);
    assertEquals(4, c.workers());
    for (int i = 0; i < 10; i++) c.sample(100, 1000, 800, true);
    assertEquals(1, c.workers());
  }
  @Test
  public void validatesUncAndRejectsTraversalAndCredentialUris() {
    assertEquals("docs\\file", Unc.parse("\\\\server\\share\\docs\\file").path());
    assertEquals("server", Unc.parse("smb://server/share").host());
    for (String bad : new String[] {"\\\\server", "smb://user:pass@host/share",
             "\\\\server\\share\\..\\escape", "\\\\server\\share\\file:stream"})
      assertThrows(IllegalArgumentException.class, () -> Unc.parse(bad));
  }
}
