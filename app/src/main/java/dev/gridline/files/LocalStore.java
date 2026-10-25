package dev.gridline.files;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import dev.gridline.files.core.Store;
import java.io.*;
import java.util.*;

/** Storage Access Framework: access is limited to folders chosen by the user. */
public final class LocalStore implements Store {
  private final ContentResolver resolver;
  private final Uri tree;
  private final String title;
  private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
  public LocalStore(Context context, Uri tree, String title) {
    this.resolver = context.getContentResolver();
    this.tree = tree;
    this.title = title;
  }
  public String id() {
    return "local:" + tree;
  }
  public String namespace() {
    return "local:" + tree.getAuthority();
  }
  public String label() {
    return title;
  }
  public String root() {
    return DocumentsContract.getTreeDocumentId(tree);
  }
  public String displayPath(String path) {
    return path.equals(root())          ? "/"
        : path.startsWith(root() + "/") ? path.substring(root().length() + 1)
                                        : path;
  }
  public Uri uri(String path) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, path);
  }
  private Entry entry(Cursor c) {
    return new Entry(c.getString(0), c.getString(1),
        DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)),
        c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4), false);
  }
  public List<Entry> list(String directory) throws IOException {
    List<Entry> items = new ArrayList<>();
    try (Cursor c =
             resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory),
                 COLUMNS, null, null, null)) {
      if (c == null)
        throw new IOException("The folder provider did not respond");
      while (c.moveToNext()) items.add(entry(c));
    }
    return items;
  }
  public Entry stat(String path) throws IOException {
    try (Cursor c = resolver.query(uri(path), COLUMNS, null, null, null)) {
      if (c == null || !c.moveToFirst())
        throw new FileNotFoundException("File no longer available");
      return entry(c);
    }
  }
  public String child(String directory, String name) throws IOException {
    Store.name(name);
    for (Entry e : list(directory))
      if (e.name().equalsIgnoreCase(name))
        return e.path();
    return null;
  }
  private String make(String directory, String name, String mime) throws IOException {
    Store.name(name);
    if (exists(directory, name))
      throw new IOException("An item with that name already exists");
    Uri result = DocumentsContract.createDocument(resolver, uri(directory), mime, name);
    if (result == null)
      throw new IOException("The provider could not create the item");
    String path = DocumentsContract.getDocumentId(result);
    if (!stat(path).name().equals(name)) {
      DocumentsContract.deleteDocument(resolver, result);
      throw new IOException("The provider changed the requested file name");
    }
    return path;
  }
  public String mkdir(String directory, String name) throws IOException {
    return make(directory, name, DocumentsContract.Document.MIME_TYPE_DIR);
  }
  public String create(String directory, String name) throws IOException {
    return make(directory, name, "application/octet-stream");
  }
  public InputStream read(String path) throws IOException {
    InputStream in = resolver.openInputStream(uri(path));
    if (in == null)
      throw new IOException("Cannot read file");
    return in;
  }
  public OutputStream write(String path) throws IOException {
    OutputStream out = resolver.openOutputStream(uri(path), "wt");
    if (out == null)
      throw new IOException("Cannot write file");
    return out;
  }
  public String rename(String path, String directory, String name) throws IOException {
    Store.name(name);
    if (exists(directory, name))
      throw new IOException("Destination already exists");
    Uri result = DocumentsContract.renameDocument(resolver, uri(path), name);
    if (result == null)
      throw new IOException("The provider refused the rename");
    String renamed = DocumentsContract.getDocumentId(result);
    if (!stat(renamed).name().equals(name))
      throw new IOException("The provider changed the destination name; inspect the folder");
    return renamed;
  }
  public void delete(String path, boolean directory) throws IOException {
    if (path.equals(root()))
      throw new IOException("Cannot delete a bookmarked root");
    if (directory && !list(path).isEmpty())
      throw new IOException("Directory is not empty");
    if (!DocumentsContract.deleteDocument(resolver, uri(path)))
      throw new IOException("The provider refused deletion");
  }
}
