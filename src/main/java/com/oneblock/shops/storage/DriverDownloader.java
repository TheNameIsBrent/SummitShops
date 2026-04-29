package com.oneblock.shops.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads the MariaDB JDBC driver from Maven Central at runtime if not
 * already present, then loads it into the JVM via a URLClassLoader.
 *
 * This keeps the plugin jar small — no driver bundled — while still working
 * on any server without manual jar installation.
 *
 * The driver jar is cached in plugins/OneBlockShops/libs/ after the first
 * download, so subsequent starts require no network access.
 */
public class DriverDownloader {

    private static final String GROUP    = "org.mariadb.jdbc";
    private static final String ARTIFACT = "mariadb-java-client";
    private static final String VERSION  = "3.4.1";

    // Maven Central URL pattern
    private static final String MAVEN_CENTRAL =
            "https://repo1.maven.org/maven2/%s/%s/%s/%s-%s.jar";

    private final Logger log;
    private final File   libsDir;

    public DriverDownloader(Logger log, File pluginDataFolder) {
        this.log     = log;
        this.libsDir = new File(pluginDataFolder, "libs");
    }

    /**
     * Ensures the driver is available and loaded.
     * Returns true if the driver class is loadable after this call.
     */
    public boolean ensureDriver() {
        // Already on the classpath? (e.g. another plugin or server provided it)
        if (isDriverLoaded()) return true;

        File jar = localJarFile();
        if (!jar.exists()) {
            log.info("[DriverDownloader] MariaDB JDBC driver not found locally — downloading from Maven Central...");
            if (!download(jar)) return false;
        }

        return loadJar(jar);
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    private boolean download(File destination) {
        libsDir.mkdirs();
        String groupPath = GROUP.replace('.', '/');
        String urlStr = String.format(MAVEN_CENTRAL, groupPath, ARTIFACT, VERSION, ARTIFACT, VERSION);

        try {
            URL url = new URL(urlStr);
            log.info("[DriverDownloader] Downloading from: " + urlStr);
            try (InputStream in = url.openStream()) {
                Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[DriverDownloader] Downloaded " + destination.getName()
                    + " (" + (destination.length() / 1024) + " KB)");
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "[DriverDownloader] Download failed: " + e.getMessage()
                    + "\nPlace " + jarName() + " manually in " + libsDir.getAbsolutePath(), e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Classloader injection
    // -----------------------------------------------------------------------

    private boolean loadJar(File jar) {
        try {
            // Inject into the plugin's own URLClassLoader so HikariCP can find the driver
            ClassLoader cl = getClass().getClassLoader();
            if (cl instanceof URLClassLoader ucl) {
                // Java 8 style — URLClassLoader exposes addURL via reflection
                Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addUrl.setAccessible(true);
                addUrl.invoke(ucl, jar.toURI().toURL());
            } else {
                // Java 9+ modules — use a child URLClassLoader and register the driver manually
                URLClassLoader child = new URLClassLoader(
                        new URL[]{jar.toURI().toURL()}, cl);
                // Force-load the driver class so DriverManager registers it
                Class<?> driverClass = child.loadClass("org.mariadb.jdbc.Driver");
                java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
                java.sql.DriverManager.registerDriver(new DelegatingDriver(driver));
            }

            if (isDriverLoaded()) {
                log.info("[DriverDownloader] MariaDB JDBC driver loaded successfully.");
                return true;
            } else {
                log.severe("[DriverDownloader] Driver jar loaded but class not found.");
                return false;
            }
        } catch (Exception e) {
            log.log(Level.SEVERE, "[DriverDownloader] Failed to load driver jar", e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean isDriverLoaded() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private File localJarFile() { return new File(libsDir, jarName()); }

    private static String jarName() { return ARTIFACT + "-" + VERSION + ".jar"; }

    // -----------------------------------------------------------------------
    // Delegating driver wrapper — needed for Java 9+ module isolation
    // -----------------------------------------------------------------------

    private static final class DelegatingDriver implements java.sql.Driver {
        private final java.sql.Driver delegate;
        DelegatingDriver(java.sql.Driver delegate) { this.delegate = delegate; }

        @Override public java.sql.Connection connect(String url, java.util.Properties info) throws java.sql.SQLException { return delegate.connect(url, info); }
        @Override public boolean acceptsURL(String url) throws java.sql.SQLException { return delegate.acceptsURL(url); }
        @Override public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info) throws java.sql.SQLException { return delegate.getPropertyInfo(url, info); }
        @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
        @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
        @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }
}
