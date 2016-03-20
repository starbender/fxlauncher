package fxlauncher;

import javafx.concurrent.Task;

import javax.xml.bind.JAXB;
import java.net.URL;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UpdateManifestService extends Task<FXManifest> {
    private static final Logger log = Logger.getLogger("Launcher");

    private FXManifest manifest;

    public UpdateManifestService(){
    }

    @Override
    protected FXManifest call() throws Exception {

        Thread.sleep(3500);

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

        return manifest;
    }
}
