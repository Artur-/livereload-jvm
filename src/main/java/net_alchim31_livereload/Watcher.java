package net_alchim31_livereload;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.barbarysoftware.watchservice.ClosedWatchServiceException;
import com.barbarysoftware.watchservice.StandardWatchEventKind;
import com.barbarysoftware.watchservice.WatchEvent;
import com.barbarysoftware.watchservice.WatchEvent.Kind;
import com.barbarysoftware.watchservice.WatchKey;
import com.barbarysoftware.watchservice.WatchService;
import com.barbarysoftware.watchservice.WatchableFile;

/**
 * @author dwayne
 * @see http://docs.oracle.com/javase/tutorial/essential/io/notification.html
 */
// TODO make a start/stop/join method
public class Watcher implements Runnable {
    private static final Logger LOG = Logger.getLogger(Watcher.class.getName());
    private final WatchService _watcher;
    private final Map<WatchKey, WatchableFile> _keys;
    private final Path _docroot;
    private final AtomicBoolean _running = new AtomicBoolean(false);

    public LRWebSocketHandler listener = null;
    private List<Pattern> _patterns;

    public Watcher(Path docroot) throws Exception {
        _docroot = docroot;
        _watcher = com.barbarysoftware.watchservice.WatchService
                .newWatchService();
        _keys = new HashMap<>();

        // System.out.format("Scanning %s ...\n", _docroot);
        registerAll(_docroot);
        // System.out.println("Done.");
    }

    private void notify(String path) throws Exception {
        if (_patterns != null) {
            for (Pattern p : _patterns) {
                LOG.finer("Testing pattern: " + p + " against string: " + path);
                if (p.matcher(path).matches()) {
                    LOG.fine("Skipping file: " + path + " thanks to pattern: "
                            + p);
                    return;
                }
            }
        }
        System.err.println("File " + path + " changed, triggering refresh");
        LRWebSocketHandler l = listener;
        if (l != null) {
            l.notifyChange(path);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        final WatchableFile file1 = new WatchableFile(dir.toFile());

        WatchKey key = file1.register(_watcher,
                StandardWatchEventKind.ENTRY_CREATE,
                StandardWatchEventKind.ENTRY_DELETE,
                StandardWatchEventKind.ENTRY_MODIFY);
        _keys.put(key, file1);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attrs) throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void start() throws Exception {
        if (_running.compareAndSet(false, true)) {
            Thread t = new Thread(this);
            t.setDaemon(true);
            t.start();
        }
    }

    void stop() throws Exception {
        _running.set(false);
        _watcher.close();
    }

    /**
     * Process all events for keys queued to the watcher
     *
     * @throws Exception
     */
    @Override
    public void run() {
        try {
            while (_running.get()) {

                // wait for key to be signalled
                WatchKey key = _watcher.take();

                WatchableFile dir = _keys.get(key);
                if (dir == null) {
                    System.err.println("WatchKey not recognized!!");
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<WatchableFile> kind = (Kind<WatchableFile>) event
                            .kind();

                    // TBD - provide example of how OVERFLOW event is handled
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    // Context for directory entry event is the file name of
                    // entry
                    WatchEvent<File> ev = cast(event);
                    File name = ev.context();
                    Path child = name.toPath();
                    // Path child = new File(ev.context().getFile(),
                    // name.getFile().getName())
                    // .toPath();

                    System.out.format("%s: %s ++ %s / %s %s\n", kind.name(),
                            name, child, _docroot.relativize(child),
                            kind == StandardWatchEventKind.ENTRY_MODIFY);
                    if (kind == StandardWatchEventKind.ENTRY_MODIFY) {
                        notify(_docroot.relativize(child).toString());
                    } else if (kind == StandardWatchEventKind.ENTRY_CREATE) {
                        // if directory is created, and watching recursively,
                        // then
                        // register it and its sub-directories
                        if (Files.isDirectory(child,
                                LinkOption.NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    }
                }

                // reset key and remove from set if directory no longer
                // accessible
                boolean valid = key.reset();
                if (!valid) {
                    _keys.remove(key);

                    // all directories are inaccessible
                    if (_keys.isEmpty()) {
                        break;
                    }
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException exc) {
            // stop
        } catch (Exception exc) {
            exc.printStackTrace();
        } finally {
            _running.set(false);
        }
    }

    public void set_patterns(List<Pattern> _patterns) {
        this._patterns = _patterns;
    }

    public List<Pattern> get_patterns() {
        return _patterns;
    }
}
