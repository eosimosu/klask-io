package io.klask.service;

import com.codahale.metrics.annotation.Timed;
import io.klask.config.Constants;
import io.klask.config.KlaskProperties;
import io.klask.domain.File;
import io.klask.repository.search.FileSearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.ElasticsearchException;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Created by jeremie on 30/04/16.
 */
@Service
public class CrawlerService {

    private final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    @Inject
    private FileSearchRepository fileSearchRepository;

    @Inject
    private KlaskProperties klaskProperties;

    private Set<String> directoriesToExcludeSet = new HashSet<>();
    private Set<String> filesToExcludeSet = new HashSet<>();
    private Set<String> filesToIncludeSet = new HashSet<>();
    private Set<String> extensionsToExcludeSet = new HashSet<>();
    private Set<String> readableExtensionSet = new HashSet<>();
    private Set<File> listeDeFichiers = new HashSet<>();

    @Inject
    private ElasticsearchTemplate elasticsearchTemplate;

    //TODO à supprimer
    private int numberOfFailedDocuments = 0;

    /**
     * execute the crawler on the path directory
     *
     * @param path
     */
    @Timed
    public void crawler(String path) {
        log.debug("Start Parsing files in {}", path);

        directoriesToExcludeSet.clear();
        directoriesToExcludeSet.addAll(klaskProperties.getCrawler().getDirectoriesToExclude());
        log.debug("exclude directories {}", directoriesToExcludeSet);

        filesToExcludeSet.clear();
        filesToExcludeSet.addAll(klaskProperties.getCrawler().getFilesToExclude());
        log.debug("exclude files : {}", filesToExcludeSet);

        filesToIncludeSet.clear();
        filesToIncludeSet.addAll(klaskProperties.getCrawler().getFilesToInclude());
        log.debug("include files : {}", filesToIncludeSet);

        extensionsToExcludeSet.clear();
        extensionsToExcludeSet.addAll(klaskProperties.getCrawler().getExtensionsToExclude());
        log.debug("exclude extensions : {}", extensionsToExcludeSet);

        readableExtensionSet.clear();
        readableExtensionSet.addAll(klaskProperties.getCrawler().getExtensionsToRead());
        log.debug("ascii files with extension : {}", readableExtensionSet);

        numberOfFailedDocuments = 0;

        try {
            Files.walk(new java.io.File(path).toPath())
                .filter(p -> p.toFile().isFile())
                //.peek(p -> notifyDiscoveredFile(p, "before"))
                .filter(this::doesntContainsExcludeDirectoriesOrFiles)
                //.peek(p -> notifyDiscoveredFile(p, "after"))
                .forEach(this::addFile);

            //s'il reste des fichiers non indexé dans la file d'attente
            //on lance une dernière fois l'indexing sur ces fichiers
            indexingBulkFiles();

        } catch (final IOException e) {
            log.error("Exception in crawler method", e);
        } catch (Throwable t) {
            log.error("Throwable thrown " + t.getMessage(), t);
        }


        if (numberOfFailedDocuments > 0) {
            log.error("{} files with indexing errors", numberOfFailedDocuments);
        }
        log.debug("Finish to parse files in {}", path);
    }

    private String extractExtension(String fileName, int posPoint) {
        if (posPoint > 0) {
            return fileName.substring(posPoint + 1, fileName.length()).toLowerCase();
        }
        //the file name doesn't contain a dot or the name is like ".project" so no extension
        return "";
    }

    private String extractName(String fileName, int posPoint) {
        if (posPoint > 0) {
            return fileName.substring(0, posPoint);
        }
        //the file name doesn't contain a dot or if there is a dot, like .project, ".project" is the name
        return fileName;
    }

    private void notifyDiscoveredFile(Path path, String position) {
        if (path.getFileName().toString().equals("pom.xml")) {
            log.debug("add {}, {}", position, path);
        }
    }

    /**
     * check the size of batch index, and index if necessary
     */
    private void indexBulkFilesIfNecessary() {
        if (listeDeFichiers.size() > klaskProperties.getCrawler().getBatchSize()) {
            indexingBulkFiles();
            listeDeFichiers.clear();
        }
    }
    /**
     * Parsing one file
     *
     * @param p
     */
    private void addFile(Path p) {
        log.trace("Parsing file : {}", p);

        String fileName = p.getFileName().toString();
        int posPoint = fileName.lastIndexOf(".");
        String extension = extractExtension(fileName, posPoint);
        String name = extractName(fileName, posPoint);

        log.trace("explode filename : name:{}\textension:{}", name, extension);

        try {
            indexBulkFilesIfNecessary();
            File document = constructFile(name, extension, p);
            listeDeFichiers.add(document);

        } catch (IOException e) {
            log.error("Exception while reading file {}", p);
        } catch (Throwable t) {
            log.error("Throwable thrown while indexing file {} ", p, t);
        }

    }

