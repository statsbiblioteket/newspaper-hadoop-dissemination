package dk.statsbiblioteket.medieplatform.hadoop;

import dk.statsbiblioteket.doms.central.connectors.BackendInvalidCredsException;
import dk.statsbiblioteket.doms.central.connectors.BackendMethodFailedException;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedora;
import dk.statsbiblioteket.doms.central.connectors.EnhancedFedoraImpl;
import dk.statsbiblioteket.doms.central.connectors.fedora.pidGenerator.PIDGeneratorException;
import dk.statsbiblioteket.doms.webservices.authentication.Credentials;
import dk.statsbiblioteket.medieplatform.autonomous.AutonomousComponentUtils;
import dk.statsbiblioteket.medieplatform.autonomous.ConfigConstants;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * Utility for adding symlinks to a batch for which presentation copies have already been created,
 */
public class SymlinkCreatorApplication {

    private static Logger log = LoggerFactory.getLogger(SymlinkCreatorApplication.class);

    private static void usage() {
        System.out.println("Usage:\njava dk.statsbiblioteket.medieplatform.hadoop.SymlinkCreatorApplication roundtripid -c configfile");
    }

    /**
     * Usage:
     * java dk.statsbiblioteket.medieplatform.hadoop.SymlinkCreatorApplication roundtripid -c configfile
     *
     *
     * @param args The arguments.
     */
    public static void main(String[] args) throws IOException {
        log.info("Started " + SymlinkCreatorApplication.class.getName());
        if (args.length != 3 || !args[1].equals("-c")) {
            usage();
            System.exit(1);
        }
        Properties properties = AutonomousComponentUtils.parseArgs(args);
        File disseminationRoot = new File(properties.getProperty(DisseminationJob.PGM_TO_JP2K_OUTPUT_PATH));
        log.debug("Reading dissemination files from {}.", disseminationRoot.getAbsolutePath());
        File roundtripRoot = new File(disseminationRoot, args[0]);
        if (!(roundtripRoot.exists() && roundtripRoot.isDirectory())) {
            log.error("Directory {} does not exist. Exiting.", roundtripRoot.getAbsolutePath());
            System.exit(2);
        }
        log.debug("Reading roundtrip dissemination files from {}.", roundtripRoot.getAbsolutePath());
        File symlinkRoot = new File(properties.getProperty(SymlinkCreatorReducer.SYMLINK_ROOTDIR_PATH));
        int symlinkDepth = Integer.parseInt(properties.getProperty(SymlinkCreatorReducer.SYMLINK_DEPTH));
        log.debug("Creating symlinks in {} with depth {}.", symlinkRoot.getAbsolutePath(), symlinkDepth);
        EnhancedFedora fedora = null;
        try {
            fedora = getFedora(properties);
        } catch (Exception e) {
            log.error("Could not initialise fedora.", e);
            System.exit(3);
        }
        IOFileFilter filter = getJP2FileFilter();
        SymlinkCreatorApplication application = new SymlinkCreatorApplication();
        try {
            application.createLinks(roundtripRoot, symlinkRoot, symlinkDepth, fedora, filter);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(4);
        }
    }

    /**
     * Filter which just matches files ending in ".jp2".
     * @return The filter.
     */
    protected static IOFileFilter getJP2FileFilter() {
        return new IOFileFilter() {
                @Override
                public boolean accept(File file) {
                  return accept(null, file.getName());
                }

                @Override
                public boolean accept(File dir, String name) {
                    return FilenameUtils.getExtension(name).equals("jp2");
                }
            };
    }

    private static EnhancedFedora getFedora(Properties properties) throws JAXBException, PIDGeneratorException, MalformedURLException {
        String username = properties.getProperty(ConfigConstants.DOMS_USERNAME);
        String password = properties.getProperty(ConfigConstants.DOMS_PASSWORD);
        String domsUrl = properties.getProperty(ConfigConstants.DOMS_URL);
        return new EnhancedFedoraImpl(
                new Credentials(username, password), domsUrl, null, null);
    }

    /**
     * Create nested symlinks to all files in a subdirectory matching a given filter.
     * @param roundtripRoot The root directory in which the files are to be found.
     * @param symlinkRoot The root directory in which the symlinks are to be created.
     * @param symlinkDepth The nesting depth for the symlinks.
     * @param enhancedFedora The fedora instance in which the filenames are resolved to the pids used for the linknames.
     * @param filter The filter.
     * @throws IOException
     * @throws BackendInvalidCredsException
     * @throws BackendMethodFailedException
     * @throws InterruptedException
     */
    public void createLinks(File roundtripRoot, File symlinkRoot, int symlinkDepth,
                            EnhancedFedora enhancedFedora,
                            IOFileFilter filter) throws IOException, BackendInvalidCredsException, BackendMethodFailedException, InterruptedException {
        Collection<File> files = FileUtils.listFiles(roundtripRoot, filter, TrueFileFilter.INSTANCE);
        log.debug("Found {} dissemination files.", files.size());
        for (File file: files) {
            String pathInBatch = file.getCanonicalPath().replace(roundtripRoot.getParentFile().getCanonicalPath() + "/", "");
            pathInBatch = "path:" + pathInBatch.replaceAll("_", "/");   //Is this necessary?
            List<String> hits = enhancedFedora.findObjectFromDCIdentifier(pathInBatch);
            if (hits.isEmpty()) {
                throw new RuntimeException("Failed to look up doms object for DC identifier '" + pathInBatch + "'");
            } else {
                if (hits.size() > 1) {
                    log.warn("Found multiple pids for dc identifier '" + pathInBatch + "', using the first one '" + hits.get(0) + "'");
                }
                String pid = hits.get(0);
                //No need to check the result of this call as it throws an exception if it fails.
                LinkUtils.createSymlinkJava6(pid, file.getAbsolutePath(), symlinkRoot.getAbsolutePath(), symlinkDepth);
            }
        }
    }




}
