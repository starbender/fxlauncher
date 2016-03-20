package fxlauncher;

import com.sun.javafx.application.ParametersImpl;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class LauncherWindowController extends AnchorPane{

    private Stage launcherWindow;
    private Application app;
    private static Application mainApplication;

    @FXML
    private Label status;
    @FXML
    private ProgressBar progressBar;

    private FXManifest manifest;

    private static Stage primaryStage;

    private DoubleProperty progress = new SimpleDoubleProperty(-1);
    private LongProperty totalBytesToUpdate = new SimpleLongProperty(-1);
    private LongProperty totalBytesToUpdateWritten = new SimpleLongProperty(0);

    private static final long MEGABYTE = 1024L * 1024L;

    public LauncherWindowController(Stage launcherWindow, Stage primaryStage, Application mainApplication){
        this.launcherWindow = launcherWindow;
        this.primaryStage = primaryStage;
        this.mainApplication = mainApplication;
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/loadingwindow.fxml"));
        fxmlLoader.setRoot(this);
        fxmlLoader.setController(this);
        try {
            fxmlLoader.load();
        } catch (IOException e) {
            e.printStackTrace(); // TODO handle exception
        }

        StringBinding stringBinding = Bindings.createStringBinding(() -> {
            String s = null;

            if (totalBytesToUpdate.get() > 0) {
                s = String.format("%.2f/%.2f KiloBytes",
                        totalBytesToUpdateWritten.floatValue() / 1024,
                        totalBytesToUpdate.floatValue() / 1024
                    );
            } else if (totalBytesToUpdate.get() == -1) {
                s = "Loading";
            } else {
                s = "No updates available";
            }
            return s;
        }, totalBytesToUpdate, totalBytesToUpdateWritten);

        progressBar = getProgressBar();
        progressBar.progressProperty().bind(progress);
        status.textProperty().bind(stringBinding);;
    }

    public LauncherWindowController(FXManifest manifest) {
        this.manifest = manifest;
    }

    public void launch() throws Exception {
        UpdateManifestService updateManifestService = new UpdateManifestService();

        updateManifestService.setOnSucceeded(e->{
            try {
                manifest = updateManifestService.get();
            } catch (InterruptedException e1) {
                e1.printStackTrace(); // TODO handle exception
            } catch (ExecutionException e1) {
                e1.printStackTrace(); // TODO handle exception
            }

            if (updateAvailable() && userWantsToUpdate()){
                SyncFilesService syncFilesService = new SyncFilesService(manifest, totalBytesToUpdate, totalBytesToUpdateWritten, progress);

                syncFilesService.setOnSucceeded(syncSuc->{
                    Boolean success = null;
                    try {
                        success = syncFilesService.get();
                    } catch (InterruptedException e1) {
                        e1.printStackTrace(); // TODO handle exception
                    } catch (ExecutionException e1) {
                        e1.printStackTrace(); // TODO handle exception
                    }

                    if (!success){
                        // TODO report to user
                    }

                    try {
                        createApplication();
                        launchAppFromManifest();
                    } catch (Exception e1) {
                        e1.printStackTrace(); // TODO handle exception
                    }
                });

                new Thread(syncFilesService).start();

            }else {
                try {
                    createApplication();
                    launchAppFromManifest();
                } catch (Exception e1) {
                    e1.printStackTrace(); // TODO handle exception
                }
            }
        });

        new Thread(updateManifestService).start();
    }

    public boolean updateAvailable(){
        List<LibraryFile> needsUpdate = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .filter(LibraryFile::needsUpdate)
                .collect(Collectors.toList());
        return needsUpdate.size() > 0;
    }

    private boolean userWantsToUpdate(){
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("There is an update available.");
        alert.setContentText("Would you like to update?");

        ButtonType buttonYes = new ButtonType("Yes");
        ButtonType buttonNo = new ButtonType("No");

        alert.getButtonTypes().setAll(buttonYes, buttonNo);

        Optional<ButtonType> result = alert.showAndWait();

        boolean userWantsToUpdate = false;
        if (result.get() == buttonYes){
            userWantsToUpdate = true;
        }
        return userWantsToUpdate;
    }

    public URLClassLoader createClassLoader() {
        List<URL> libs = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .map(LibraryFile::toURL)
                .collect(Collectors.toList());

        return new URLClassLoader(libs.toArray(new URL[libs.size()]));
    }

    public void createApplication() throws Exception {
        URLClassLoader classLoader = createClassLoader();
        FXMLLoader.setDefaultClassLoader(classLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        Platform.runLater(() -> Thread.currentThread().setContextClassLoader(classLoader));
        Class<? extends Application> appclass = (Class<? extends Application>) classLoader.loadClass(manifest.launchClass);
        app = appclass.newInstance();
    }

    public void launchAppFromManifest() throws Exception {
        app.init();
        Platform.runLater(() -> {
            try {
                if (launcherWindow != null){
                    launcherWindow.close();
                }
                ParametersImpl.registerParameters(app, new LauncherParams(mainApplication.getParameters(), manifest));
                app.start(primaryStage);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    public void stop() throws Exception {
        if (app != null){
            app.stop();
        }
    }

    public Label getStatusLabel() {
        return status;
    }

    public ProgressBar getProgressBar() {
        return progressBar;
    }

    private static class ThrowableWrapper {
        Throwable t;
    }

    /**
     * Invokes a Runnable in JFX Thread and waits while it's finished. Like
     * SwingUtilities.invokeAndWait does for EDT.
     *
     * @param run
     *            The Runnable that has to be called on JFX thread.
     * @throws InterruptedException
     *             f the execution is interrupted.
     * @throws ExecutionException
     *             If a exception is occurred in the run method of the Runnable
     */
    public void runAndWait(final Runnable run)
            throws InterruptedException, ExecutionException {
        if (Platform.isFxApplicationThread()) {
            try {
                run.run();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        } else {
            final Lock lock = new ReentrantLock();
            final Condition condition = lock.newCondition();
            final ThrowableWrapper throwableWrapper = new ThrowableWrapper();
            lock.lock();
            try {
                Platform.runLater(new Runnable() {

                    @Override
                    public void run() {
                        lock.lock();
                        try {
                            run.run();
                        } catch (Throwable e) {
                            throwableWrapper.t = e;
                        } finally {
                            try {
                                condition.signal();
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                });
                condition.await();
                if (throwableWrapper.t != null) {
                    throw new ExecutionException(throwableWrapper.t);
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