    /**
     * Index a bulk of files (Constant default : 100)
     *
     */
    private void indexingBulkFiles() {
        log.trace("indexing bulk files : {}", listeDeFichiers);
        try {
            fileSearchRepository.save(listeDeFichiers);
        } catch (ElasticsearchException e) {
            log.error("Exception while indexing file -- getting file's list...");
            Set<String> failedDocuments = e.getFailedDocuments().keySet();
            numberOfFailedDocuments += failedDocuments.size();
            listeDeFichiers.stream()
                .filter(f -> failedDocuments.contains(f.getId()))
                .forEach(file -> log.error("Exception while indexing file {}, {}", file.getPath(), e.getFailedDocuments().get(file.getId())));
        } catch (OutOfMemoryError e) {
            log.error("OutOfMemory while indexing one file of the following files :");
            StringBuilder sb = new StringBuilder();
            listeDeFichiers
                .forEach(file -> sb.append(file.getPath() + ","));
            log.error(sb.toString());
        } catch (Exception e) {
            log.error("elasticsearch node is not avaible, waiting 10s and continue", e);
            try {
                Thread.sleep(10000);
            } catch (Exception ee) {
                log.error("Error while Thread.sleep", e);
            }
        }
    }

    /**
     * Construct a {@link File} with a version and readContent
     *
     * @param name
     * @param extension
     * @param path
     * @return
     * @throws IOException
     */
    private File constructFile(String name, String extension, Path path) throws IOException {

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attrs.size();

        String content = null;
        if (!readableExtensionSet.contains(extension) || size > Constants.MAX_SIZE_FOR_INDEXING_ONE_FILE) {
            log.trace("parsing only name on file : {}", path);
        } else {
            content = readContent(path);
        }

        File fichier = new File(
            UUID.randomUUID().toString(),
            name,
            extension,
            path.toString(),
            null,
            content,
            null,
            size
        );
        setVersionAndProject(fichier, path.toString());
        fichier.setCreatedDate(attrs.creationTime().toInstant().atZone(ZoneId.systemDefault()));
        fichier.setLastModifiedDate(attrs.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));

        return fichier;
    }

    private void setVersionAndProject(File fichier, String path) {
        String project = null;//par défaut
        String version = "trunk";//par défaut
        if (path.contains("/branches/")) {
            int positionBranches = path.indexOf("/branches/");
            version = path.substring(positionBranches + 10, path.indexOf("/", positionBranches + 10));
            if (positionBranches > 1) {
                project = path.substring(path.lastIndexOf("/", positionBranches - 1) + 1, positionBranches);
            }
        } else if (path.contains("trunk")) {
            int positionBranches = path.indexOf("/trunk/");
            if (positionBranches > 1) {
                project = path.substring(path.lastIndexOf("/", positionBranches - 1) + 1, positionBranches);
            }
        }
        fichier.setVersion(version);
        fichier.setProject(project);
    }

    /**
     * Read the content of the file at the path p
     *
     * @param p
     * @return
     * @throws IOException
     */
    private String readContent(Path p) throws IOException {
        byte[] content = Files.readAllBytes(p);
        return new String(content, Charset.forName("iso-8859-1"));

    }

    /**
     * Test if the Path contains an exclude directory, an exclude file or an file with an exclude extension
     *
     * @param path
     */
    private boolean doesntContainsExcludeDirectoriesOrFiles(Path path) {
        String fileName = path.getFileName().toString();
        return
            (directoriesToExcludeSet.stream().noneMatch(token -> path.toString().contains(token)) &&
            filesToExcludeSet.stream().noneMatch(fileName::equals) &&
                !fileName.endsWith("~") &&
                extensionsToExcludeSet.stream().noneMatch(token -> extractExtension(fileName, fileName.lastIndexOf(".")).equals(token))
            )
                || (
                filesToIncludeSet.stream().anyMatch(fileName::equals)
            );
    }

    /**
     * clear all the index
     */
    public void clearIndex() {
        elasticsearchTemplate.deleteIndex(File.class);
        elasticsearchTemplate.createIndex(File.class);
        elasticsearchTemplate.putMapping(File.class);
        elasticsearchTemplate.refresh(File.class);
    }
}
