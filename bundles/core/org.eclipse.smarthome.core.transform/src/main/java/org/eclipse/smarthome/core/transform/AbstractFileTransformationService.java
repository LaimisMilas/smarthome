/**
 * Copyright (c) 2014,2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.core.transform;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.smarthome.config.core.ConfigConstants;
import org.eclipse.smarthome.core.i18n.LocaleProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for cacheable and localizable file based transformation
 * {@link TransformationService}.
 * It expects the transformation to be applied to be read from a file stored
 * under the 'transform' folder within the configuration path. To organize the various
 * transformations one might use subfolders.
 *
 * @author Gaël L'hopital - Initial contribution
 * @author Kai Kreuzer - File caching mechanism
 * @author Markus Rathgeb - Add locale provider support
 */
public abstract class AbstractFileTransformationService<T> implements TransformationService {

    private WatchService watchService = null;

    protected final Map<String, T> cachedFiles = new ConcurrentHashMap<>();
    protected final List<String> watchedDirectories = new ArrayList<String>();

    private final Logger logger = LoggerFactory.getLogger(AbstractFileTransformationService.class);

    private LocaleProvider localeProvider;
    private ServiceTracker<LocaleProvider, LocaleProvider> localeProviderTracker;

    private class LocaleProviderServiceTrackerCustomizer
            implements ServiceTrackerCustomizer<LocaleProvider, LocaleProvider> {

        private final BundleContext context;

        public LocaleProviderServiceTrackerCustomizer(final BundleContext context) {
            this.context = context;
        }

        @Override
        public LocaleProvider addingService(ServiceReference<LocaleProvider> reference) {
            localeProvider = context.getService(reference);
            return localeProvider;
        }

        @Override
        public void modifiedService(ServiceReference<LocaleProvider> reference, LocaleProvider service) {
        }

        @Override
        public void removedService(ServiceReference<LocaleProvider> reference, LocaleProvider service) {
            localeProvider = null;
        }

    }

    protected void activate(final BundleContext context) {
        localeProviderTracker = new ServiceTracker<>(context, LocaleProvider.class,
                new LocaleProviderServiceTrackerCustomizer(context));
        localeProviderTracker.open();
    }

    protected void deactivate() {
        localeProviderTracker.close();
    }

    protected Locale getLocale() {
        return localeProvider.getLocale();
    }

    /**
     * <p>
     * Transforms the input <code>source</code> by the according method defined in subclass to another string.
     * It expects the transformation to be read from a file which is stored
     * under the 'conf/transform'
     *
     * @param filename
     *            the name of the file which contains the transformation definition.
     *            The name may contain subfoldernames
     *            as well
     * @param source
     *            the input to transform
     * @throws TransformationException
     */
    @Override
    public String transform(String filename, String source) throws TransformationException {

        if (filename == null || source == null) {
            throw new TransformationException("the given parameters 'filename' and 'source' must not be null");
        }

        if (watchService == null) {
            initializeWatchService();
        } else {
            processFolderEvents();
        }

        String transformFile = getLocalizedProposedFilename(filename);
        T transform = cachedFiles.get(transformFile);
        if (transform == null) {
            transform = internalLoadTransform(transformFile);
            cachedFiles.put(transformFile, transform);
        }

        try {
            return internalTransform(transform, source);
        } catch (TransformationException e) {
            logger.warn("Could not transform '{}' with the file '{}' : {}", source, filename, e.getMessage());
            return "";
        }

    }

    /**
     * <p>
     * Abstract method defined by subclasses to effectively operate the
     * transformation according to its rules
     *
     * @param transform
     *            transformation held by the file provided to <code>transform</code> method
     *
     * @param source
     *            the input to transform
     *
     * @return the transformed result or null if the
     *         transformation couldn't be completed for any reason.
     *
     */
    protected abstract String internalTransform(T transform, String source) throws TransformationException;

    /**
     * <p>
     * Abstract method defined by subclasses to effectively read the transformation
     * source file according to their own needs.
     *
     * @param filename
     *            Name of the file to be read. This filename may have been transposed
     *            to a localized one
     *
     * @return
     *         An object containing the source file
     *
     * @throws TransformationException
     *             file couldn't be read for any reason
     */
    protected abstract T internalLoadTransform(String filename) throws TransformationException;

    private void initializeWatchService() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchSubDirectory("");
        } catch (IOException e) {
            logger.error("Unable to start transformation directory monitoring");
        }
    }

    private void watchSubDirectory(String subDirectory) {
        if (watchedDirectories.indexOf(subDirectory) == -1) {
            String watchedDirectory = getSourcePath() + subDirectory;
            Path transformFilePath = Paths.get(watchedDirectory);
            try {
                transformFilePath.register(watchService, ENTRY_DELETE, ENTRY_MODIFY);
                logger.debug("Watching directory {}", transformFilePath);
                watchedDirectories.add(subDirectory);
            } catch (IOException e) {
                logger.warn("Unable to watch transformation directory : {}", watchedDirectory);
                cachedFiles.clear();
            }
        }
    }

    /**
     * Ensures that a modified or deleted cached files does not stay in the cache
     */
    private void processFolderEvents() {
        WatchKey key = watchService.poll();
        if (key != null) {
            for (WatchEvent<?> e : key.pollEvents()) {
                if (e.kind() == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) e;
                Path path = ev.context();

                logger.debug("Refreshing transformation file '{}'", path);

                for (String fileEntry : cachedFiles.keySet()) {
                    if (fileEntry.endsWith(path.toString())) {
                        cachedFiles.remove(fileEntry);
                    }
                }
            }
            key.reset();
        }
    }

    /**
     * Returns the name of the localized transformation file
     * if it actually exists, keeps the original in the other case
     *
     * @param filename name of the requested transformation file
     * @return original or localized transformation file to use
     */
    protected String getLocalizedProposedFilename(String filename) {
        String extension = FilenameUtils.getExtension(filename);
        String prefix = FilenameUtils.getPath(filename);
        String result = filename;

        if (!prefix.isEmpty()) {
            watchSubDirectory(prefix);
        }

        // the filename may already contain locale information
        if (!filename.matches(".*_[a-z]{2}." + extension + "$")) {
            String basename = FilenameUtils.getBaseName(filename);
            String alternateName = prefix + basename + "_" + getLocale().getLanguage() + "." + extension;
            String alternatePath = getSourcePath() + alternateName;

            File f = new File(alternatePath);
            if (f.exists()) {
                result = alternateName;
            }
        }

        result = getSourcePath() + result;
        return result;
    }

    /**
     * Returns the path to the root of the transformation folder
     */
    protected String getSourcePath() {
        return ConfigConstants.getConfigFolder() + File.separator + TransformationService.TRANSFORM_FOLDER_NAME
                + File.separator;
    }

}