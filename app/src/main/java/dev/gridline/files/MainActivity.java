package dev.gridline.files;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.gridline.files.core.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public final class MainActivity extends Activity {
  private FilesApp app;
  private LinearLayout root, body, statusBar;
  private TextView status, pathText, selectionText;
  private ListView list;
  private FileAdapter adapter;
  private Typeface sans, mono;
  private int bg, surface, ink, muted, border, selection, tab = 0, paneIndex = 0;
  private final Pane[] panes = {new Pane(), new Pane()};
  private final Set<String> selected = new HashSet<>();
  private List<Store.Entry> entries = new ArrayList<>(), shown = new ArrayList<>();
  private String query = "", sort = "Name";
  private boolean hidden, descending, searchResults, darkMode;
  private final AtomicInteger generation = new AtomicInteger();
  private String transferSignature = "";
  private final Map<String, Runnable> transferUpdates = new HashMap<>();
  private Store clipboardStore;
  private List<Store.Entry> clipboard = new ArrayList<>();
  private boolean cut;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable tick = new Runnable() {
    public void run() {
      if (hasWindowFocus()) {
        if (tab == 1)
          drawTransfers();
        else
          updateStatus();
      }
      handler.postDelayed(this, 1000);
    }
  };
  private static final class Pane {
    Store store;
    String path;
    final Deque<String> parents = new ArrayDeque<>();
    final Deque<String> titles = new ArrayDeque<>();
  }
  private Pane pane() {
    return panes[paneIndex];
  }
  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }
  @Override
  public void onCreate(Bundle state) {
    String themeMode = getPreferences(0).getString("theme", "System");
    boolean night = themeMode.equals("Dark")
        || (themeMode.equals("System")
            && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    setTheme(night ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    app = (FilesApp) getApplication();
    sans = getResources().getFont(R.font.plex_sans);
    mono = getResources().getFont(R.font.plex_mono);
    if (state != null) {
      paneIndex = state.getInt("pane");
      for (int i = 0; i < 2; i++) {
        String id = state.getString("store" + i);
        Store store = id == null ? null : app.connections.get(id);
        if (store != null) {
          panes[i].store = store;
          panes[i].path = state.getString("path" + i, store.root());
          ArrayList<String> parents = state.getStringArrayList("parents" + i);
          if (parents != null)
            panes[i].parents.addAll(parents);
        }
      }
    }
    draw();
    if (getSharedPreferences("state", 0).getBoolean("interrupted", false) && !app.busy()) {
      notice("Interrupted transfer",
          "Android stopped the previous session. Completed files remain. Inspect destination "
              + ".smbfiles-*.partial files before retrying; no source deletion is resumed "
              + "automatically.");
      getSharedPreferences("state", 0).edit().putBoolean("interrupted", false).apply();
    }
  }
  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("pane", paneIndex);
    for (int i = 0; i < 2; i++)
      if (panes[i].store != null) {
        b.putString("store" + i, panes[i].store.id());
        b.putString("path" + i, panes[i].path);
        b.putStringArrayList("parents" + i, new ArrayList<>(panes[i].parents));
      }
  }
  @Override
  public void onResume() {
    super.onResume();
    handler.post(tick);
  }
  @Override
  public void onPause() {
    handler.removeCallbacks(tick);
    super.onPause();
  }
  @Override
  public void onBackPressed() {
    if (tab != 0) {
      tab = 0;
      draw();
    } else if (!selected.isEmpty()) {
      selected.clear();
      filter();
    } else if (pane().store != null && !pane().parents.isEmpty())
      up();
    else
      super.onBackPressed();
  }
  private void palette() {
    String mode = getPreferences(0).getString("theme", "System");
    boolean dark = mode.equals("Dark")
        || (mode.equals("System")
            && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    darkMode = dark;
    bg = Color.parseColor(dark ? "#181818" : "#C6C6C6");
    surface = Color.parseColor(dark ? "#242424" : "#FFFFFF");
    ink = Color.parseColor(dark ? "#FFFFFF" : "#101010");
    muted = Color.parseColor(dark ? "#C6C6C6" : "#3D3D3D");
    border = Color.parseColor(dark ? "#666666" : "#9A9A9A");
    selection = Color.parseColor(dark ? "#24244A" : "#E8E8F8");
  }
  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }
  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }
  private TextView text(String value, int size, boolean measured) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextColor(ink);
    t.setTextSize(size);
    t.setTypeface(measured ? mono : sans);
    t.setPadding(dp(10), dp(7), dp(10), dp(7));
    return t;
  }
  private GradientDrawable box(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setStroke(dp(1), border);
    d.setCornerRadius(0);
    return d;
  }
  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(ink);
    b.setTextSize(14);
    b.setTypeface(sans);
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setMinWidth(dp(64));
    b.setPadding(dp(10), 0, dp(10), 0);
    b.setBackground(box(bg));
    b.setOnClickListener(v -> action.run());
    return b;
  }
  private void fill(LinearLayout row, View view) {
    row.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
  }
  private void strip(LinearLayout parent, String label) {
    TextView t = text(label, 13, true);
    t.setTextColor(Color.WHITE);
    t.setBackgroundColor(Color.rgb(0, 0, 168));
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
  }
  private void draw() {
    generation.incrementAndGet();
    transferSignature = "";
    transferUpdates.clear();
    palette();
    root = column();
    root.setBackgroundColor(bg);
    setContentView(root);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars =
            insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      } else
        v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets;
    });
    root.requestApplyInsets();
    LinearLayout header = row();
    TextView title = text("SMBITS", 20, true);
    title.setTextColor(Color.WHITE);
    header.setBackgroundColor(Color.rgb(0, 0, 168));
    fill(header, title);
    header.addView(button("Settings", this::settings));
    root.addView(header);
    body = column();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    statusBar = row();
    statusBar.setBackground(box(bg));
    status = text("", 11, true);
    fill(statusBar, status);
    root.addView(statusBar);
    LinearLayout nav = row();
    for (int i = 0; i < 3; i++) {
      final int n = i;
      Button b = button(new String[] {"Browse", "Transfers", "Connections"}[i], () -> {
        tab = n;
        draw();
      });
      if (i == tab)
        b.setBackground(box(selection));
      fill(nav, b);
    }
    root.addView(nav);
    if (tab == 0)
      drawBrowse();
    else if (tab == 1)
      drawTransfers();
    else
      drawConnections();
    updateStatus();
  }
  private void drawBrowse() {
    LinearLayout locations = row();
    for (int i = 0; i < 2; i++) {
      final int n = i;
      Pane p = panes[i];
      String label =
          (i == 0 ? "A · " : "B · ") + (p.store == null ? "Choose location" : p.store.label());
      Button b = button(label, () -> {
        paneIndex = n;
        selected.clear();
        query = "";
        draw();
        if (panes[n].store == null)
          chooseLocation();
      });
      b.setMaxLines(2);
      if (i == paneIndex)
        b.setBackground(box(selection));
      fill(locations, b);
    }
    body.addView(locations);
    Pane p = pane();
    pathText = text(p.store == null ? "Choose a local folder or connect to a share"
                                    : p.store.label() + " · " + p.store.displayPath(p.path),
        12, true);
    pathText.setTextIsSelectable(true);
    body.addView(pathText);
    LinearLayout toolbar = row();
    fill(toolbar, button("Up", this::up));
    fill(toolbar, button("Location", this::chooseLocation));
    fill(toolbar, button("Refresh", this::load));
    fill(toolbar, button("Sort", this::sortDialog));
    body.addView(toolbar);
    EditText search = new EditText(this);
    search.setSingleLine();
    search.setTextColor(ink);
    search.setHintTextColor(muted);
    search.setTypeface(sans);
    search.setTextSize(15);
    search.setHint("Filter this folder");
    search.setBackground(box(surface));
    search.setPadding(dp(12), dp(8), dp(12), dp(8));
    search.setText(query);
    body.addView(search);
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
      public void onTextChanged(CharSequence s, int a, int b, int c) {
        query = s.toString();
        filter();
      }
      public void afterTextChanged(Editable e) {}
    });
    LinearLayout info = row();
    selectionText = text("", 12, true);
    fill(info, selectionText);
    info.addView(button("Select all", () -> {
      for (Store.Entry e : shown) selected.add(e.path());
      filter();
    }));
    body.addView(info);
    list = new ListView(this);
    list.setBackgroundColor(surface);
    list.setDivider(new android.graphics.drawable.ColorDrawable(border));
    list.setDividerHeight(dp(1));
    adapter = new FileAdapter();
    list.setAdapter(adapter);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView empty = text(p.store == null
            ? "Browse local folders and SMB shares\n\nUse Location to choose a folder or enter a "
                + "UNC path. Locations A and B keep separate browsing positions."
            : "No matching items",
        16, false);
    empty.setGravity(Gravity.CENTER);
    empty.setBackgroundColor(surface);
    body.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
    list.setEmptyView(empty);
    list.setOnItemClickListener((a, v, position, id) -> {
      Store.Entry e = shown.get(position);
      if (!selected.isEmpty())
        toggle(e);
      else if (e.directory())
        enter(e);
      else
        properties(e);
    });
    list.setOnItemLongClickListener((a, v, position, id) -> {
      toggle(shown.get(position));
      return true;
    });
    LinearLayout actions = row();
    fill(actions, button("Copy", () -> copySelection(false)));
    fill(actions, button("Cut", () -> copySelection(true)));
    fill(actions, button("Paste", this::paste));
    fill(actions, button("More", this::actions));
    body.addView(actions);
    load();
  }
  private void setIfChanged(TextView view, String value) {
    if (!view.getText().toString().equals(value))
      view.setText(value);
  }
  private void updateStatus() {
    if (status == null)
      return;
    int count = 0;
    double rate = 0;
    for (Transfer.Job j : app.jobs)
      if (j.state == Transfer.State.RUNNING || j.state == Transfer.State.PAUSED
          || j.state == Transfer.State.QUEUED || j.state == Transfer.State.SCANNING) {
        count++;
        rate += j.rate;
      }
    setIfChanged(status,
        count > 0                 ? count + " ACTIVE · " + size((long) rate) + "/s"
            : clipboard.isEmpty() ? "READY · 0.1.0 ALPHA"
                                  : clipboard.size()
                + (clipboard.size() == 1 ? " ITEM TO " : " ITEMS TO ") + (cut ? "MOVE" : "COPY"));
  }
  private void load() {
    if (tab != 0)
      return;
    int request = generation.incrementAndGet();
    Pane p = pane();
    selected.clear();
    entries = new ArrayList<>();
    filter();
    if (p.store == null) {
      selectionText.setText("No location selected");
      return;
    }
    Store store = p.store;
    String path = p.path;
    searchResults = false;
    selectionText.setText("Loading…");
    app.browse.execute(() -> {
      long start = System.nanoTime();
      try {
        List<Store.Entry> result = store.list(path);
        app.browsingLatency((System.nanoTime() - start) / 1e6);
        runOnUiThread(() -> {
          if (request != generation.get() || isFinishing())
            return;
          entries = result;
          filter();
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          if (request != generation.get() || isFinishing())
            return;
          selectionText.setText("Folder unavailable");
          notice("Cannot browse", Transfer.safeMessage(e));
        });
      }
    });
  }
  private void filter() {
    shown = new ArrayList<>();
    String q = query.toLowerCase(Locale.ROOT);
    for (Store.Entry e : entries)
      if ((hidden || !e.name().startsWith(".")) && e.name().toLowerCase(Locale.ROOT).contains(q))
        shown.add(e);
    Comparator<Store.Entry> comparator = sort.equals("Size")
        ? Comparator.comparingLong(Store.Entry::size)
        : sort.equals("Modified")
        ? Comparator.comparingLong(Store.Entry::modified)
        : Comparator.comparing(Store.Entry::name, String.CASE_INSENSITIVE_ORDER);
    if (descending)
      comparator = comparator.reversed();
    shown.sort(Comparator.comparing((Store.Entry e) -> !e.directory()).thenComparing(comparator));
    if (adapter != null)
      adapter.notifyDataSetChanged();
    if (selectionText != null)
      selectionText.setText(
          selected.isEmpty() ? shown.size() + " ITEMS" : selected.size() + " SELECTED");
  }
  private void toggle(Store.Entry e) {
    if (!selected.add(e.path()))
      selected.remove(e.path());
    filter();
  }
  private void enter(Store.Entry e) {
    if (e.link()) {
      notice("Link", "Reparse points are not followed.");
      return;
    }
    pane().parents.push(pane().path);
    pane().path = e.path();
    query = "";
    draw();
  }
  private void up() {
    Pane p = pane();
    if (p.store == null)
      return;
    if (!p.parents.isEmpty())
      p.path = p.parents.pop();
    else
      p.path = p.store.root();
    query = "";
    selected.clear();
    draw();
  }
  private List<Store.Entry> selection() {
    List<Store.Entry> result = new ArrayList<>();
    for (Store.Entry e : entries)
      if (selected.contains(e.path()))
        result.add(e);
    return result;
  }
  private void sortDialog() {
    String[] options = {"Name", "Size", "Modified",
        descending ? "Ascending order" : "Descending order",
        hidden ? "Hide hidden files" : "Show hidden files"};
    new AlertDialog.Builder(this)
        .setTitle("Folder view")
        .setItems(options,
            (d, n) -> {
              if (n < 3)
                sort = options[n];
              else if (n == 3)
                descending = !descending;
              else
                hidden = !hidden;
              filter();
            })
        .show();
  }
  private void copySelection(boolean move) {
    if (selected.isEmpty()) {
      notice("Select files", "Long-press a row or use its checkbox first.");
      return;
    }
    clipboard = selection();
    clipboardStore = pane().store;
    cut = move;
    selected.clear();
    filter();
    updateStatus();
    Toast.makeText(this, "Choose a destination, then Paste", Toast.LENGTH_SHORT).show();
  }
  private void paste() {
    if (clipboard.isEmpty() || pane().store == null) {
      notice("Paste", "Copy or cut items, then choose a destination folder.");
      return;
    }
    if (app.mutation) {
      notice("File operation active", "Wait for the current operation to finish.");
      return;
    }
    Store source = clipboardStore, destination = pane().store;
    String target = pane().path;
    List<Store.Entry> items = new ArrayList<>(clipboard);
    boolean move = cut;
    if (source.namespace().equals(destination.namespace()))
      for (Store.Entry item : items)
        if (item.directory()
            && (pane().parents.contains(item.path()) || target.equals(item.path()))) {
          notice("Destination", "Choose a folder outside the selected source.");
          return;
        }
    String[] conflicts = {"Report existing files", "Skip existing files",
        "Keep both with numbered names", "Replace existing files after verification"};
    new AlertDialog.Builder(this)
        .setTitle((move ? "Move " : "Copy ") + items.size() + " items")
        .setMessage("To " + destination.label() + " · " + destination.displayPath(target)
            + "\n\nFiles are verified with SHA-256. Choose how to handle existing names.")
        .setPositiveButton("Choose conflict policy",
            (d, w)
                -> new AlertDialog.Builder(this)
                    .setTitle("Existing files")
                    .setItems(conflicts,
                        (dialog, n) -> {
                          if (Build.VERSION.SDK_INT >= 33
                              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(
                                new String[] {Manifest.permission.POST_NOTIFICATIONS}, 3);
                          int workers = getPreferences(0).getInt("workers", 0);
                          long cap = getPreferences(0).getLong("cap", 0);
                          Transfer.Job job = new Transfer.Job(source, items, destination, target,
                              move, Transfer.Conflict.values()[n], workers, cap);
                          app.jobs.add(job);
                          startForegroundService(new Intent(this, TransferService.class));
                          if (move)
                            clipboard = new ArrayList<>();
                          tab = 1;
                          draw();
                        })
                    .setNegativeButton("Cancel", null)
                    .show())
        .setNegativeButton("Cancel", null)
        .show();
  }
  private void actions() {
    String[] choices = {"New folder", "Rename selected", "Delete selected", "Properties / open",
        "Search subfolders", "Clear selection"};
    new AlertDialog.Builder(this)
        .setTitle("Files")
        .setItems(choices,
            (d, n) -> {
              if (pane().store == null)
                return;
              if (n == 0) {
                Store targetStore = pane().store;
                String targetPath = pane().path;
                nameDialog(
                    "New folder", "", name -> mutate(() -> targetStore.mkdir(targetPath, name)));
              }
              if (n == 1) {
                if (searchResults) {
                  notice("Rename", "Open the containing folder before renaming a search result.");
                  return;
                }
                List<Store.Entry> s = selection();
                if (s.size() != 1) {
                  notice("Rename", "Select one item.");
                  return;
                }
                Store.Entry e = s.get(0);
                Store store = pane().store;
                String dir = pane().path;
                nameDialog(
                    "Rename", e.name(), name -> mutate(() -> store.rename(e.path(), dir, name)));
              }
              if (n == 2)
                deleteSelection();
              if (n == 3) {
                List<Store.Entry> s = selection();
                if (s.size() == 1)
                  properties(s.get(0));
                else
                  notice("Properties", "Select one item.");
              }
              if (n == 4)
                recursiveSearch();
              if (n == 5) {
                selected.clear();
                filter();
              }
            })
        .show();
  }
  private interface Named {
    void accept(String name);
  }
  private interface Work {
    void run() throws Exception;
  }
  private void nameDialog(String title, String value, Named action) {
    EditText field = new EditText(this);
    field.setSingleLine();
    field.setText(value);
    field.setSelectAllOnFocus(true);
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle(title)
                             .setView(field)
                             .setPositiveButton("Save", null)
                             .setNegativeButton("Cancel", null)
                             .create();
    dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
      try {
        String n = Store.name(field.getText().toString());
        dialog.dismiss();
        action.accept(n);
      } catch (Exception e) {
        field.setError(e.getMessage());
      }
    }));
    dialog.show();
  }
  private void mutate(Work work) {
    if (app.busy() || app.mutation) {
      notice("Operation in progress", "Finish or cancel active transfers before changing files.");
      return;
    }
    app.mutation = true;
    app.operations.execute(() -> {
      try {
        work.run();
        runOnUiThread(() -> {
          if (tab == 0)
            load();
        });
      } catch (Exception e) {
        runOnUiThread(() -> notice("Operation failed", Transfer.safeMessage(e)));
      } finally {
        app.mutation = false;
      }
    });
  }
  private void deleteSelection() {
    List<Store.Entry> items = selection();
    Store store = pane().store;
    if (items.isEmpty())
      return;
    String names = String.join("\n",
        items.stream()
            .limit(8)
            .map(Store.Entry::name)
            .collect(java.util.stream.Collectors.toList()));
    new AlertDialog.Builder(this)
        .setTitle("Permanently delete " + items.size() + " items?")
        .setMessage(names + "\n\nFolders include their contents. This app cannot undo deletion.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete permanently", (d, w) -> mutate(() -> {
          for (Store.Entry e : items) deleteTree(store, e, 0);
        }))
        .show();
  }
  private void deleteTree(Store store, Store.Entry e, int depth) throws Exception {
    if (depth > 128 || e.link())
      throw new java.io.IOException("Refusing deep recursion or link traversal");
    if (e.directory())
      for (Store.Entry child : store.list(e.path())) deleteTree(store, child, depth + 1);
    store.delete(e.path(), e.directory());
  }
  private void properties(Store.Entry e) {
    Store store = pane().store;
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle(e.name())
            .setMessage((e.directory() ? "Folder" : "File") + "\n" + size(e.size()) + "\nModified: "
                + date(e.modified()) + "\n\n" + store.label() + " · " + store.displayPath(e.path()))
            .setNegativeButton("Close", null);
    if (!e.directory() && store instanceof LocalStore local) {
      b.setPositiveButton("Open", (d, w) -> {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(local.uri(e.path()), mime(e.name()))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
          startActivity(Intent.createChooser(intent, "Open file"));
        } catch (Exception error) {
          notice("Open", "No installed app can open this file.");
        }
      });
      b.setNeutralButton("Share", (d, w) -> {
        Intent intent = new Intent(Intent.ACTION_SEND)
                            .setType(mime(e.name()))
                            .putExtra(Intent.EXTRA_STREAM, local.uri(e.path()))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(e.name(), local.uri(e.path())));
        startActivity(Intent.createChooser(intent, "Share file"));
      });
    } else if (!e.directory())
      b.setPositiveButton("Copy to local folder", (d, w) -> {
        clipboardStore = store;
        clipboard = Collections.singletonList(e);
        cut = false;
        chooseLocation();
      });
    b.show();
  }
  private String mime(String name) {
    int dot = name.lastIndexOf('.');
    String m = dot < 0 ? null
                       : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                             name.substring(dot + 1).toLowerCase(Locale.ROOT));
    return m == null ? "application/octet-stream" : m;
  }
  private void recursiveSearch() {
    EditText input = new EditText(this);
    input.setSingleLine();
    input.setHint("Name contains");
    new AlertDialog.Builder(this)
        .setTitle("Search subfolders")
        .setView(input)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Search",
            (d, w) -> {
              String term = input.getText().toString().trim().toLowerCase(Locale.ROOT);
              if (term.isEmpty())
                return;
              Store store = pane().store;
              String folder = pane().path;
              int request = generation.incrementAndGet();
              selectionText.setText("Searching… Refresh stops search");
              app.browse.execute(() -> {
                List<Store.Entry> found = new ArrayList<>();
                try {
                  searchTree(store, folder, term, found, 0, request, new int[] {0});
                  runOnUiThread(() -> {
                    if (request != generation.get())
                      return;
                    entries = found;
                    searchResults = true;
                    query = "";
                    selected.clear();
                    filter();
                    selectionText.setText(found.size() + " RESULTS · UP TO 1,000");
                  });
                } catch (Exception error) {
                  runOnUiThread(() -> {
                    if (request == generation.get())
                      notice("Search", Transfer.safeMessage(error));
                  });
                }
              });
            })
        .show();
  }
  private void searchTree(Store store, String folder, String term, List<Store.Entry> out, int depth,
      int request, int[] visited) throws Exception {
    if (depth > 64 || out.size() >= 1000 || request != generation.get())
      return;
    if (++visited[0] > 10000)
      throw new java.io.IOException("Search reached 10,000 folders; narrow the search.");
    for (Store.Entry e : store.list(folder)) {
      if (request != generation.get() || out.size() >= 1000)
        return;
      if (e.name().toLowerCase(Locale.ROOT).contains(term))
        out.add(e);
      if (e.directory() && !e.link())
        searchTree(store, e.path(), term, out, depth + 1, request, visited);
    }
  }
  private void chooseLocation() {
    String[] options = {"Choose local folder", "Connect to SMB share", "Saved locations"};
    new AlertDialog.Builder(this)
        .setTitle("Location " + (paneIndex == 0 ? "A" : "B"))
        .setItems(options,
            (d, n) -> {
              if (n == 0)
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                           .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                               | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                               | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                    10);
              if (n == 1)
                connectDialog(null);
              if (n == 2) {
                tab = 2;
                draw();
              }
            })
        .show();
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 10 || result != RESULT_OK || data == null || data.getData() == null)
      return;
    Uri uri = data.getData();
    try {
      int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION;
      if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
        grants |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
      getContentResolver().takePersistableUriPermission(uri, grants);
      LocalStore local = new LocalStore(this, uri, "Local folder");
      String title = local.stat(local.root()).name();
      JSONObject profile = new JSONObject()
                               .put("id", UUID.randomUUID().toString())
                               .put("type", "local")
                               .put("uri", uri.toString())
                               .put("label", title);
      saveProfile(profile);
      use(new LocalStore(this, uri, title), local.root());
    } catch (Exception e) {
      notice("Folder access", Transfer.safeMessage(e));
    }
  }
  private void use(Store store, String path) {
    app.connections.put(store.id(), store);
    app.liveStores.add(store);
    pane().store = store;
    pane().path = path;
    pane().parents.clear();
    selected.clear();
    query = "";
    tab = 0;
    draw();
  }
  private JSONArray profiles() {
    try {
      return new JSONArray(getSharedPreferences("locations", 0).getString("items", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }
  private void saveProfile(JSONObject profile) {
    JSONArray old = profiles(), next = new JSONArray();
    boolean replaced = false;
    for (int i = 0; i < old.length(); i++) {
      JSONObject p = old.optJSONObject(i);
      if (p.optString("id").equals(profile.optString("id"))) {
        next.put(profile);
        replaced = true;
      } else
        next.put(p);
    }
    if (!replaced)
      next.put(profile);
    getSharedPreferences("locations", 0).edit().putString("items", next.toString()).apply();
  }
  private void drawConnections() {
    body.removeAllViews();
    strip(body, "LOCAL FOLDERS & SMB SHARES");
    LinearLayout actions = row();
    fill(actions,
        button("Add local folder",
            ()
                -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                  | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                  | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                    10)));
    fill(actions, button("Add SMB share", () -> connectDialog(null)));
    body.addView(actions);
    body.addView(text("Connections open in location " + (paneIndex == 0 ? "A" : "B")
            + ". Password saving is optional.",
        14, false));
    ScrollView scroll = new ScrollView(this);
    LinearLayout cards = column();
    scroll.addView(cards);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    JSONArray profiles = profiles();
    if (profiles.length() == 0)
      cards.addView(
          text("No saved locations. Add a folder or an authenticated SMB share.", 16, false));
    for (int i = 0; i < profiles.length(); i++) {
      JSONObject profile = profiles.optJSONObject(i);
      LinearLayout card = column();
      card.setBackground(box(surface));
      card.addView(text(profile.optString("label"), 17, false));
      card.addView(text(profile.optString("type").equals("local")
              ? "LOCAL · FOLDER ACCESS"
              : profile.optString("unc") + "\n" + profile.optString("security"),
          12, true));
      LinearLayout actionsRow = row();
      fill(actionsRow, button("Open", () -> {
        if (profile.optString("type").equals("local")) {
          LocalStore local =
              new LocalStore(this, Uri.parse(profile.optString("uri")), profile.optString("label"));
          use(local, local.root());
        } else
          connectDialog(profile);
      }));
      fill(actionsRow,
          button("Forget",
              ()
                  -> new AlertDialog.Builder(this)
                      .setTitle("Forget this location?")
                      .setMessage("Saved credentials will also be removed. Files are unchanged.")
                      .setNegativeButton("Cancel", null)
                      .setPositiveButton("Forget",
                          (d, w) -> {
                            JSONArray all = profiles(), kept = new JSONArray();
                            for (int k = 0; k < all.length(); k++)
                              if (!all.optJSONObject(k).optString("id").equals(
                                      profile.optString("id")))
                                kept.put(all.optJSONObject(k));
                            getSharedPreferences("locations", 0)
                                .edit()
                                .putString("items", kept.toString())
                                .apply();
                            new Vault(this).remove(profile.optString("id"));
                            if (profile.optString("type").equals("local"))
                              try {
                                getContentResolver().releasePersistableUriPermission(
                                    Uri.parse(profile.optString("uri")),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                              } catch (Exception ignored) {
                              }
                            drawConnections();
                          })
                      .show()));
      card.addView(actionsRow);
      cards.addView(card);
    }
    body.addView(button("Disconnect idle SMB sessions", () -> {
      if (app.busy() || app.mutation) {
        notice("Active work", "Finish or cancel file operations first.");
        return;
      }
      app.operations.execute(() -> {
        for (Store store : new ArrayList<>(app.liveStores))
          if (store instanceof SmbStore) {
            try {
              store.close();
            } catch (Exception ignored) {
            }
            app.connections.remove(store.id());
            app.liveStores.remove(store);
          }
        runOnUiThread(() -> {
          for (Pane p : panes)
            if (p.store instanceof SmbStore) {
              p.store = null;
              p.path = null;
              p.parents.clear();
            }
          clipboard = new ArrayList<>();
          notice("Disconnected", "Idle SMB sessions closed.");
        });
      });
    }));
  }
  private EditText input(LinearLayout form, String label, String value, boolean password) {
    form.addView(text(label, 13, false));
    EditText e = new EditText(this);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    e.setTextSize(15);
    e.setTypeface(mono);
    e.setSingleLine();
    e.setBackground(box(surface));
    e.setPadding(dp(10), dp(10), dp(10), dp(10));
    e.setInputType(password ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    e.setText(value);
    form.addView(e);
    return e;
  }
  private void connectDialog(JSONObject existing) {
    JSONObject profile = existing == null ? new JSONObject() : existing;
    String id = profile.optString("id", UUID.randomUUID().toString());
    LinearLayout form = column();
    form.setPadding(dp(12), dp(4), dp(12), dp(12));
    form.setBackgroundColor(bg);
    EditText address =
        input(form, "UNC path", profile.optString("unc", "\\\\server\\share"), false);
    EditText portField = input(form, "Port", Integer.toString(profile.optInt("port", 445)), false);
    portField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    EditText username = input(form, "Username", profile.optString("user"), false);
    EditText domain = input(form, "Domain (optional)", profile.optString("domain"), false);
    EditText password = input(form, "Password", "", true);
    try {
      password.setText(new Vault(this).get(id));
    } catch (Exception e) {
      form.addView(text("Unlock the device and re-enter your password.", 13, false));
    }
    CheckBox save = new CheckBox(this);
    save.setText("Save password with Android Keystore");
    save.setTextColor(ink);
    save.setChecked(profile.optBoolean("saved", false));
    form.addView(save);
    CheckBox compatibility = new CheckBox(this);
    compatibility.setText("Signed SMB 2/3 compatibility (unencrypted data allowed)");
    compatibility.setTextColor(ink);
    compatibility.setChecked(profile.optString("security").equals("SIGNED"));
    form.addView(compatibility);
    form.addView(text("Default: SMB 3 encryption required. SMB 1, guest access, and automatic "
            + "security downgrades are disabled.",
        13, false));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle("Connect to SMB share")
                             .setView(scroll)
                             .setNegativeButton("Cancel", null)
                             .setPositiveButton("Connect", null)
                             .create();
    dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
      final Unc unc;
      try {
        unc = Unc.parse(address.getText().toString());
      } catch (Exception e) {
        address.setError(e.getMessage());
        return;
      }
      final int port;
      try {
        port = Integer.parseInt(portField.getText().toString());
        if (port < 1 || port > 65535)
          throw new IllegalArgumentException();
      } catch (Exception e) {
        portField.setError("Port 1–65535");
        return;
      }
      if (username.getText().toString().trim().isEmpty()) {
        username.setError("Username required");
        return;
      }
      String addressValue = address.getText().toString(), user = username.getText().toString(),
             dom = domain.getText().toString();
      char[] pass = password.getText().toString().toCharArray();
      boolean remember = save.isChecked();
      SmbStore.Security security =
          compatibility.isChecked() ? SmbStore.Security.SIGNED : SmbStore.Security.ENCRYPTED;
      int chosenPane = paneIndex;
      dialog.getButton(-1).setEnabled(false);
      dialog.getButton(-1).setText("Connecting…");
      dialog.setCancelable(false);
      dialog.getButton(-2).setEnabled(false);
      app.browse.execute(() -> {
        try {
          String secret = remember ? new String(pass) : "";
          SmbStore store = new SmbStore(unc, user, pass, dom, security, port);
          try {
            store.list(unc.path());
          } catch (Exception failure) {
            store.close();
            throw failure;
          }
          JSONObject p = new JSONObject()
                             .put("id", id)
                             .put("type", "smb")
                             .put("label", unc.host() + " / " + unc.share())
                             .put("unc", addressValue)
                             .put("user", user)
                             .put("domain", dom)
                             .put("port", port)
                             .put("security", security.name())
                             .put("saved", remember);
          if (remember)
            new Vault(this).put(id, secret);
          else
            new Vault(this).remove(id);
          runOnUiThread(() -> {
            saveProfile(p);
            password.setText("");
            dialog.dismiss();
            paneIndex = chosenPane;
            use(store, unc.path());
            Toast.makeText(this, store.negotiated(), Toast.LENGTH_LONG).show();
          });
        } catch (Exception e) {
          runOnUiThread(() -> {
            dialog.setCancelable(true);
            dialog.getButton(-2).setEnabled(true);
            dialog.getButton(-1).setEnabled(true);
            dialog.getButton(-1).setText("Connect");
            notice("Connection failed", Transfer.safeMessage(e));
          });
        } finally {
          Arrays.fill(pass, '\0');
        }
      });
    }));
    dialog.show();
  }
  private int stateColor(Transfer.State state) {
    if (state == Transfer.State.DONE)
      return Color.parseColor(darkMode ? "#7DDDA3" : "#18743A");
    if (state == Transfer.State.PAUSED || state == Transfer.State.ATTENTION)
      return Color.parseColor(darkMode ? "#F0BD55" : "#8A5900");
    if (state == Transfer.State.RUNNING || state == Transfer.State.SCANNING)
      return Color.parseColor(darkMode ? "#5ED9E8" : "#006C7A");
    return ink;
  }
  private void drawTransfers() {
    if (body == null || tab != 1)
      return;
    StringBuilder signature = new StringBuilder("jobs:");
    for (Transfer.Job j : app.jobs) signature.append(j.id).append(j.state).append(j.pause);
    if (signature.toString().equals(transferSignature)) {
      for (Runnable update : transferUpdates.values()) update.run();
      updateStatus();
      return;
    }
    transferSignature = signature.toString();
    transferUpdates.clear();
    body.removeAllViews();
    strip(body, "TRANSFER QUEUE");
    ScrollView scroll = new ScrollView(this);
    LinearLayout jobs = column();
    scroll.addView(jobs);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (app.jobs.isEmpty()) {
      jobs.addView(text("No transfers queued", 20, false));
      jobs.addView(
          text("Select files in Browse, choose Copy or Cut, then Paste into a destination folder.",
              15, false));
    }
    for (Transfer.Job j : app.jobs) {
      LinearLayout card = column();
      card.setBackground(box(surface));
      TextView heading = text((j.move ? "MOVE" : "COPY") + " · " + j.state, 15, true);
      heading.setTextColor(stateColor(j.state));
      card.addView(heading);
      card.addView(text(j.source.label() + " → " + j.destination.label() + "\n"
              + j.destination.displayPath(j.target),
          12, true));
      ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      progress.setProgressTintList(android.content.res.ColorStateList.valueOf(stateColor(j.state)));
      progress.setMax(1000);
      progress.setProgress(
          j.totalBytes == 0 ? 0 : (int) Math.min(1000, j.copied.get() * 1000 / j.totalBytes));
      card.addView(progress);
      TextView counters = text("", 12, true), tuning = text("", 12, true);
      card.addView(counters);
      card.addView(tuning);
      Runnable update = () -> {
        progress.setProgress(
            j.totalBytes == 0 ? 0 : (int) Math.min(1000, j.copied.get() * 1000 / j.totalBytes));
        setIfChanged(counters,
            j.completed + " / " + j.totalFiles + " VERIFIED · " + j.skipped + " SKIPPED\n"
                + size(j.copied.get()) + " / " + size(j.totalBytes) + " · " + size((long) j.rate)
                + "/s");
        setIfChanged(tuning,
            j.activeWorkers.get() + " ACTIVE / " + j.controller.workers() + " TARGET / "
                + j.controller.ceiling() + " CEILING · I/O P95 "
                + (j.p95 > 0 ? String.format(Locale.ROOT, "%.1f ms", j.p95) : "—") + "\n"
                + ((j.state == Transfer.State.DONE || j.state == Transfer.State.ATTENTION
                       || j.state == Transfer.State.CANCELED)
                        ? "Final concurrency limit"
                        : j.controller.reason())
                + "\n" + j.tuningContext);
      };
      transferUpdates.put(j.id, update);
      update.run();
      LinearLayout buttons = row();
      if (j.state == Transfer.State.RUNNING || j.state == Transfer.State.PAUSED
          || j.state == Transfer.State.SCANNING || j.state == Transfer.State.QUEUED) {
        fill(buttons, button(j.pause ? "Resume" : "Pause", () -> j.pause = !j.pause));
        fill(buttons, button("Cancel", j::cancel));
      } else {
        fill(buttons,
            button("Details",
                ()
                    -> notice("Transfer details",
                        j.errors.isEmpty() ? "Completed: " + j.completed + "\nSkipped: " + j.skipped
                                + "\nState: " + j.state
                                           : String.join("\n\n", j.errors))));
        fill(buttons, button("Dismiss", () -> {
          app.jobs.remove(j);
          drawTransfers();
        }));
      }
      card.addView(buttons);
      jobs.addView(card);
    }
    updateStatus();
  }
  private void settings() {
    LinearLayout form = column();
    form.setPadding(dp(12), 0, dp(12), dp(12));
    form.setBackgroundColor(bg);
    EditText workers = input(form, "Parallel file ceiling (0 = Auto, 1–128)",
        Integer.toString(getPreferences(0).getInt("workers", 0)), false);
    workers.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    EditText cap = input(form, "Transfer I/O cap in MiB/s (0 = unlimited)",
        Long.toString(getPreferences(0).getLong("cap", 0) / (1024 * 1024)), false);
    cap.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    form.addView(text("Auto uses memory headroom, up to 128 files. New profiles start at two; "
            + "familiar links use saved experience. Verification reads count toward the I/O cap. "
            + "Rate-limited jobs do not train link history.",
        13, false));
    EditText route = input(form, "Route label (e.g. Home Wi-Fi or X VPN)",
        app.tuning.settings().getString("route", ""), false);
    route.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    form.addView(text("Use distinct labels for different VPNs. Android may hide VPN identity, "
            + "radio type or signal. Unlabeled VPNs do not save history.",
        13, false));
    Spinner quality = new Spinner(this);
    quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
        new String[] {"Signal: automatic", "Signal: weak", "Signal: fair", "Signal: good",
            "Signal: excellent"}));
    quality.setSelection(app.tuning.settings().getInt("quality", 0));
    form.addView(quality);
    form.addView(text(app.tuning.description(), 13, false));
    form.addView(button("Forget tuning history", () -> {
      app.tuning.clear();
      Toast.makeText(this, "Tuning history cleared", Toast.LENGTH_SHORT).show();
    }));
    Spinner theme = new Spinner(this);
    theme.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
        new String[] {"System", "Light", "Dark"}));
    theme.setSelection(Arrays.asList("System", "Light", "Dark")
            .indexOf(getPreferences(0).getString("theme", "System")));
    form.addView(theme);
    form.addView(button("Licenses and security", this::about));
    ScrollView settingsScroll = new ScrollView(this);
    settingsScroll.addView(form);
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle("Transfer settings")
                             .setView(settingsScroll)
                             .setNegativeButton("Cancel", null)
                             .setPositiveButton("Save", null)
                             .create();
    dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
      try {
        int w = Integer.parseInt(workers.getText().toString());
        long c = Long.parseLong(cap.getText().toString());
        if (w < 0 || w > 128 || c < 0 || c > 10000)
          throw new IllegalArgumentException();
        getPreferences(0)
            .edit()
            .putInt("workers", w)
            .putLong("cap", c * 1024 * 1024)
            .putString("theme", theme.getSelectedItem().toString())
            .apply();
        app.tuning.settings()
            .edit()
            .putString("route", route.getText().toString().trim())
            .putInt("quality", quality.getSelectedItemPosition())
            .apply();
        dialog.dismiss();
        recreate();
      } catch (Exception e) {
        workers.setError("Workers 0–128; cap 0–10000 MiB/s");
      }
    }));
    dialog.show();
  }
  private void about() {
    String[] names;
    try {
      names = getAssets().list("licenses");
    } catch (Exception e) {
      names = new String[0];
    }
    final String[] licenses = names;
    new AlertDialog.Builder(this)
        .setTitle("Licenses and security")
        .setMessage("SMBits · MIT\nSMBJ and ASN.1 · Apache-2.0\nBouncy Castle, MBassador, SLF4J "
            + "· permissive licenses\nIBM Plex · SIL OFL-1.1\n\nNo analytics. Credentials "
            + "are optional and encrypted with a Keystore key. Android folder permissions "
            + "limit local access. SMB 1 and guest sessions are refused.\n\nAlpha build: "
            + "provider behavior and device/network performance need field validation.")
        .setNegativeButton("Close", null)
        .setPositiveButton("License texts",
            (d, w)
                -> new AlertDialog.Builder(this)
                    .setTitle("Licenses")
                    .setItems(licenses,
                        (a, n) -> {
                          try (java.io.InputStream in =
                                   getAssets().open("licenses/" + licenses[n])) {
                            notice(licenses[n], readText(in));
                          } catch (Exception e) {
                            notice("License", Transfer.safeMessage(e));
                          }
                        })
                    .show())
        .show();
  }
  private String readText(java.io.InputStream in) throws java.io.IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) >= 0) out.write(b, 0, n);
    return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
  }
  private void notice(String title, String message) {
    if (isFinishing() || isDestroyed())
      return;
    TextView text = text(message, 15, false);
    text.setTextIsSelectable(true);
    text.setBackgroundColor(surface);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(text);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(scroll)
        .setPositiveButton("OK", null)
        .show();
  }
  private static String date(long value) {
    return value <= 0 ? "Unknown"
                      : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(new Date(value));
  }
  private static String size(long value) {
    if (value < 0)
      return "Unknown";
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
    double n = value;
    int i = 0;
    while (n >= 1024 && i < 4) {
      n /= 1024;
      i++;
    }
    return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", n, units[i]);
  }
  private final class ItemRow extends LinearLayout {
    final CheckBox check;
    final TextView name, detail;
    ItemRow() {
      super(MainActivity.this);
      setOrientation(HORIZONTAL);
      setGravity(Gravity.CENTER_VERTICAL);
      setMinimumHeight(dp(64));
      check = new CheckBox(MainActivity.this);
      check.setFocusable(false);
      check.setFocusableInTouchMode(false);
      addView(check);
      LinearLayout labels = column();
      name = text("", 16, false);
      name.setMaxLines(2);
      detail = text("", 11, true);
      detail.setTextColor(muted);
      labels.addView(name);
      labels.addView(detail);
      fill(this, labels);
    }
  }
  private final class FileAdapter extends BaseAdapter {
    public int getCount() {
      return shown.size();
    }
    public Object getItem(int i) {
      return shown.get(i);
    }
    public long getItemId(int i) {
      return i;
    }
    public View getView(int position, View old, android.view.ViewGroup parent) {
      Store.Entry e = shown.get(position);
      ItemRow r = old instanceof ItemRow ? (ItemRow) old : new ItemRow();
      r.setBackgroundColor(selected.contains(e.path()) ? selection : surface);
      r.check.setChecked(selected.contains(e.path()));
      r.check.setContentDescription("Select " + e.name());
      r.check.setOnClickListener(v -> toggle(e));
      r.name.setText(e.name());
      r.detail.setText((e.directory() ? "DIR" : size(e.size())) + " · " + date(e.modified())
          + (e.link() ? " · LINK" : ""));
      return r;
    }
  }
}
