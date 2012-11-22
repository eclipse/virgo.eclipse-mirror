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
package org.apache.openejb.assembler.classic.cmd;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.openejb.assembler.classic.OpenEjbConfiguration;
import org.apache.openejb.assembler.classic.ServiceInfo;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.Messages;
import org.apache.openejb.util.OpenEjbVersion;
import org.apache.openejb.util.SuperProperties;
import org.apache.openejb.util.URISupport;

/**
 * @version $Rev: 1153797 $ $Date: 2011-08-04 02:09:44 -0700 (Thu, 04 Aug 2011) $
 */
public class Info2Properties {
    private static Messages messages = new Messages(Info2Properties.class);

    private static final String defaultServerUrl = "ejbd://localhost:4201";

    public static void main(String[] args) {

        CommandLineParser parser = new PosixParser();

        // create the Options
        Options options = new Options();
        options.addOption(option("v", "version", "cmd.properties.opt.version"));
        options.addOption(option("h", "help", "cmd.properties.opt.help"));
        options.addOption(option("s", "server-url", "url", "cmd.properties.opt.server"));

        CommandLine line = null;
        try {
            // parse the command line arguments
            line = parser.parse(options, args);
        } catch (ParseException exp) {
            help(options);
            System.exit(-1);
        }

        if (line.hasOption("help")) {
            help(options);
            System.exit(0);
        } else if (line.hasOption("version")) {
            OpenEjbVersion.get().print(System.out);
            System.exit(0);
        }

        Properties p = new Properties();
        p.put(Context.INITIAL_CONTEXT_FACTORY, "org.apache.openejb.client.RemoteInitialContextFactory");

        String serverUrl = line.getOptionValue("server-url", defaultServerUrl);
        p.put(Context.PROVIDER_URL, serverUrl);

        ConfigurationInfo configInfo = null;
        try {
            InitialContext ctx = new InitialContext(p);
            configInfo = (ConfigurationInfo) ctx.lookup("openejb/ConfigurationInfoBusinessRemote");
        } catch (javax.naming.ServiceUnavailableException e) {
            System.out.println(e.getCause().getMessage());
            System.out.println(messages.format("cmd.deploy.serverOffline"));
            System.exit(1);
        } catch (javax.naming.NamingException e) {
            System.out.println("ConfigurationInfo does not exist in server '" + serverUrl + "', check the server logs to ensure it exists and has not been removed.");
            System.exit(2);
        }

        File tempFile = null;
        try {
            tempFile = File.createTempFile("configrequest", "txt");
            if (!tempFile.exists()) {
                throw new IllegalStateException("Failed to create tmp file: " + tempFile.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Temp file creation failed.");
            e.printStackTrace();
            System.exit(1);
        }

        OpenEjbConfiguration configuration = null;
        try {
            configuration = configInfo.getOpenEjbConfiguration(tempFile);
        } catch (ConfigurationInfo.UnauthorizedException e) {
            System.err.println("This tool is currently crippled to only work with server's on the same physical machine.  See this JIRA issue for details: http://issues.apache.org/jira/browse/OPENEJB-621");
            System.exit(10);
        }

        printConfig(configuration);
    }

    public static void printLocalConfig() {
        OpenEjbConfiguration configuration = SystemInstance.get().getComponent(OpenEjbConfiguration.class);
        if (configuration != null){
            printConfig(configuration);
        }
    }

    public static void printConfig(OpenEjbConfiguration configuration) {
        comment(i18n("cmd.properties.header"));
        comment("");
        comment("");
        comment("Generated by OpenEJB " + OpenEjbVersion.get().getVersion());
        comment("On " + new Date().toString());
        comment("");
        comment("");
        println("");
        println("");

        comment("-------------------------------------------------");
        comment(" Components configurable via openejb.xml");
        comment("-------------------------------------------------");
        println("");
        println("");

        for (ServiceInfo info : configuration.containerSystem.containers) {
            print(info);
        }

        for (ServiceInfo info : configuration.facilities.connectionManagers) {
            print(info);
        }

        for (ServiceInfo info : configuration.facilities.resources) {
            print(info);
        }

        print(configuration.facilities.securityService);

        print(configuration.facilities.transactionService);

        println("");
        comment("-------------------------------------------------");
        comment(" Services configured via conf/<id>.properties");
        comment("-------------------------------------------------");
        println("");
        println("");

        for (ServiceInfo info : configuration.facilities.services) {
            print(info);
        }

        println("");
        comment("-------------------------------------------------");
        comment(" Misc OpenEJB flags and properties");
        comment("-------------------------------------------------");
        println("");
        printSystemProperties();
    }

    private static void printSystemProperties() {

        try {
            SuperProperties p = new SuperProperties();
            p.setSpaceBetweenProperties(false);
            p.setKeyValueSeparator(" = ");
            copyOpenEjbProperties(System.getProperties(), p);
            copyOpenEjbProperties(SystemInstance.get().getProperties(), p);
            p.store(System.out, null);


            Properties p2 = System.getProperties();
            String[] misc = {"os.version", "os.name", "os.arch", "java.version", "java.vendor"};
            for (String prop : misc) {
                comment(prop + "=" + p2.get(prop));
            }
        } catch (IOException e) {
            e.printStackTrace(new PrintWriter(new CommentsFilter(System.out)));
        }
    }

    private static void copyOpenEjbProperties(Properties source, Properties dest) {
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) continue;
            if (!(entry.getValue() instanceof String)) continue;

            String key = (String) entry.getKey();
            if (key.startsWith("openejb.")) {
                dest.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void comment(String text) {
        text = text.replaceAll("\n", "\n# ");
        print("# ");
        println(text);
    }

    private static void print(String text) {
        System.out.print(text);
    }

    private static void println(String text) {
        System.out.println(text);
    }

    private static void print(ServiceInfo info) {
        try {

            println("");

            comment(info.service + "(id=" + info.id + ")");
            comment("className: " + info.className);
            // TODO: the codebase value usually isn't filled in, we should do that.
            // comment("codebase: " + info.codebase);
            comment("");
            SuperProperties p = new SuperProperties();
            p.setSpaceBetweenProperties(false);
            p.setKeyValueSeparator(" = ");


            String uri = "new://" + info.service;
            if (info.service.matches("Container|Resource|Connector")){
                try {
                    Map query = new HashMap();
                    query.put("type", info.types.get(0));
                    uri += "?" + URISupport.createQueryString(query);
                } catch (Exception e) {
                }
            }

            p.put(info.id, uri);
            
            for (Map.Entry<Object, Object> entry : info.properties.entrySet()) {
                if (!(entry.getKey() instanceof String)) continue;
                if (!(entry.getValue() instanceof String)) continue;

                // If property name is 'password' replace value with 'xxxx' to protect it
                if ("password".equalsIgnoreCase((String) entry.getKey())) {
                    p.put(info.id + "." + entry.getKey(), "xxxx");
                } else {
                    p.put(info.id + "." + entry.getKey(), entry.getValue());
                }
            }
            p.store(System.out, null);

        } catch (IOException e) {
            System.out.println("# Printing service(id=" + info.id + ") failed.");
            e.printStackTrace(new PrintWriter(new CommentsFilter(System.out)));
        }

    }

    // Filter out the stupid date comment the Properties.store() method
    // adds seemingly no matter what.
    static class Filter extends java.io.FilterOutputStream {
        private boolean pastFirstLine;

        public Filter(OutputStream out) {
            super(out);
        }

        public void write(int b) throws IOException {
            if (pastFirstLine) super.write(b);
            else pastFirstLine = b == '\n';
        }

    }

    static class CommentsFilter extends java.io.FilterOutputStream {

        public CommentsFilter(OutputStream out) {
            super(out);
        }

        public void write(int b) throws IOException {
            super.write(b);

            if (b == '\n') super.write('#');
        }

    }

    private static void help(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("properties [options]", "\n" + i18n("cmd.properties.description"), options, "\n");
    }

    private static Option option(String shortOpt, String longOpt, String description) {
        return OptionBuilder.withLongOpt(longOpt).withDescription(i18n(description)).create(shortOpt);
    }

    private static Option option(String shortOpt, String longOpt, String argName, String description) {
        return OptionBuilder.withLongOpt(longOpt).withArgName(argName).hasArg().withDescription(i18n(description)).create(shortOpt);
    }

    private static String i18n(String key) {
        return messages.format(key);
    }

}
