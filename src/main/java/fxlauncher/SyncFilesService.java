package fxlauncher;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.concurrent.Task;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class SyncFilesService extends Task<Boolean> {

    private final DoubleProperty progress;
    private LongProperty total;
    private LongProperty written;
    private FXManifest manifest;

    public SyncFilesService(FXManifest manifest, LongProperty total, LongProperty written, DoubleProperty progress){
        this.total = total;
        this.written = written;
        this.manifest = manifest;
        this.progress = progress;
    }

    @Override
    protected Boolean call() throws Exception {
        List<LibraryFile> needsUpdate = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .filter(LibraryFile::needsUpdate)
                .collect(Collectors.toList());

        Long totalBytes = needsUpdate.stream().mapToLong(f -> f.size).sum();
        Long totalWritten = 0L;

        UpdateRunnable updateRunnable = new UpdateRunnable(total,written,progress);

        for (LibraryFile lib : needsUpdate) {
            Path target = Paths.get(lib.file).toAbsolutePath();
            Files.createDirectories(target.getParent());

            try (InputStream input = manifest.uri.resolve(lib.file).toURL().openStream();
                 OutputStream output = Files.newOutputStream(target)) {

                byte[] buf = new byte[65536];

                int read;
                while ((read = input.read(buf)) > -1) {
                    output.write(buf, 0, read);
                    totalWritten += read;
                    Double progress = totalWritten.doubleValue() / totalBytes.doubleValue();
                    updateRunnable.total = totalBytes;
                    updateRunnable.written = totalWritten;
                    updateRunnable.progress = progress;
                    Platform.runLater(updateRunnable);
                }
            }
        }
        return true;
    }

    /**
     * Hack to update the observable properties in the UI thread outside of this execution thread.
     */
    private class UpdateRunnable implements Runnable{

        public long total;
        public long written;
        public double progress;

        private DoubleProperty progressProp;
        private LongProperty totalProp;
        private LongProperty writtenProp;

        public UpdateRunnable(LongProperty total, LongProperty written, DoubleProperty progress){
            this.totalProp = total;
            this.writtenProp = written;
            this.progressProp = progress;
        }

        @Override
        public void run() {
            this.totalProp.setValue(total);
            this.writtenProp.setValue(written);
            this.progressProp.setValue(progress);
        }
    }
}
