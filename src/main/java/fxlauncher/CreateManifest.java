package fxlauncher;

import starbender.model.meta.MetaData;

import javax.xml.bind.JAXB;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class CreateManifest {

    public static void main(String[] args) throws IOException {
        URI baseURI = URI.create(args[0]);
        String launchClass = args[1];
        Path appPath = Paths.get(args[2]);
        FXManifest manifest = create(baseURI, launchClass, appPath);

        if (args.length == 4)
            manifest.parameters = args[3];

        JAXB.marshal(manifest, appPath.resolve("app.xml").toFile());
    }

    public static FXManifest create(URI baseURI, String launchClass, Path appPath) throws IOException {
        FXManifest manifest = new FXManifest();
        manifest.uri = baseURI;
        manifest.launchClass = launchClass;
        manifest.majorVersion = MetaData.instance.MajorVersion;
        manifest.minorVersion = MetaData.instance.MinorVersion;
        manifest.buildVersion = MetaData.instance.Build;

        Files.walkFileTree(appPath, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isDirectory(file) && file.toString().endsWith(".jar") && !file.getFileName().toString().startsWith("fxlauncher"))
                    manifest.files.add(new LibraryFile(appPath, file));
                return FileVisitResult.CONTINUE;
            }
        });

        return manifest;
    }
    
}
