/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.util;

import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.loader.FileUtils;
import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;

import java.util.ResourceBundle;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.text.MessageFormat;
import java.io.IOException;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URL;

public class Log4jLog {

    protected org.apache.log4j.Logger _logger = null;
    private LogCategory category;
    private String baseName;

    private static final String SUFFIX = ".Messages";

    private static final String OPENEJB = "org.apache.openejb";
    /**
     * Computes the parent of a resource name. E.g. if we pass in a key of
     * a.b.c, it returns the value a.b
     */
    private static final Computable<String, String> heirarchyResolver = new Computable<String, String>() {
        public String compute(String key) throws InterruptedException {
            int index = key.lastIndexOf(".");
            String parent = key.substring(0, index);
            if (parent.contains(OPENEJB))
                return parent;
            return null;
        }
    };
    /**
     * Simply returns the ResourceBundle for a given baseName
     */
    private static final Computable<String, ResourceBundle> bundleResolver = new Computable<String, ResourceBundle>() {
        public ResourceBundle compute(String baseName)
                throws InterruptedException {
            try {
                return ResourceBundle.getBundle(baseName + SUFFIX);
            } catch (MissingResourceException e) {
                return null;
            }
        }
    };
    /**
     * Builds a Logger object and returns it
     */
    private static final Computable<Object[], Log4jLog> loggerResolver = new Computable<Object[], Log4jLog>() {
        public Log4jLog compute(Object[] args) throws InterruptedException {

            Log4jLog logger = new Log4jLog();
            logger.category = (LogCategory) args[0];
            logger._logger = org.apache.log4j.Logger.getLogger(logger.category.getName());
            logger.baseName = (String) args[1];
            return logger;

        }
    };
    /**
     * Creates a MessageFormat object for a message and returns it
     */
    private static final Computable<String, MessageFormat> messageFormatResolver = new Computable<String, MessageFormat>() {
        public MessageFormat compute(String message)
                throws InterruptedException {

            return new MessageFormat(message);

        }
    };
    /**
     * Cache of parent-child relationships between resource names
     */
    private static final Computable<String, String> heirarchyCache = new Memoizer<String, String>(heirarchyResolver);
    /**
     * Cache of ResourceBundles
     */
    private static final Computable<String, ResourceBundle> bundleCache = new Memoizer<String, ResourceBundle>(bundleResolver);
    /**
     * Cache of Loggers
     */
    private static final Computable<Object[], Log4jLog> loggerCache = new Memoizer<Object[], Log4jLog>(loggerResolver);
    /**
     * Cache of MessageFormats
     */
    private static final Computable<String, MessageFormat> messageFormatCache = new Memoizer<String, MessageFormat>(messageFormatResolver);

    private static final String LOGGING_PROPERTIES_FILE = "logging.properties";
    private static final String EMBEDDED_PROPERTIES_FILE = "embedded.logging.properties";

    static {
        try {
            String prop = System.getProperty("openejb.logger.external", "false");
            boolean externalLogging = Boolean.parseBoolean(prop);
            if (!externalLogging)
                configureInternal();
        } catch (Exception e) {
            // The fall back here is that if log4j.configuration system property is set, then that configuration file will be used.
            e.printStackTrace();
        }
    }

