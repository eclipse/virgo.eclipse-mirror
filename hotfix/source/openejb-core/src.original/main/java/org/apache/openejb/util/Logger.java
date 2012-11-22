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

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class Logger {
    private static final String SUFFIX = ".Messages";
    private static final String OPENEJB = "org.apache.openejb";
    private static LogStreamFactory logStreamFactory;

    static {
        configure();
    }

    public static void configure() {

        //See if user factory has been specified
        final String factoryName = System.getProperty("openejb.log.factory");

        if (factoryName != null) {

            Class<?> factoryClass = null;

            final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            if (classLoader != null) {
                try {
                    factoryClass = classLoader.loadClass(factoryName);
                } catch (Throwable e) {
                    //Ignore
                }
            }

            if (factoryClass == null) {
                try {
                    factoryClass = Class.forName(factoryName);
                } catch (Throwable e) {
                    //Ignore
                }
            }

            if (factoryClass != null) {
                try {
                    //Try and use the user specified factory
                    logStreamFactory = (LogStreamFactory) factoryClass.newInstance();
                    return;
                } catch (Throwable e) {
                    //Ignore
                }
            }
        }

        // See if log4j is available
        Log4jLogStreamFactory log4jLogStreamFactory = null;

        try {
            Logger.class.getClassLoader().loadClass("org.apache.log4j.Layout");

            if (!System.getProperties().containsKey("org.apache.cxf.Logger")) {
                System.setProperty("org.apache.cxf.Logger", "org.apache.cxf.common.logging.Log4jLogger");
            }

            //This will configure log4j, but we may be using slf4j later
            log4jLogStreamFactory = new Log4jLogStreamFactory();

        } catch (Throwable e) {
            //Ignore
        }

        // Fall back to JUL if log4j is not available
        JuliLogStreamFactory juliLogStreamFactory = null;
        if (null == log4jLogStreamFactory) {
            //This will configure JUL, but we may be using slf4j later
            juliLogStreamFactory = new JuliLogStreamFactory();
        }

        // See if slf4j is available
        try {
            Logger.class.getClassLoader().loadClass("org.slf4j.LoggerFactory");

            if (!System.getProperties().containsKey("org.apache.cxf.Logger")) {
                System.setProperty("org.apache.cxf.Logger", "org.apache.cxf.common.logging.Slf4jLogger");
            }

            //This will delegate to either log4j or JUL
            logStreamFactory = new Slf4jLogStreamFactory();
            return;
        } catch (Throwable e) {
            //Ignore
        }

        //Fall back to either log4j or JUL
        logStreamFactory = (null != log4jLogStreamFactory ? log4jLogStreamFactory : juliLogStreamFactory);
    }

    /**
     * Computes the parent of a resource name. E.g. if we pass in a key of
     * a.b.c, it returns the value a.b
     */
    private static final Computable<String, String> heirarchyResolver = new Computable<String, String>() {
        @Override
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
        @Override
        public ResourceBundle compute(String baseName) throws InterruptedException {
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
    private static final Computable<Object[], Logger> loggerResolver = new Computable<Object[], Logger>() {
        @Override
        public Logger compute(Object[] args) throws InterruptedException {
            LogCategory category = (LogCategory) args[0];
            LogStream logStream = logStreamFactory.createLogStream(category);
            String baseName = (String) args[1];
            return new Logger(category, logStream, baseName);
        }
    };

    /**
     * Creates a MessageFormat object for a message and returns it
     */
    private static final Computable<String, MessageFormat> messageFormatResolver = new Computable<String, MessageFormat>() {
        @Override
        public MessageFormat compute(String message) throws InterruptedException {
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
    private static final Computable<Object[], Logger> loggerCache = new Memoizer<Object[], Logger>(loggerResolver);

    /**
     * Cache of MessageFormats
     */
    private static final Computable<String, MessageFormat> messageFormatCache = new Memoizer<String, MessageFormat>(messageFormatResolver);

    /**
     * Finds a Logger from the cache and returns it. If not found in cache then builds a Logger and returns it.
     *
     * @param category - The category of the logger
     * @param baseName - The baseName for the ResourceBundle
     * @return Logger
     */
    public static Logger getInstance(LogCategory category, String baseName) {
        try {
            return loggerCache.compute(new Object[]{category, baseName});
        } catch (InterruptedException e) {
            // Don't return null here. Just create a new Logger and set it up.
            // It will not be stored in the cache, but a later lookup for the
            // same Logger would probably end up in the cache
            LogStream logStream = logStreamFactory.createLogStream(category);
            return new Logger(category, logStream, baseName);
        }
    }

    private final LogCategory category;
    private final LogStream logStream;
    private final String baseName;

    public Logger(LogCategory category, LogStream logStream, String baseName) {
        this.category = category;
        this.logStream = logStream;
        this.baseName = baseName;
    }

    public static Logger getInstance(LogCategory category, Class clazz) {
        return getInstance(category, packageName(clazz));
    }

    private static String packageName(Class clazz) {
        String name = clazz.getName();
        return name.substring(0, name.lastIndexOf("."));
    }

    public Logger getChildLogger(String child) {
        return Logger.getInstance(this.category.createChild(child), this.baseName);
    }

    /**
     * Formats a given message
     *
     * @param message String
     * @param args    Object...
     * @return the formatted message
     */
    private String formatMessage(String message, Object... args) {
        if (args.length == 0) return message;

        try {
            final MessageFormat mf = messageFormatCache.compute(message);
            return mf.format(args);
        } catch (Exception e) {
            return "Error in formatting message " + message;
        }

    }

    public boolean isDebugEnabled() {
        return logStream.isDebugEnabled();
    }

    public boolean isErrorEnabled() {
        return logStream.isErrorEnabled();
    }

    public boolean isFatalEnabled() {
        return logStream.isFatalEnabled();
    }

    public boolean isInfoEnabled() {
        return logStream.isInfoEnabled();
    }

    public boolean isWarningEnabled() {
        return logStream.isWarnEnabled();
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
            logStream.debug(msg);
            return msg;
        }
        return message;
    }

    public String debug(String message, Object... args) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.debug(msg);
            return msg;
        }
        return message;
    }

    public String debug(String message, Throwable t) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.debug(msg, t);
            return msg;
        }
        return message;
    }

    public String debug(String message, Throwable t, Object... args) {

        if (isDebugEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.debug(msg, t);
            return msg;
        }
        return message;
    }

    public String error(String message) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.error(msg);
            return msg;
        }
        return message;
    }

    public String error(String message, Object... args) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.error(msg);
            return msg;
        }
        return message;
    }

    public String error(String message, Throwable t) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.error(msg, t);
            return msg;
        }
        return message;
    }

    public String error(String message, Throwable t, Object... args) {

        if (isErrorEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.error(msg, t);
            return msg;
        }
        return message;
    }

    public String fatal(String message) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.fatal(msg);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Object... args) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.fatal(msg);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Throwable t) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.fatal(msg, t);
            return msg;
        }
        return message;
    }

    public String fatal(String message, Throwable t, Object... args) {
        if (isFatalEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.fatal(msg, t);
            return msg;
        }
        return message;
    }

    public String info(String message) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.info(msg);
            return msg;
        }
        return message;
    }

    public String info(String message, Object... args) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.info(msg);
            return msg;
        }
        return message;
    }

    public String info(String message, Throwable t) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.info(msg, t);
            return msg;
        }
        return message;
    }

    public String info(String message, Throwable t, Object... args) {
        if (isInfoEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.info(msg, t);
            return msg;
        }
        return message;
    }

    public String warning(String message) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.warn(msg);
            return msg;
        }
        return message;
    }

    public String warning(String message, Object... args) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.warn(msg);
            return msg;
        }
        return message;
    }

    public String warning(String message, Throwable t) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            logStream.warn(msg, t);
            return msg;
        }
        return message;
    }

    public String warning(String message, Throwable t, Object... args) {
        if (isWarningEnabled()) {
            String msg = getMessage(message, baseName);
            msg = formatMessage(msg, args);
            logStream.warn(msg, t);
            return msg;
        }
        return message;
    }

    /**
     * Given a key and a baseName, this method computes a message for a key. if
     * the key is not found in this ResourceBundle for this baseName, then it
     * recursively looks up its parent to find the message for a key. If no
     * message is found for a key, the key is returned as is and is logged by
     * the logger.
     *
     * @param key      String
     * @param baseName String
     * @return String
     */
    private String getMessage(String key, String baseName) {
        try {

            ResourceBundle bundle = bundleCache.compute(baseName);
            if (bundle != null) {
                try {
                    return bundle.getString(key);
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

}
