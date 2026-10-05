import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Extracts signing properties into private files, without logging credentials. */
class SigningProperties {
    /** Reads Gradle's properties format and writes only the three apksigner inputs. */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var values = new Properties();
        try (var input = Files.newInputStream(root.resolve("signing.properties"))) {
            values.load(input);
        }
        for (var key : new String[] {"storePassword", "keyAlias", "keyPassword"}) {
            var value = values.getProperty(key);
            if (value == null || value.isEmpty() || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("Missing or multiline signing property: " + key);
            }
            var output = root.resolve(key);
            Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(output, value + "\n");
        }
    }
}
