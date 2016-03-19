package fxlauncher;

import com.sun.javafx.application.ParametersImpl;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import javax.xml.bind.JAXB;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class Launcher extends Application {
    private static final Logger log = Logger.getLogger("Launcher");

    private FXManifest manifest;
    private Application app;
    private StackPane root;
    private Stage primaryStage;
    private ProgressBar progressBar;
    private Stage stage;
    private String phase;

    ButtonType buttonYes = new ButtonType("Yes");
    ButtonType buttonNo = new ButtonType("No");
    boolean userWantsToUpdate = false;

    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;

        root = new StackPane(new ProgressIndicator());
        root.setPrefSize(200, 80);
        root.setPadding(new Insets(10));

        stage = new Stage(StageStyle.UNDECORATED);

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.show();



        new Thread(() -> {
            try {
                updateManifest();
                if (updateAvailable()){
                    Platform.runLater(() -> {
                        try {
                            userWantsToUpdate();
                            try {
                                createApplication();
                                launchAppFromManifest();
                            } catch (Exception ex) {
                                reportError(String.format("Error during %s phase", phase), ex);
                            }
                        } catch (Exception e) {
                            log.log(Level.WARNING, String.format("Error during %s phase", phase), e);
                        }
                    });
                }else {
                    createApplication();
                    launchAppFromManifest();
                }
            } catch (Exception ex) {
                log.log(Level.WARNING, String.format("Error during %s phase", phase), ex);
            }



        }).start();
    }

    private boolean userWantsToUpdate() throws Exception {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("There is an update available.");
        alert.setContentText("Would you like to update?");

        alert.getButtonTypes().setAll(buttonYes, buttonNo);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.get() == buttonYes){
            userWantsToUpdate = true;
        }
        if (userWantsToUpdate){
            syncFiles();
        }
        return userWantsToUpdate;
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void createUpdateWrapper() {
        phase = "Update Wrapper Creation";

        Platform.runLater(() -> {
            progressBar = new ProgressBar();
            progressBar.setStyle(manifest.progressBarStyle);

            Label label = new Label(manifest.updateText);
            label.setStyle(manifest.updateLabelStyle);

            VBox wrapper = new VBox(label, progressBar);
            wrapper.setStyle(manifest.wrapperStyle);

            root.getChildren().clear();
            root.getChildren().add(wrapper);
        });
    }

    public URLClassLoader createClassLoader() {
        List<URL> libs = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .map(LibraryFile::toURL)
                .collect(Collectors.toList());

        return new URLClassLoader(libs.toArray(new URL[libs.size()]));
    }

    private void launchAppFromManifest() throws Exception {
        phase = "Application Init";
        app.init();
        phase = "Application Start";
        Platform.runLater(() -> {
            try {
                stage.close();
                ParametersImpl.registerParameters(app, new LauncherParams(getParameters(), manifest));
                app.start(primaryStage);
            } catch (Exception ex) {
                reportError("Failed to start application", ex);
            }
        });
    }

    private void updateManifest() throws Exception {
        phase = "Update Manifest";
        syncManifest();
    }

    public boolean updateAvailable(){
        List<LibraryFile> needsUpdate = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .filter(LibraryFile::needsUpdate)
                .collect(Collectors.toList());
        return needsUpdate.size() > 0;
    }

    private void syncFiles() throws Exception {
        createUpdateWrapper();
        phase = "File Synchronization";

        List<LibraryFile> needsUpdate = manifest.files.stream()
                .filter(LibraryFile::loadForCurrentPlatform)
                .filter(LibraryFile::needsUpdate)
                .collect(Collectors.toList());

        Long totalBytes = needsUpdate.stream().mapToLong(f -> f.size).sum();
        Long totalWritten = 0L;

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
                    Platform.runLater(() -> progressBar.setProgress(progress));
                }
            }
        }
    }

    private void createApplication() throws Exception {
        phase = "Create Application";

        URLClassLoader classLoader = createClassLoader();
        FXMLLoader.setDefaultClassLoader(classLoader);
        Thread.currentThread().setContextClassLoader(classLoader);
        Platform.runLater(() -> Thread.currentThread().setContextClassLoader(classLoader));
        Class<? extends Application> appclass = (Class<? extends Application>) classLoader.loadClass(manifest.launchClass);
        app = appclass.newInstance();
    }

    public void stop() throws Exception {
        if (app != null)
            app.stop();
    }

    private void reportError(String title, Throwable error) {
        log.log(Level.WARNING, title, error);

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.getDialogPane().setPrefWidth(600);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(out);
            error.printStackTrace(writer);
            writer.close();
            TextArea text = new TextArea(out.toString());
            alert.getDialogPane().setContent(text);

            alert.showAndWait();
            Platform.exit();
        });
    }

    private void syncManifest() throws Exception {
        URL embeddedManifest = Launcher.class.getResource("/app.xml");
        manifest = JAXB.unmarshal(embeddedManifest, FXManifest.class);

        if (Files.exists(manifest.getPath()))
            manifest = JAXB.unmarshal(manifest.getPath().toFile(), FXManifest.class);

        try {
            FXManifest remoteManifest = JAXB.unmarshal(manifest.getFXAppURI(), FXManifest.class);

            if (remoteManifest == null) {
                log.info(String.format("No remote manifest at %s", manifest.getFXAppURI()));
            } else if (!remoteManifest.equals(manifest)) {
                manifest = remoteManifest;
                JAXB.marshal(manifest, manifest.getPath().toFile());
            }
        } catch (Exception ex) {
            log.log(Level.WARNING, "Unable to update manifest", ex);
        }
    }

}
