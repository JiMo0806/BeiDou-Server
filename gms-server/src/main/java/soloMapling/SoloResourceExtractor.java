package soloMapling;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the runtime data files bundled under classpath:soloMapling-data/
 * into the working directory, so legacy file-path loaders keep working when
 * the server runs from a fat jar. Runs once per JVM.
 */
public final class SoloResourceExtractor {

    private static final String CLASSPATH_PATTERN = "classpath*:soloMapling-data/**";
    private static final String OUTPUT_DIR = "soloMapling-data";
    private static final String MARKER = "soloMapling-data/";

    private static volatile boolean done = false;

    private SoloResourceExtractor() {
    }

    public static synchronized void extractIfNeeded() {
        if (done) {
            return;
        }
        int count = 0;
        try {
            Path outRoot = Path.of(OUTPUT_DIR).toAbsolutePath().normalize();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource res : resolver.getResources(CLASSPATH_PATTERN)) {
                if (!res.isReadable()) {
                    continue;
                }
                String url = res.getURL().toString();
                int idx = url.indexOf(MARKER);
                if (idx < 0) {
                    continue;
                }
                String rel = url.substring(idx + MARKER.length());
                if (rel.isEmpty() || rel.endsWith("/")) {
                    continue; // directory entries
                }
                Path target = outRoot.resolve(rel).normalize();
                if (!target.startsWith(outRoot)) {
                    continue; // path traversal guard
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            done = true;
            BotLogger.log("Extracted " + count + " soloMapling data files to " + outRoot);
        } catch (Exception e) {
            System.err.println("[SoloResourceExtractor] extraction failed after " + count + " files: " + e);
            e.printStackTrace();
        }
    }
}