    private static void configureInternal() throws IOException {

        System.setProperty("openjpa.Log", "log4j");
        SystemInstance system = SystemInstance.get();
        FileUtils base = system.getBase();
        File confDir = base.getDirectory("conf");
        File loggingPropertiesFile = new File(confDir, LOGGING_PROPERTIES_FILE);
        if (confDir.exists()) {
            if (loggingPropertiesFile.exists()) {
                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(loggingPropertiesFile));
                Properties props = new Properties();
                props.load(bis);
                applyOverrides(props);
                preprocessProperties(props);
                PropertyConfigurator.configure(props);
                try {
                    bis.close();
                } catch (IOException e) {

                }
            } else {
                installLoggingPropertiesFile(loggingPropertiesFile);
            }
        } else {
            configureEmbedded();
        }
    }

    private static void applyOverrides(Properties properties) {
        Properties system = SystemInstance.get().getProperties();
        for (Map.Entry<Object, Object> entry : system.entrySet()) {
            String key = entry.getKey().toString();
            if (key.startsWith("log4j.") && !key.equals("log4j.configuration")){
                properties.put(key, entry.getValue());
            }
        }
    }

    private static void preprocessProperties(Properties properties) {
        FileUtils base = SystemInstance.get().getBase();
        File confDir = new File(base.getDirectory(), "conf");
        File baseDir = base.getDirectory();
        File userDir = new File("foo").getParentFile();

        File[] paths = {confDir, baseDir, userDir};

        List missing = new ArrayList();

        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();


            if (key.endsWith(".File")) {

                boolean found = false;
                for (int i = 0; i < paths.length && !found; i++) {
                    File path = paths[i];
                    File logfile = new File(path, value);
                    if (logfile.getParentFile().exists()) {
                        properties.setProperty(key, logfile.getAbsolutePath());
                        found = true;
                    }
                }

                if (!found) {
                    File logfile = new File(paths[0], value);
                    missing.add(logfile);
                }
            }
        }

        if (missing.size() > 0) {
            org.apache.log4j.Logger logger = getFallabckLogger();

            logger.error("Logging may not operate as expected.  The directories for the following files do not exist so no file can be created.  See the list below.");
            for (int i = 0; i < missing.size(); i++) {
                File file = (File) missing.get(i);
                logger.error("[" + i + "] " + file.getAbsolutePath());
            }
        }
    }

    private static org.apache.log4j.Logger getFallabckLogger() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger("OpenEJB.logging");

        SimpleLayout simpleLayout = new SimpleLayout();
        ConsoleAppender newAppender = new ConsoleAppender(simpleLayout);
        logger.addAppender(newAppender);
        return logger;
    }

    private static void configureEmbedded() {
        URL resource = ConfUtils.getResource(EMBEDDED_PROPERTIES_FILE);
        Properties properties = asProperies(resource);

        applyOverrides(properties);

        if (resource != null) {
            PropertyConfigurator.configure(properties);
        } else {
            System.out.println("FATAL ERROR WHILE CONFIGURING LOGGING!!!. MISSING embedded.logging.properties FILE ");
        }
    }

    private static Properties asProperies(URL resource) {
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = resource.openStream();
            in = new BufferedInputStream(in);
            properties.load(in);
        } catch (IOException e) {
        } finally{
            try {
                if (in != null) in.close();
            } catch (IOException e) {
            }
        }
        return properties;
    }

    private static void installLoggingPropertiesFile(File loggingPropertiesFile) throws IOException {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(LOGGING_PROPERTIES_FILE);
        if (resource == null) {
            System.out.println("FATAL ERROR WHILE CONFIGURING LOGGING!!!. MISSING logging.properties FILE ");
            return;
        }
        InputStream in = resource.openStream();
        in = new BufferedInputStream(in);
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        byte buf[] = new byte[4096];
        int i = in.read(buf);
        while (i != -1) {
            bao.write(buf);
            i = in.read(buf);
        }
        byte[] byteArray = bao.toByteArray();
        ByteArrayInputStream bis = new ByteArrayInputStream(byteArray);

        Properties props = new Properties();
        props.load(bis);
        preprocessProperties(props);
        BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(loggingPropertiesFile));
        bout.write(byteArray);
        PropertyConfigurator.configure(props);
        try {
            bout.close();
        } catch (IOException e) {

        }
        try {
            in.close();
        } catch (IOException e) {

        }

    }

    /**
     * Given a key and a baseName, this method computes a message for a key. if
     * the key is not found in this ResourceBundle for this baseName, then it
     * recursively looks up its parent to find the message for a key. If no
     * message is found for a key, the key is returned as is and is logged by
     * the logger.
     */
    private String getMessage(String key, String baseName) {
        try {

            ResourceBundle bundle = bundleCache.compute(baseName);
            if (bundle != null) {
                String message = null;
                try {
                    message = bundle.getString(key);
                    return message;
                } catch (MissingResourceException e) {
                    String parentName = heirarchyCache.compute(baseName);
                    if (parentName == null)
                        return key;
                    else
                        return getMessage(key, parentName);
                }

            } else {
                String parentName = heirarchyCache.compute(baseName);
                if (parentName == null)
                    return key;
                else
                    return getMessage(key, parentName);

            }
        } catch (InterruptedException e) {
            // ignore
        }
        return key;
    }


    /**
     * Finds a Logger from the cache and returns it. If not found in cache then builds a Logger and returns it.
     *
     * @param category - The category of the logger
     * @param baseName - The baseName for the ResourceBundle
     * @return Logger
     */
    public static Log4jLog getInstance(LogCategory category, String baseName) {
        try {
            Log4jLog logger = loggerCache
                    .compute(new Object[]{category, baseName});
            return logger;
        } catch (InterruptedException e) {
            /*
                * Don't return null here. Just create a new Logger and set it up.
                * It will not be stored in the cache, but a later lookup for the
                * same Logger would probably end up in the cache
                */
            Log4jLog logger = new Log4jLog();
            logger.category = category;
            logger._logger = org.apache.log4j.Logger.getLogger(category.getName());
            logger.baseName = baseName;
            return logger;
        }
    }

    public static Log4jLog getInstance(LogCategory category, Class clazz) {
        return getInstance(category, packageName(clazz));
    }

    private static String packageName(Class clazz) {
        String name = clazz.getName();
        return name.substring(0, name.lastIndexOf("."));
    }

    public Log4jLog getLogger(String moduleId) {
        return getInstance(this.category, this.baseName);

    }

    /**
     * Formats a given message
     *
     * @param message
     * @param args
     * @return the formatted message
     */
    private String formatMessage(String message, Object... args) {
        try {
            MessageFormat mf = messageFormatCache.compute(message);
            String msg = mf.format(args);
            return msg;
        } catch (InterruptedException e) {
            return "Error in formatting message " + message;
        }

    }

    private Log4jLog() {
    }

    public boolean isDebugEnabled() {
        return _logger.isDebugEnabled();
    }

    public boolean isErrorEnabled() {
        return _logger.isEnabledFor(Level.ERROR);
    }

    public boolean isFatalEnabled() {
        return _logger.isEnabledFor(Level.FATAL);
    }

    public boolean isInfoEnabled() {
        return _logger.isInfoEnabled();
    }

    public boolean isWarningEnabled() {
        return _logger.isEnabledFor(Level.WARN);
    }

    /**
     * If this level is enabled, then it finds a message for the given key  and logs it
     *
     * @param message - This could be a plain message or a key in Messages.properties
     * @return the formatted i18n message
     */
    public String debug(String message) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.debug(msg);
            return msg;
        }
        return message;
    }

    public String debug(String message, Object... args) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.debug(msg);
            return msg;
        }
        return message;
    }

    public String debug(String message, Throwable t) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.debug(msg, t);
            return msg;
        }
        return message;
    }

    public String debug(String message, Throwable t, Object... args) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.debug(msg, t);
            return msg;
        }
        return message;
    }

    public String error(String message) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.error(msg);
            return msg;
        }
        return message;
    }

    public String error(String message, Object... args) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.error(msg);
            return msg;
        }
        return message;
    }

    public String error(String message, Throwable t) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.error(msg, t);
            return msg;
        }
        return message;
    }

    public String error(String message, Throwable t, Object... args) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.error(msg, t);
            return msg;
        }
        return message;
    }

    public String fatal(String message) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.fatal(msg);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Object... args) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.fatal(msg);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Throwable t) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.fatal(msg, t);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Throwable t, Object... args) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.fatal(msg, t);
            return msg;
        }
        return message;
    }

    public String info(String message) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.info(msg);
            return msg;
        }
        return message;
    }

    public String info(String message, Object... args) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.info(msg);
            return msg;
        }
        return message;
    }

    public String info(String message, Throwable t) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.info(msg, t);
            return msg;
        }
        return message;
    }

    public String info(String message, Throwable t, Object... args) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.info(msg, t);
            return msg;
        }
        return message;
    }

    public String warning(String message) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.warn(msg);
            return msg;
        }
        return message;
    }

    public String warning(String message, Object... args) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.warn(msg);
            return msg;
        }
        return message;
    }

    public String warning(String message, Throwable t) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            _logger.warn(msg, t);
            return msg;
        }
        return message;
    }

    public String warning(String message, Throwable t, Object... args) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            _logger.warn(msg, t);
            return msg;
        }
        return message;
    }

}
